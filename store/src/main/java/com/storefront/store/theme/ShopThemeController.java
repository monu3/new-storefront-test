package com.storefront.store.theme;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The shop's own theme editor (D8).
 *
 * <p>No shop id anywhere — the shop is the caller's own (D35) — and
 * {@link ThemeService} independently confirms that a theme or asset belongs to it.
 *
 * <p>The draft/published distinction is the important part of this surface. A shop may keep
 * several themes, exactly one of which is live, and a live theme cannot be edited: saving a
 * half-finished configuration over what a customer is currently looking at is the one thing this
 * API exists to prevent.
 */
@RestController
@RequestMapping("/api/v1/shop/theme")
@Tag(name = "Shop theme", description = "Install, customise, preview and publish the storefront theme")
@PreAuthorize("hasRole('SHOP')")
@RequiredArgsConstructor
public class ShopThemeController {

	private final ThemeService themes;
	private final TenantGuard tenantGuard;
	private final com.storefront.store.subscription.EntitlementService entitlements;
	private final CurrentUser currentUser;
	private final StorefrontCacheInvalidator cacheInvalidator;

	// ------------------------------------------------------------------ themes

	/** The shop's themes, live one first. */
	@GetMapping
	@Operation(summary = "The shop's themes",
			description = "Several drafts and at most one published.")
	public ThemeListResponse list() {
		Shop shop = tenantGuard.requireOwnedShop();
		// A shop that has never touched its theme still gets a usable default, created lazily.
		themes.ensureDefaultTheme(shop, currentUser.optionalUserId());

		List<ThemeView> all = themes.themesOf(shop).stream().map(t -> toView(shop, t)).toList();

		return new ThemeListResponse(
				all.stream().filter(ThemeView::published).findFirst().orElse(null),
				all,
				// Whether the storefront is currently showing a real theme or the platform default,
				// so the editor can say so plainly rather than the owner wondering why their edits
				// are not showing.
				themes.hasPublishedTheme(shop));
	}

	@GetMapping("/{themeId}")
	@Operation(summary = "One theme with its document")
	public ThemeDetail get(@PathVariable UUID themeId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopTheme theme = themes.getOwnedTheme(shop, themeId);

		return new ThemeDetail(toView(shop, theme), theme.getConfig(),
				// Warnings, not errors: a section may reference an asset the owner has not uploaded
				// yet, and refusing to save would make the editor fight them.
				themes.danglingAssetHandles(shop, theme.getConfig()));
	}

	/**
	 * The template marketplace, filtered to what this shop can install.
	 *
	 * <p>Filtered by the shop's own type (D17 payoff) and by its subscription tier, so a shop is
	 * never shown something it cannot have.
	 */
	@GetMapping("/marketplace")
	@Operation(summary = "Templates available to this shop",
			description = "Filtered by the shop's type and subscription tier.")
	public List<MarketplaceEntry> marketplace() {
		Shop shop = tenantGuard.requireOwnedShop();

		// Read from the entitlement rather than from `!shop.paysCommission()`. They agree today, but
		// they are different questions, and the filter here and the gate in `installTemplate` must
		// not be able to drift apart.
		boolean subscribed = entitlements.commissionsWaived(shop.getId());

		return themes.marketplaceFor(shop, subscribed).stream()
				.map(t -> new MarketplaceEntry(
						t.getId(), t.getCode(), t.getName(), t.getDescription(),
						t.shopTypeOrNull(), t.getPriceTier(),
						themes.templatePreviewUrl(t),
						themes.sectionCount(t.getConfig()),
						t.getConfig()))
				.toList();
	}

	/** Installs a template as a new draft. The config is copied, never referenced. */
	@PostMapping("/install/{templateId}")
	@Operation(summary = "Install a template as a draft",
			description = "The config is copied, so later edits to the template do not change this shop's storefront.")
	public ThemeView install(@PathVariable UUID templateId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ThemeTemplate template = themes.getTemplate(templateId);
		ShopTheme installed = themes.installTemplate(shop, template, currentUser.optionalUserId());
		return toView(shop, installed);
	}

