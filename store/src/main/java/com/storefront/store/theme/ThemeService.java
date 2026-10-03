package com.storefront.store.theme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.media.MediaAssetRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Themes: templates, a shop's installed themes, and its assets.
 *
 * <p>Three rules run through the whole class:
 *
 * <ul>
 *   <li><b>Every write is validated.</b> A theme that reaches the database unvalidated is a
 *       storefront that fails in front of a customer, and the owner has no way to tell which
 *       change caused it.</li>
 *   <li><b>A live theme is never edited in place.</b> {@code replaceConfig} refuses while
 *       published, so a half-saved theme can never be the thing a customer is looking at.</li>
 *   <li><b>Installing copies the config.</b> A shop's storefront must not restyle itself because
 *       an admin edited the template it came from.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ThemeService {

	private final ThemeTemplateRepository templates;
	private final ShopThemeRepository shopThemes;
	private final ThemeAssetRepository assets;
	private final ShopRepository shops;
	private final MediaAssetRepository media;
	private final ThemeValidator validator;
	private final ObjectMapper json;

	// ------------------------------------------------------------------ templates

	/**
	 * Creates a template.
	 *
	 * <p>A draft: no {@code publishedAt}, so it is absent from the marketplace until an admin
	 * publishes it. That staging step matters because a template is a lot of JSON to get wrong in
	 * public.
	 */
	@Transactional
	public ThemeTemplate createTemplate(String code, String name, String description,
			ShopType shopType, String configJson, ThemeTemplate.PriceTier tier, UUID actorId) {

		String config = validator.validateAndNormalise(configJson);

		if (templates.existsByCode(code)) {
			// The code appears in seed scripts and URLs, so it has to be stable. Renaming one would
			// break every script that references it.
			throw new IllegalArgumentException("A template with code '" + code + "' already exists");
		}

		ThemeTemplate template = new ThemeTemplate(code, name, config, shopType);
		template.setDescription(description);
		template.setPriceTier(tier == null ? ThemeTemplate.PriceTier.FREE : tier);
		template.setCreatedBy(actorId);

		ThemeTemplate saved = templates.saveAndFlush(template);
		log.info("Template '{}' created ({} sections), draft", code, sectionCount(config));
		return saved;
	}

	/**
	 * Updates a published template.
	 *
	 * <p>Refuses. A template that changes after shops have installed it does not change their
	 * storefronts — they hold a copy — so editing it would only confuse anyone who installs it
	 * again expecting the version they saw in the preview.
	 */
	@Transactional
	public ThemeTemplate updateTemplate(UUID templateId, String name, String description,
			ShopType shopType, String configJson, Integer sortOrder, UUID previewMediaId) {

		ThemeTemplate template = loadTemplate(templateId);
		if (template.isPublished()) {
			throw new IllegalStateException(
					"Unpublish the template before editing it. Shops have already installed it.");
		}

		if (name != null && !name.isBlank()) {
			template.setName(name.trim());
		}
		template.setDescription(description);
		if (shopType != null) {
			template.changeType(shopType);
		}
		if (sortOrder != null) {
			// Nullable so a PATCH that does not mention it leaves it alone. Taking a primitive
			// made a partial update throw an unboxing NPE — which surfaces as a 500 for an
			// ordinary "rename this template" request.
			template.setSortOrder(sortOrder);
		}
		if (previewMediaId != null) {
			template.setPreviewMediaId(previewMediaId);
		}
		if (configJson != null && !configJson.isBlank()) {
			template.setConfig(validator.validateAndNormalise(configJson));
		}
		return templates.saveAndFlush(template);
	}

	@Transactional
	public ThemeTemplate publishTemplate(UUID templateId, UUID actorId) {
		ThemeTemplate template = loadTemplate(templateId);
		template.publish();
		log.info("Template '{}' published to the marketplace", template.getCode());
		return templates.saveAndFlush(template);
	}

	@Transactional
	public ThemeTemplate unpublishTemplate(UUID templateId) {
		ThemeTemplate template = loadTemplate(templateId);
		template.unpublish();
		log.info("Template '{}' withdrawn from the marketplace; installs are untouched",
				template.getCode());
		return templates.saveAndFlush(template);
	}

	@Transactional
	public ThemeTemplate setTemplateActive(UUID templateId, boolean active) {
		ThemeTemplate template = loadTemplate(templateId);
		template.setActive(active);
		return templates.saveAndFlush(template);
	}

	@Transactional(readOnly = true)
	public ThemeTemplate getTemplate(UUID templateId) {
		return loadTemplate(templateId);
	}

	/**
	 * The marketplace, filtered to what a shop may install.
	 *
	 * <p>Filtered in memory because the "untagged template suits every type" half of the condition
	 * has no useful index, and the catalogue is tens of rows.
	 */
	@Transactional(readOnly = true)
	public List<ThemeTemplate> marketplaceFor(Shop shop, boolean subscribed) {
		return templates.findByActiveTrueAndPublishedAtIsNotNullOrderBySortOrderAscNameAsc().stream()
				.filter(t -> t.isOfferedTo(shop.getType(), subscribed))
				.toList();
	}

	@Transactional(readOnly = true)
	public Page<ThemeTemplate> allTemplates(Pageable pageable) {
		return templates.findByOrderBySortOrderAscNameAsc(pageable);
	}

	// ------------------------------------------------------------------ shop themes

	/**
	 * Gives a shop its default theme, if it has none.
	 *
	 * <p>Called at shop creation and safe to call again. <b>A shop is never left with no
	 * theme</b>: an empty storefront is the first thing a new customer sees, and a blank page reads
	 * as a broken shop rather than a new one.
	 *
	 * <p>The default is a <em>draft</em>. It renders as the fallback at read time, so the shop has
	 * a working storefront immediately, but the owner publishes their own arrangement explicitly.
	 */
	@Transactional
	public ShopTheme ensureDefaultTheme(Shop shop, UUID actorId) {
		if (shopThemes.countByShopId(shop.getId()) > 0) {
			return shopThemes.findByShopIdOrderByUpdatedAtDesc(shop.getId()).get(0);
		}
		String config = validator.validateAndNormalise(DefaultTemplates.forShopType(shop.getType()));
		ShopTheme theme = shopThemes.saveAndFlush(new ShopTheme(shop.getId(), "Default", config,
				null, actorId));
		log.info("Default {} theme created for shop {}", shop.getType(), shop.getId());
		return theme;
	}

	/**
	 * Installs a template as a new draft.
	 *
	 * <p>The config is <b>copied</b>. A shop's storefront must not restyle itself because an admin
	 * edited a template, and must not break because a template was deleted — which is why
	 * {@code shop_theme.template_id} is provenance and {@code ON DELETE SET NULL}.
	 */
	@Transactional
	public ShopTheme installTemplate(Shop shop, ThemeTemplate template, UUID actorId) {
		String config = validator.validateAndNormalise(template.getConfig());

		ShopTheme theme = shopThemes.saveAndFlush(new ShopTheme(shop.getId(),
				template.getName(), config, template.getId(), actorId));
		log.info("Template '{}' installed in shop {} as a draft", template.getCode(), shop.getId());
		return theme;
	}

	/**
	 * Saves a draft.
	 *
	 * <p>Refuses to edit a live theme: a customer must never be looking at a half-saved
	 * configuration.
	 */
	@Transactional
	public ShopTheme saveTheme(Shop shop, UUID themeId, String configJson, String name) {
		ShopTheme theme = requireOwnedTheme(shop, themeId);
		theme.replaceConfig(validator.validateAndNormalise(configJson));
		theme.rename(name);
		return shopThemes.saveAndFlush(theme);
	}

	/**
	 * Publishes a theme.
	 *
	 * <p>Refuses a second published theme rather than silently replacing the live one. The partial
	 * unique index would reject it anyway; this produces the message.
	 */
	@Transactional
	public ShopTheme publishTheme(Shop shop, UUID themeId, UUID actorId) {
		ShopTheme theme = requireOwnedTheme(shop, themeId);
		boolean anotherIsLive = shopThemes.findByShopIdAndPublishedTrue(shop.getId())
				.filter(live -> !live.getId().equals(themeId))
				.isPresent();

		theme.publish(anotherIsLive);
		log.info("Theme {} published for shop {}", themeId, shop.getId());
		return shopThemes.saveAndFlush(theme);
	}

	@Transactional
	public ShopTheme unpublishTheme(Shop shop, UUID themeId) {
		ShopTheme theme = requireOwnedTheme(shop, themeId);
		theme.unpublish();
		return shopThemes.saveAndFlush(theme);
	}

	/**
	 * Resets a draft back to a template's config.
	 *
	 * <p>A copy, for the same reason as installing. The theme keeps its own id and name, so
	 * anything referencing it still resolves.
	 */
	@Transactional
	public ShopTheme resetToTemplate(Shop shop, UUID themeId, UUID templateId) {
		ShopTheme theme = requireOwnedTheme(shop, themeId);
		ThemeTemplate template = loadTemplate(templateId);
		theme.replaceConfig(validator.validateAndNormalise(template.getConfig()));
		theme.setTemplateId(templateId);
		return shopThemes.saveAndFlush(theme);
	}

	@Transactional
	public void deleteTheme(Shop shop, UUID themeId) {
		ShopTheme theme = requireOwnedTheme(shop, themeId);
		if (theme.isPublished()) {
			throw new IllegalStateException("Unpublish the theme before deleting it.");
		}
		shopThemes.delete(theme);
		shopThemes.flush();
	}

	@Transactional(readOnly = true)
	public ShopTheme getOwnedTheme(Shop shop, UUID themeId) {
		return requireOwnedTheme(shop, themeId);
	}

	@Transactional(readOnly = true)
	public List<ShopTheme> themesOf(Shop shop) {
		return shopThemes.findByShopIdOrderByUpdatedAtDesc(shop.getId());
	}

	/** The live theme, or null. The storefront falls back to the default when this is empty. */
	@Transactional(readOnly = true)
	public Optional<ShopTheme> publishedTheme(Shop shop) {
		return shopThemes.findByShopIdAndPublishedTrue(shop.getId());
	}

	// ------------------------------------------------------------------ assets

	/**
	 * Creates or replaces a shop's asset.
	 *
	 * <p>Upsert by handle, because that is how a section refers to it. Replacing the image behind
	 * {@code footer-main} must not require the owner to edit every section that used it.
	 */
	@Transactional
	public ThemeAsset putAsset(Shop shop, ThemeAsset.Kind kind, String handle, String name,
			String configJson) {

		if (!ThemeValidator.isValidHandle(handle)) {
			throw new IllegalArgumentException(
					"A handle must be lowercase letters, digits and dashes: '" + handle + "'");
		}
		JsonNode config = readJson(configJson);

		return assets.findByShopIdAndKindAndHandle(shop.getId(), kind, handle)
				.map(existing -> {
					existing.replaceConfig(configJson);
					existing.rename(name);
					return assets.saveAndFlush(existing);
				})
				.orElseGet(() -> assets.saveAndFlush(
						new ThemeAsset(shop.getId(), kind, handle, name, configJson)));
	}

	@Transactional
	public void deleteAsset(Shop shop, UUID assetId) {
		ThemeAsset asset = requireOwnedAsset(shop, assetId);
		assets.delete(asset);
		assets.flush();
	}

	@Transactional(readOnly = true)
	public ThemeAsset getOwnedAsset(Shop shop, UUID assetId) {
		return requireOwnedAsset(shop, assetId);
	}

	@Transactional(readOnly = true)
	public List<ThemeAsset> assetsOf(Shop shop, ThemeAsset.Kind kind) {
		return kind == null
				? assets.findByShopIdOrderByKindAscHandleAsc(shop.getId())
				: assets.findByShopIdAndKindOrderByHandleAsc(shop.getId(), kind);
	}

	// ------------------------------------------------------------------ rendering input

	/**
	 * The document a storefront should render.
	 *
	 * <p>Falls back to the type's default when the shop has published nothing. The fallback is
	 * computed rather than stored, so a shop that has never touched its theme still has a
	 * complete storefront, and the default improves for everyone when it is improved for one.
	 */
	@Transactional(readOnly = true)
	public String renderableConfig(Shop shop) {
		return publishedTheme(shop)
				.map(ShopTheme::getConfig)
				.orElseGet(() -> {
					try {
						return validator.validateAndNormalise(
								DefaultTemplates.forShopType(shop.getType()));
					} catch (ThemeValidationException e) {
						// The defaults are asserted valid by a test, so this should be unreachable.
						// Falling back to a bare theme keeps a storefront rendering rather than
						// throwing on every page view.
						log.error("Default theme for {} failed validation: {}",
								shop.getType(), e.getMessage());
						return "{\"schemaVersion\":1,\"tokens\":{},\"sections\":[]}";
					}
				});
	}

	/** Whether {@code renderableConfig} used a real published theme or the default. */
	@Transactional(readOnly = true)
	public boolean hasPublishedTheme(Shop shop) {
		return publishedTheme(shop).isPresent();
	}

	/**
	 * Handles a theme references that the shop has not created.
	 *
	 * <p>A <b>warning, not an error</b>. A shop can lay out a page before uploading its
	 * photograph, and refusing to save would make the editor fight them. The renderer shows a
	 * placeholder, which is exactly what it is for.
	 *
	 * @return one message per dangling handle, empty when the theme is self-consistent
	 */
	public List<String> danglingAssetHandles(Shop shop, String configJson) {
		Set<String> owned = new HashSet<>();
		for (ThemeAsset asset : assets.findByShopId(shop.getId())) {
			owned.add(asset.getKind().name() + "/" + asset.getHandle());
		}

		List<String> problems = new ArrayList<>();
		try {
			JsonNode root = json.readTree(configJson);
			JsonNode sections = root.path("sections");
			for (JsonNode section : sections) {
				String image = section.path("props").path("imageUrl").asText("");
				if (!image.isBlank() && image.startsWith("asset:")) {
					String reference = image.substring("asset:".length());
					if (!owned.contains(reference)) {
						problems.add("Section '" + section.path("id").asText("?")
								+ "' references asset " + reference + ", which does not exist");
					}
				}
			}
		} catch (Exception e) {
			problems.add("The theme could not be inspected: " + e.getMessage());
		}
		return problems;
	}

	/** How many sections a config has, for a summary. Never throws on bad input. */
	public int sectionCount(String configJson) {
		try {
			return json.readTree(configJson).path("sections").size();
		} catch (Exception e) {
			return 0;
		}
	}

	// ------------------------------------------------------------------ helpers

	private ThemeTemplate loadTemplate(UUID templateId) {
		return templates.findById(templateId)
				.orElseThrow(() -> new TemplateNotFoundException(templateId));
	}

	/**
	 * Loads one of the shop's own themes.
	 *
	 * <p>A theme belonging to another shop is reported as not found rather than forbidden: a 403
	 * would confirm the id exists, turning this into an existence oracle.
	 */
	private ShopTheme requireOwnedTheme(Shop shop, UUID themeId) {
		return shopThemes.findByIdAndShopId(themeId, shop.getId())
				.orElseThrow(() -> new ThemeNotFoundException(themeId));
	}

	private ThemeAsset requireOwnedAsset(Shop shop, UUID assetId) {
		return assets.findByIdAndShopId(assetId, shop.getId())
				.orElseThrow(() -> new AssetNotFoundException(assetId));
	}

	private JsonNode readJson(String configJson) {
		if (configJson == null || configJson.isBlank()) {
			return json.createObjectNode();
		}
		try {
			return json.readTree(configJson);
		} catch (Exception e) {
			throw new IllegalArgumentException("Not valid JSON: " + e.getMessage());
		}
	}

	/**
	 * The preview image for a template, or null.
	 *
	 * <p>Read through {@link MediaAssetRepository} rather than stored on the template, so a
	 * deleted media row leaves a null preview instead of a dangling reference that renders as a
	 * broken image in the marketplace.
	 */
	public String templatePreviewUrl(ThemeTemplate template) {
		UUID mediaId = template.getPreviewMediaId();
		if (mediaId == null) {
			return null;
		}
		return media.findById(mediaId)
				.map(com.storefront.store.media.MediaAsset::displayUrl)
				.orElse(null);
	}

	/** Maps to HTTP 404. */
	public static class TemplateNotFoundException extends RuntimeException {
		public TemplateNotFoundException(UUID id) {
			super("No theme template " + id);
		}
	}

	/** Maps to HTTP 404, including for a theme that belongs to another shop. */
	public static class ThemeNotFoundException extends RuntimeException {
		public ThemeNotFoundException(UUID id) {
			super("No theme " + id);
		}
	}

	/** Maps to HTTP 404. */
	public static class AssetNotFoundException extends RuntimeException {
		public AssetNotFoundException(UUID id) {
			super("No theme asset " + id);
		}
	}
}
