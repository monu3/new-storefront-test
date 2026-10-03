package com.storefront.store.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Templates, installing, publishing and assets.
 *
 * <p>Three properties carry most of the weight, and each is a place where a plausible
 * implementation is quietly wrong:
 *
 * <ul>
 *   <li><b>A shop is never left without a theme.</b> A blank storefront is the first thing a new
 *       customer sees, and it reads as a broken shop rather than a new one.</li>
 *   <li><b>Installing copies the config.</b> Editing a template must not restyle every shop that
 *       installed it.</li>
 *   <li><b>A live theme is never edited.</b> Saving a half-finished configuration over what a
 *       customer is looking at is the one thing this API exists to prevent.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ThemeServiceIT {

	@Autowired
	private ThemeService themes;

	@Autowired
	private ThemeTemplateRepository templates;

	@Autowired
	private ShopThemeRepository shopThemes;

	@Autowired
	private ThemeAssetRepository assets;

	@Autowired
	private TestFixtures fixtures;

	/**
	 * A real user for {@code created_by}.
	 *
	 * <p>It is a genuine foreign key to {@code app_user}, so a fabricated UUID fails the
	 * constraint before the assertion is reached.
	 */
	private UUID actor() {
		return fixtures.owner().getId();
	}

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Theme Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.PHARMACY);
	}

	private String minimalTheme(String heading) {
		return """
				{
				  "schemaVersion": 1,
				  "tokens": { "primary": "#0d9488" },
				  "sections": [
				    { "id": "hero", "type": "hero",
				      "props": { "heading": "%s", "cta": { "label": "Go", "href": "#x" } } }
				  ],
				  "nav": { "menu": [] },
				  "footer": {}
				}
				""".formatted(heading);
	}

	// ------------------------------------------------------------------ defaults

	@Nested
	@DisplayName("the default theme")
	class Defaults {

		@Test
		@DisplayName("is created on demand, so a shop is never storefront-less")
		void createdOnDemand() {
			// A blank storefront reads as a broken shop, and that is the first thing a new
			// customer sees.
			assertThat(shopThemes.countByShopId(shop.getId())).isZero();

			ShopTheme theme = themes.ensureDefaultTheme(shop, actor());

			assertThat(theme.getStatus()).isEqualTo(ShopTheme.Status.DRAFT);
			assertThat(theme.getConfig()).contains("pharmacy");
		}

		@Test
		@DisplayName("is a draft, so the storefront uses the fallback until published")
		void defaultIsADraft() {
			ShopTheme theme = themes.ensureDefaultTheme(shop, actor());

			assertThat(themes.hasPublishedTheme(shop)).isFalse();
			assertThat(themes.renderableConfig(shop))
					.as("a draft still renders, from the platform default")
					.isNotBlank();
		}

		@Test
		@DisplayName("is per shop type")
		void variesByShopType() {
			// A pharmacy's storefront leads with "prescriptions accepted"; a fashion one leads
			// with a hero. The default has to speak to the vertical it is serving.
			assertThat(themes.renderableConfig(shop)).contains("pharmacy");

			Shop fashion = fixtures.createVerifiedShop(
					"Fashion Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.FASHION);
			themes.ensureDefaultTheme(fashion, actor());

			assertThat(themes.renderableConfig(fashion)).contains("Playfair");
		}

		@Test
		@DisplayName("is only created once")
		void idempotent() {
			ShopTheme first = themes.ensureDefaultTheme(shop, actor());
			ShopTheme second = themes.ensureDefaultTheme(shop, actor());

			assertThat(second.getId()).isEqualTo(first.getId());
			assertThat(shopThemes.countByShopId(shop.getId())).isEqualTo(1);
		}

		@Test
		@DisplayName("covers every shop type with something renderable")
		void everyTypeHasADefault() {
			// A shop type with no specific default falls back to GENERIC, and that has to render
			// too — otherwise signing up as an unusual vertical gets a blank storefront.
			for (ShopType type : ShopType.values()) {
				assertThat(themes.renderableConfig(
						fixtures.createVerifiedShop("S" + UUID.randomUUID().toString().substring(0, 6), type)))
						.as("every shop type has a renderable default")
						.isNotBlank();
			}
		}
	}

	// ------------------------------------------------------------------ templates

	@Nested
	@DisplayName("templates")
	class Templates {

		@Test
		@DisplayName("a new template is a draft, absent from the marketplace")
		void draftsAreNotOffered() {
			// Publishing is a separate, explicit act: a template is a lot of JSON to get wrong in
			// public, and an admin needs a chance to check it renders.
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, ShopType.PHARMACY,
					minimalTheme("Hello"), null, actor());

			assertThat(template.isPublished()).isFalse();
			assertThat(themes.marketplaceFor(shop, false))
					.as("a draft is not something a shop can install")
					.isEmpty();
		}

		@Test
		@DisplayName("a published template appears to a shop of its own type")
		void publishedIsOffered() {
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, ShopType.PHARMACY,
					minimalTheme("Hello"), null, actor());
			themes.publishTemplate(template.getId(), actor());

			assertThat(themes.marketplaceFor(shop, false))
					.extracting(ThemeTemplate::getCode)
					.contains("t1");
		}

		@Test
		@DisplayName("a template tagged for another type is not offered")
		void taggedForAnotherType() {
			// D17 payoff: a pharmacy shop is not shown a fashion template, because the shop type
			// is a closed enum and the filtering is exact rather than approximate.
			ThemeTemplate template = themes.createTemplate("fashion-1", "Fashion", null,
					ShopType.FASHION, minimalTheme("Hello"), null, actor());
			themes.publishTemplate(template.getId(), actor());

			assertThat(themes.marketplaceFor(shop, false)).isEmpty();
		}

		@Test
		@DisplayName("an untagged template suits every type")
		void untaggedSuitsEveryone() {
			// A genuinely generic template is common — a bakery and a hardware shop can share one.
			ThemeTemplate template = themes.createTemplate("generic-1", "Generic", null, null,
					minimalTheme("Hello"), null, actor());
			themes.publishTemplate(template.getId(), actor());

			assertThat(themes.marketplaceFor(shop, false)).hasSize(1);
			assertThat(themes.marketplaceFor(
					fixtures.createVerifiedShop("B" + UUID.randomUUID().toString().substring(0, 6),
							ShopType.FURNITURE), false)).hasSize(1);
		}

		@Test
		@DisplayName("a SUBSCRIBED template is not offered to a free shop")
		void paidTierRespected() {
			ThemeTemplate template = themes.createTemplate("paid-1", "Paid", null, null,
					minimalTheme("Hello"), ThemeTemplate.PriceTier.SUBSCRIBED, actor());
			themes.publishTemplate(template.getId(), actor());

			assertThat(themes.marketplaceFor(shop, false))
					.as("offering something the shop cannot have is worse than not offering it")
					.isEmpty();
			assertThat(themes.marketplaceFor(shop, true)).hasSize(1);
		}

		@Test
		@DisplayName("an invalid document is refused before it can be stored")
		void invalidRefused() {
			assertThatThrownBy(() -> themes.createTemplate("bad", "Bad", null, null,
					"{\"sections\":[{\"id\":\"a\",\"type\":\"not-a-section\"}]}", null, actor()))
					.isInstanceOf(ThemeValidationException.class)
					.satisfies(e -> assertThat(((ThemeValidationException) e).issues())
							.as("every problem, named by path")
							.anySatisfy(i -> assertThat(i)
									.asString()
									.contains("sections[0].type")
									.contains("not a known section type")));
		}

		@Test
		@DisplayName("a published template cannot be edited")
		void publishedIsFrozen() {
			// Editing it would only confuse anyone who installs it expecting the previewed version.
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("Hello"), null, actor());
			themes.publishTemplate(template.getId(), actor());

			assertThatThrownBy(() -> themes.updateTemplate(template.getId(), "Renamed", null, null,
					minimalTheme("Changed"), 0, null))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Unpublish");
		}

		@Test
		@DisplayName("unpublishing stops new installs without touching existing ones")
		void unpublishDoesNotBreakInstalls() {
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("Hello"), null, actor());
			themes.publishTemplate(template.getId(), actor());
			ShopTheme installed = themes.installTemplate(shop, template, actor());
			String originalConfig = installed.getConfig();

			themes.unpublishTemplate(template.getId());

			assertThat(themes.marketplaceFor(shop, false)).isEmpty();
			assertThat(themes.getOwnedTheme(shop, installed.getId()).getConfig())
					.as("an installed theme keeps working when its template is withdrawn")
					.isEqualTo(originalConfig);
		}

		@Test
		@DisplayName("codes are unique")
		void codesUnique() {
			themes.createTemplate("t1", "First", null, null, minimalTheme("A"), null, null);

			assertThatThrownBy(() -> themes.createTemplate("t1", "Second", null, null,
					minimalTheme("B"), null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("already exists");
		}
	}

	// ------------------------------------------------------------------ installing

	@Nested
	@DisplayName("installing")
	class Installing {

		@Test
		@DisplayName("copies the config rather than referencing it")
		void copiesConfig() {
			// The whole reason shop_theme.config is a copy: editing a template must not restyle
			// every shop that installed it.
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("Original"), null, actor());
			themes.publishTemplate(template.getId(), actor());
			ShopTheme installed = themes.installTemplate(shop, template, actor());

			// Editing needs the template unpublished — and that is precisely the point: the edit
			// happens long after the shop took its copy, so the copy must be unaffected.
			themes.unpublishTemplate(template.getId());
			themes.updateTemplate(template.getId(), null, null, null, minimalTheme("Changed"), 0, null);
			themes.publishTemplate(template.getId(), actor());

			assertThat(themes.getOwnedTheme(shop, installed.getId()).getConfig())
					.contains("Original")
					.doesNotContain("Changed");
		}

		@Test
		@DisplayName("records provenance, so the editor can say 'based on X'")
		void recordsProvenance() {
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("Hello"), null, actor());

			ShopTheme installed = themes.installTemplate(shop, template, actor());

			assertThat(installed.getTemplateId()).isEqualTo(template.getId());
			assertThat(shopThemes.findByShopIdAndTemplateId(shop.getId(), template.getId()))
					.hasSize(1);
		}

		@Test
		@DisplayName("installs as a draft")
		void installsAsDraft() {
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("Hello"), null, actor());
			themes.publishTemplate(template.getId(), actor());

			assertThat(themes.installTemplate(shop, template, actor()).isPublished())
					.as("installing must not silently put a copy on the storefront")
					.isFalse();
		}
	}

	// ------------------------------------------------------------------ publishing

	@Nested
	@DisplayName("publishing")
	class Publishing {

		@Test
		@DisplayName("a published theme becomes the renderable document")
		void publishedBecomesRenderable() {
			themes.ensureDefaultTheme(shop, actor());
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("Mine"), null, actor());
			themes.publishTemplate(template.getId(), actor());
			ShopTheme theme = themes.installTemplate(shop, template, actor());

			themes.publishTheme(shop, theme.getId(), actor());

			assertThat(themes.renderableConfig(shop))
					.as("what the shop publishes is what the storefront renders")
					.contains("Mine");
			assertThat(themes.hasPublishedTheme(shop)).isTrue();
		}

		@Test
		@DisplayName("a second theme cannot be published while one is live")
		void oneLiveThemeOnly() {
			// The partial unique index would reject it anyway; this produces the message.
			ShopTheme first = themes.ensureDefaultTheme(shop, actor());
			ThemeTemplate template = themes.createTemplate("t1", "Second", null, null,
					minimalTheme("Another"), null, actor());
			ShopTheme second = themes.installTemplate(shop, template, actor());

			themes.publishTheme(shop, first.getId(), actor());

			assertThatThrownBy(() -> themes.publishTheme(shop, second.getId(), actor()))
					.as("a customer must never have two storefronts")
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("already has a published theme");
		}

		@Test
		@DisplayName("unpublishing falls back to the platform default, so the storefront keeps working")
		void unpublishFallsBack() {
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("Mine"), null, actor());
			ShopTheme theme = themes.installTemplate(shop, template, actor());
			themes.publishTheme(shop, theme.getId(), actor());

			themes.unpublishTheme(shop, theme.getId());

			assertThat(themes.hasPublishedTheme(shop)).isFalse();
			assertThat(themes.renderableConfig(shop))
					.as("a blank storefront is the one outcome to avoid")
					.isNotBlank()
					.contains("pharmacy");
		}

		@Test
		@DisplayName("a live theme cannot be edited")
		void liveThemeIsNotEditable() {
			// Saving a half-finished configuration over what a customer is looking at is the one
			// thing this API exists to prevent.
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("A"), null, actor());
			ShopTheme theme = themes.installTemplate(shop, template, actor());
			themes.publishTheme(shop, theme.getId(), actor());

			assertThatThrownBy(() -> themes.saveTheme(shop, theme.getId(), minimalTheme("B"), null))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Unpublish the theme before editing it");
		}

		@Test
		@DisplayName("a published theme cannot be deleted")
		void liveThemeCannotBeDeleted() {
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("A"), null, actor());
			ShopTheme theme = themes.installTemplate(shop, template, actor());
			themes.publishTheme(shop, theme.getId(), actor());

			assertThatThrownBy(() -> themes.deleteTheme(shop, theme.getId()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Unpublish");
		}

		@Test
		@DisplayName("an invalid document is refused on save")
		void saveValidates() {
			themes.ensureDefaultTheme(shop, actor());
			ShopTheme theme = themes.themesOf(shop).get(0);

			assertThatThrownBy(() -> themes.saveTheme(shop, theme.getId(),
					"{\"sections\":[{\"id\":\"a\",\"type\":\"nope\"}]}", null))
					.isInstanceOf(ThemeValidationException.class);
		}
	}

	// ------------------------------------------------------------------ tenancy

	@Nested
	@DisplayName("tenant isolation")
	class Tenancy {

		@Test
		@DisplayName("another shop's theme reads as not found")
		void crossShopIsIndistinguishable() {
			Shop theirs = fixtures.createVerifiedShop(
					"Their Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.PHARMACY);
			ThemeTemplate template = themes.createTemplate("t1", "Test", null, null,
					minimalTheme("A"), null, actor());
			ShopTheme theirTheme = themes.installTemplate(theirs, template, actor());

			// 403 would confirm the id exists, turning this into an existence oracle.
			assertThatThrownBy(() -> themes.getOwnedTheme(shop, theirTheme.getId()))
					.isInstanceOf(ThemeService.ThemeNotFoundException.class);
			assertThatThrownBy(() -> themes.saveTheme(shop, theirTheme.getId(), minimalTheme("X"), null))
					.isInstanceOf(ThemeService.ThemeNotFoundException.class);
			assertThatThrownBy(() -> themes.publishTheme(shop, theirTheme.getId(), actor()))
					.isInstanceOf(ThemeService.ThemeNotFoundException.class);
		}

		@Test
		@DisplayName("another shop cannot delete my asset")
		void crossShopAsset() {
			Shop theirs = fixtures.createVerifiedShop(
					"Their Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.PHARMACY);
			ThemeAsset theirs2 = themes.putAsset(theirs, ThemeAsset.Kind.FOOTER, "footer-main",
					"Theirs", "{}");

			assertThatThrownBy(() -> themes.getOwnedAsset(shop, theirs2.getId()))
					.isInstanceOf(ThemeService.AssetNotFoundException.class);
			assertThatThrownBy(() -> themes.deleteAsset(shop, theirs2.getId()))
					.isInstanceOf(ThemeService.AssetNotFoundException.class);
		}
	}

	// ------------------------------------------------------------------ assets

	@Nested
	@DisplayName("assets")
	class Assets {

		@Test
		@DisplayName("upsert by handle, so re-pointing one needs no section edits")
		void upsertByHandle() {
			// With an id, replacing a logo would mean finding and editing every section that used
			// it — and missing one leaves a broken image on a live storefront.
			ThemeAsset first = themes.putAsset(shop, ThemeAsset.Kind.FOOTER, "footer-main",
					"Footer", "{\"imageUrl\":\"https://x/one.jpg\"}");

			ThemeAsset second = themes.putAsset(shop, ThemeAsset.Kind.FOOTER, "footer-main",
					"Footer", "{\"imageUrl\":\"https://x/two.jpg\"}");

			assertThat(second.getId())
					.as("same handle is the same asset")
					.isEqualTo(first.getId());
			assertThat(assets.findByShopIdAndKindAndHandle(shop.getId(), ThemeAsset.Kind.FOOTER,
					"footer-main")).isPresent();
			assertThat(second.getConfig()).contains("two.jpg");
		}

		@Test
		@DisplayName("the same handle may exist under different kinds")
		void handlesArePerKind() {
			themes.putAsset(shop, ThemeAsset.Kind.FOOTER, "main", "Footer", "{}");
			themes.putAsset(shop, ThemeAsset.Kind.BANNER, "main", "Banner", "{}");

			assertThat(themes.assetsOf(shop, null))
					.as("a footer and a banner can both be called 'main'")
					.hasSize(2);
		}

		@Test
		@DisplayName("refuses a handle that could never be created")
		void refusesBadHandle() {
			assertThatThrownBy(() -> themes.putAsset(shop, ThemeAsset.Kind.FOOTER, "Not Valid",
					"Footer", "{}"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("lowercase");
		}

		@Test
		@DisplayName("a theme referencing a missing asset warns rather than failing")
		void danglingIsAWarning() {
			// A shop can lay out a page before uploading its photograph. Refusing to save would make
			// the editor fight them; the renderer shows a placeholder, which is what it is for.
			ShopTheme theme = themes.ensureDefaultTheme(shop, actor());
			String withDangling = """
					{
					  "schemaVersion": 1,
					  "sections": [
					    { "id": "hero", "type": "hero", "props": { "imageUrl": "asset:HERO/nope" } }
					  ]
					}
					""";

			// It saves. The document is structurally valid; the missing asset is reported
			// separately so the editor can warn without refusing to let the owner work.
			ShopTheme saved = themes.saveTheme(shop, theme.getId(), withDangling, null);

			assertThat(themes.danglingAssetHandles(shop, saved.getConfig()))
					.singleElement()
					.asString()
					.contains("HERO/nope");
		}

		@Test
		@DisplayName("a referenced asset that does exist produces no warning")
		void satisfiedReference() {
			themes.putAsset(shop, ThemeAsset.Kind.HERO, "main", "Hero", "{}");
			ShopTheme theme = themes.ensureDefaultTheme(shop, actor());
			String referencing = """
					{
					  "schemaVersion": 1,
					  "sections": [
					    { "id": "hero", "type": "hero", "props": { "imageUrl": "asset:HERO/main" } }
					  ]
					}
					""";

			ShopTheme saved = themes.saveTheme(shop, theme.getId(), referencing, null);
			assertThat(themes.danglingAssetHandles(shop, saved.getConfig())).isEmpty();
		}
	}
}