	/** Saves a draft. Refused while the theme is published. */
	@PatchMapping("/{themeId}")
	@Operation(summary = "Save a draft",
			description = "Refused if the theme is live. Unpublish first so a customer never sees a half-saved theme.")
	public ThemeDetail save(@PathVariable UUID themeId, @Valid @RequestBody SaveThemeRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopTheme theme = themes.saveTheme(shop, themeId, request.config(), request.name());

		return new ThemeDetail(toView(shop, theme), theme.getConfig(),
				themes.danglingAssetHandles(shop, theme.getConfig()));
	}

	@PostMapping("/{themeId}/publish")
	@Operation(summary = "Publish a theme to the storefront")
	public ThemeView publish(@PathVariable UUID themeId) {
		Shop shop = tenantGuard.requireOwnedShop();
		return toView(shop, themes.publishTheme(shop, themeId, currentUser.optionalUserId()));
	}

	/**
	 * Saves and publishes in one request.
	 *
	 * <p>This is what the Appearance screen's Publish button calls. The pair of endpoints above
	 * forces an owner to unpublish, edit, then publish — and in the gap the storefront falls back to
	 * the platform default, so a customer's page visibly changes to someone else's template and
	 * back. Doing it in one transaction closes that window: a reader sees the old published theme
	 * or the new one, never a gap and never a blend.
	 *
	 * <p>The plain save endpoint keeps its refusal. It exists for genuinely draft work, and a bare
	 * save of a live theme would genuinely show customers a work in progress.
	 */
	@PostMapping("/{themeId}/save-and-publish")
	@Operation(summary = "Save and publish a theme atomically",
			description = "The storefront never falls back to the platform default, because the config "
					+ "and the published flag are written in one transaction.")
	public ThemeDetail saveAndPublish(@PathVariable UUID themeId,
			@Valid @RequestBody SaveThemeRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopTheme theme = themes.saveAndPublishTheme(shop, themeId, request.config(), request.name(),
				currentUser.optionalUserId());

		// The storefront caches this page, so without this the owner sees "published" while the
		// page they are about to share still serves the old design.
		cacheInvalidator.invalidate(shop.getSlug());

		return new ThemeDetail(toView(shop, theme), theme.getConfig(),
				themes.danglingAssetHandles(shop, theme.getConfig()));
	}

	@PostMapping("/{themeId}/unpublish")
	@Operation(summary = "Withdraw a theme from the storefront",
			description = "The storefront falls back to the platform default, so it keeps working.")
	public ThemeView unpublish(@PathVariable UUID themeId) {
		Shop shop = tenantGuard.requireOwnedShop();
		return toView(shop, themes.unpublishTheme(shop, themeId));
	}

	/** Resets a draft to a template's config, keeping the theme's own id and name. */
	@PostMapping("/{themeId}/reset/{templateId}")
	@Operation(summary = "Reset a draft back to a template")
	public ThemeDetail reset(@PathVariable UUID themeId, @PathVariable UUID templateId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopTheme theme = themes.resetToTemplate(shop, themeId, templateId);

		return new ThemeDetail(toView(shop, theme), theme.getConfig(),
				themes.danglingAssetHandles(shop, theme.getConfig()));
	}

	@DeleteMapping("/{themeId}")
	@Operation(summary = "Delete a draft", description = "Refused while the theme is published.")
	public void delete(@PathVariable UUID themeId) {
		Shop shop = tenantGuard.requireOwnedShop();
		themes.deleteTheme(shop, themeId);
	}

	// ------------------------------------------------------------------ assets

	@GetMapping("/assets")
	@Operation(summary = "The shop's theme assets")
	public List<AssetView> assets(@RequestParam(required = false) ThemeAsset.Kind kind) {
		Shop shop = tenantGuard.requireOwnedShop();
		return themes.assetsOf(shop, kind).stream().map(this::toAssetView).toList();
	}

	/**
	 * Creates or replaces an asset.
	 *
	 * <p>Upsert by handle, because that is how a section refers to it: replacing the image behind
	 * {@code footer-main} must not mean editing every section that used it.
	 */
	@PutMapping("/assets")
	@Operation(summary = "Create or replace an asset",
			description = "Upsert by kind and handle, so re-pointing an asset needs no section edits.")
	public AssetView putAsset(@Valid @RequestBody PutAssetRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		return toAssetView(themes.putAsset(shop, request.kind(), request.handle(), request.name(),
				request.config()));
	}

	@DeleteMapping("/assets/{assetId}")
	@Operation(summary = "Delete an asset",
			description = "A section still referencing it shows a placeholder, not an error.")
	public void deleteAsset(@PathVariable UUID assetId) {
		Shop shop = tenantGuard.requireOwnedShop();
		themes.deleteAsset(shop, assetId);
	}

	/**
	 * What the storefront should render right now.
	 *
	 * <p>The editor's preview reads this, so what the owner sees is what a customer gets — which is
	 * the Phase 3 exit criterion stated as an endpoint.
	 */
	@GetMapping("/preview")
	@Operation(summary = "The document the storefront renders",
			description = "The live theme if published, otherwise the platform default for this shop's type.")
	public PreviewResponse preview() {
		Shop shop = tenantGuard.requireOwnedShop();
		String config = themes.renderableConfig(shop);

		return new PreviewResponse(
				config,
				themes.sectionCount(config),
				themes.hasPublishedTheme(shop),
				shop.getType().name(),
				DefaultTemplates.forShopType(shop.getType()));
	}

	// ------------------------------------------------------------------ requests

	public record SaveThemeRequest(
			/** The theme document, as JSON. Validated on write. */
			@NotBlank String config,
			@Size(max = 120) String name) {
	}

	public record PutAssetRequest(
			@NotNull ThemeAsset.Kind kind,
			/** Lowercase letters, digits and dashes. Stable: sections refer to it. */
			@NotBlank @Size(max = 40) String handle,
			@NotBlank @Size(max = 120) String name,
			/** The asset's content, as JSON. Shape depends on the kind. */
			String config) {
	}

	// ------------------------------------------------------------------ responses

	public record ThemeView(
			UUID id,
			String name,
			boolean published,
			java.time.Instant publishedAt,
			UUID templateId,
			int schemaVersion,
			java.time.Instant updatedAt) {
	}

	public record ThemeDetail(ThemeView theme, String config, List<String> warnings) {
	}

	public record ThemeListResponse(ThemeView published, List<ThemeView> themes,
			boolean hasPublishedTheme) {
	}

	/**
	 * One template in the marketplace.
	 *
	 * <p><b>`config` is included so the gallery can render a real preview.</b> Without it the card
	 * could only show a photograph — and `preview_media_id` is null on every template that has ever
	 * been seeded here, so in practice it would show nothing at all. A shop owner choosing a theme
	 * is choosing a layout; making them install it first to see it is exactly the friction the
	 * gallery exists to remove.
	 *
	 * <p>This leaks nothing: installing a template already copies this exact config into the shop's
	 * own theme, where the owner can read and edit it.
	 *
	 * <p>Cost, stated rather than hidden: the list now carries one config per template. Each is a
	 * few KB and the list is filtered to the shop's type and tier, so it stays small in practice.
	 * When the catalogue grows past a screenful, this becomes a per-card fetch.
	 */
	public record MarketplaceEntry(
			UUID id,
			String code,
			String name,
			String description,
			ShopType shopType,
			ThemeTemplate.PriceTier priceTier,
			String previewImageUrl,
			int sectionCount,
			String config) {
	}

	public record AssetView(
			UUID id,
			ThemeAsset.Kind kind,
			String handle,
			String name,
			String config,
			java.time.Instant updatedAt) {
	}

	/**
	 * The renderable document.
	 *
	 * @param platformDefault included so the editor can offer "start from the default" and show
	 *                        what the fallback actually is, rather than describing it
	 */
	public record PreviewResponse(
			String config,
			int sectionCount,
			boolean fromPublishedTheme,
			String shopType,
			String platformDefault) {
	}

	private ThemeView toView(Shop shop, ShopTheme theme) {
		return new ThemeView(
				theme.getId(),
				theme.getName(),
				theme.isPublished(),
				theme.getPublishedAt(),
				theme.getTemplateId(),
				theme.getSchemaVersion(),
				theme.getUpdatedAt());
	}

	private AssetView toAssetView(ThemeAsset asset) {
		return new AssetView(asset.getId(), asset.getKind(), asset.getHandle(), asset.getName(),
				asset.getConfig(), asset.getUpdatedAt());
	}
}
