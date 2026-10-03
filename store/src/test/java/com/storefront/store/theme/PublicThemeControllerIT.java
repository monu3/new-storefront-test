package com.storefront.store.theme;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.security.KeycloakJwtAuthenticationConverter;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The theme HTTP surface: the storefront payload, the shop's editor, and the admin marketplace.
 *
 * <p>The boundary that matters most: <b>the storefront endpoint is public and the editor is
 * not</b>, and neither of them can be talked into reading another shop's theme.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class PublicThemeControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private ThemeService themes;

	@Autowired
	private ProductService products;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Theme Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.PHARMACY);
	}

	// ------------------------------------------------------------------ auth helpers

	private RequestPostProcessor asShopOwner() {
		return tokenWithRole("SHOP", subOf(shop.getOwnerUserId()));
	}

	private RequestPostProcessor asOtherShopOwner() {
		Shop other = fixtures.createVerifiedShop(
				"Other " + UUID.randomUUID().toString().substring(0, 6), ShopType.PHARMACY);
		return tokenWithRole("SHOP", subOf(other.getOwnerUserId()));
	}

	private RequestPostProcessor asAdmin() {
		AppUser admin = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"admin-" + UUID.randomUUID(), "admin-" + UUID.randomUUID() + "@example.test", "Admin"));
		return tokenWithRole("ADMIN", admin.getKeycloakSub());
	}

	private RequestPostProcessor asCustomer() {
		return tokenWithRole("CUSTOMER", "cust-" + UUID.randomUUID(),
				"https://auth-master.yenyasoft.com/realms/customers");
	}

	private String subOf(UUID userId) {
		return users.findById(userId).orElseThrow().getKeycloakSub();
	}

	private RequestPostProcessor tokenWithRole(String role, String subject) {
		return tokenWithRole(role, subject, OPS_ISSUER);
	}

	/**
	 * A token in the given realm, with authorities from the real converter.
	 *
	 * <p>Running the production {@link KeycloakJwtAuthenticationConverter} rather than asserting
	 * authorities by hand means a broken realm-role mapping fails these tests too.
	 */
	private RequestPostProcessor tokenWithRole(String role, String subject, String issuer) {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", issuer)
						.claim("sub", subject)
						.claim("email", role.toLowerCase(java.util.Locale.ROOT) + "@example.test")
						.claim("realm_access", Map.of("roles", List.of(role))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	private String body(Object value) throws Exception {
		return json.writeValueAsString(value);
	}

	// ------------------------------------------------------------------ storefront

	@Nested
	@DisplayName("the storefront payload")
	class Storefront {

		@Test
		@DisplayName("is public, because a storefront must sell to anyone")
		void isPublic() throws Exception {
			mvc.perform(get("/api/v1/p/shops/" + shop.getSlug() + "/storefront"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.shopSlug").value(shop.getSlug()))
					.andExpect(jsonPath("$.themeConfig").isNotEmpty())
					.andExpect(jsonPath("$.sectionCount").isNumber());
		}

		@Test
		@DisplayName("includes the products the commerce sections render from")
		void includesSectionData() throws Exception {
			// One request per page view: the alternative is a storefront issuing six calls before
			// the first product appears.
			ProductService productsService = products;
			var product = fixtures.liveProduct(shop, "Panadol 500mg",
					ProductCategory.MEDICINE, 40_000L, 10);
			productsService.recordSale(product, 3);

			mvc.perform(get("/api/v1/p/shops/" + shop.getSlug() + "/storefront"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.latestProducts.length()").value(1))
					.andExpect(jsonPath("$.latestProducts[0].name").value("Panadol 500mg"))
					.andExpect(jsonPath("$.categories.length()").value(1))
					;
		}

		@Test
		@DisplayName("lists only the categories the shop has products in")
		void categoriesAreRealNotPermitted() throws Exception {
			// A pharmacy with one product should show one category on its storefront, not the five
			// its vertical permits. A grid of mostly-empty tiles is worse than a smaller honest one.
			fixtures.liveProduct(shop, "Panadol", ProductCategory.MEDICINE, 40_000L, 10);

			mvc.perform(get("/api/v1/p/shops/" + shop.getSlug() + "/storefront"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.categories.length()").value(1))
					.andExpect(jsonPath("$.categories[0].code").value("MEDICINE"))
					.andExpect(jsonPath("$.categories[0].productCount").value(1));
		}

		@Test
		@DisplayName("an unverified shop is 404, indistinguishable from one that never existed")
		void hiddenShopIsNotFound() throws Exception {
			Shop draft = fixtures.createShop("Hidden " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.PHARMACY);

			// 403 would confirm the shop exists, turning this endpoint into an existence oracle
			// for every suspended shop on the platform.
			mvc.perform(get("/api/v1/p/shops/" + draft.getSlug() + "/storefront"))
					.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("serves the platform default when the shop has published nothing")
		void servesDefault() throws Exception {
			mvc.perform(get("/api/v1/p/shops/" + shop.getSlug() + "/storefront"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.fromPublishedTheme").value(false))
										.andExpect(jsonPath("$.themeConfig")
							.value(org.hamcrest.Matchers.containsString("pharmacy")));
		}

		@Test
		@DisplayName("serves the shop's own theme once it publishes one")
		void servesPublished() throws Exception {
			ThemeTemplate template = themes.createTemplate("t1", "Custom", null, null,
					"{\"schemaVersion\":1,\"sections\":[{\"id\":\"only\",\"type\":\"hero\","
							+ "\"props\":{\"heading\":\"OUR THEME\"}}]}",
					null, shop.getOwnerUserId());
			ShopTheme theme = themes.installTemplate(shop, template, shop.getOwnerUserId());
			themes.publishTheme(shop, theme.getId(), shop.getOwnerUserId());

			mvc.perform(get("/api/v1/p/shops/" + shop.getSlug() + "/storefront"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.fromPublishedTheme").value(true))
										.andExpect(jsonPath("$.themeConfig")
							.value(org.hamcrest.Matchers.containsString("OUR THEME")));
		}
	}

	// ------------------------------------------------------------------ shop editor

	@Nested
	@DisplayName("the shop editor")
	class Editor {

		@Test
		@DisplayName("needs a SHOP token")
		void needsShopRole() throws Exception {
			mvc.perform(get("/api/v1/shop/theme")).andExpect(status().isUnauthorized());
			mvc.perform(get("/api/v1/shop/theme").with(asCustomer()))
					.andExpect(status().isForbidden());
			mvc.perform(get("/api/v1/shop/theme").with(asAdmin()))
					// ADMIN is for the platform's screens, not for editing a shop's storefront
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("lists the shop's themes, creating the default on first visit")
		void listsThemes() throws Exception {
			mvc.perform(get("/api/v1/shop/theme").with(asShopOwner()))
					.andExpect(status().isOk())
										.andExpect(jsonPath("$.themes.length()").value(org.hamcrest.Matchers.greaterThan(0)))
					.andExpect(jsonPath("$.hasPublishedTheme").value(false));
		}

		@Test
		@DisplayName("shows only templates this shop's type can install")
		void marketplaceFilteredByType() throws Exception {
			// D17 payoff: the shop type is a closed enum, so the filtering is exact.
			ThemeTemplate fashion = themes.createTemplate("f1", "Fashion", null, ShopType.FASHION,
					"{\"schemaVersion\":1,\"sections\":[]}", null, shop.getOwnerUserId());
			ThemeTemplate generic = themes.createTemplate("g1", "Generic", null, null,
					"{\"schemaVersion\":1,\"sections\":[]}", null, shop.getOwnerUserId());
			themes.publishTemplate(fashion.getId(), shop.getOwnerUserId());
			themes.publishTemplate(generic.getId(), shop.getOwnerUserId());

			mvc.perform(get("/api/v1/shop/theme/marketplace").with(asShopOwner()))
					.andExpect(status().isOk())
										.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].code").value("g1"));
		}

		@Test
		@DisplayName("refuses to edit a live theme")
		void refusesToEditLive() throws Exception {
			ThemeTemplate template = themes.createTemplate("t1", "T", null, null,
					"{\"schemaVersion\":1,\"sections\":[]}", null, shop.getOwnerUserId());
			ShopTheme theme = themes.installTemplate(shop, template, shop.getOwnerUserId());
			themes.publishTheme(shop, theme.getId(), shop.getOwnerUserId());

			mvc.perform(patch("/api/v1/shop/theme/" + theme.getId()).with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("config",
									"{\"schemaVersion\":1,\"sections\":[{\"id\":\"x\",\"type\":\"spacer\"}]}"))))
					.andExpect(status().isConflict())
										.andExpect(jsonPath("$.detail")
							.value(org.hamcrest.Matchers.containsString("Unpublish")));
		}

		@Test
		@DisplayName("reports every theme problem at once, each named by path")
		void reportsEveryProblem() throws Exception {
			ShopTheme theme = themes.ensureDefaultTheme(shop, shop.getOwnerUserId());

			mvc.perform(patch("/api/v1/shop/theme/" + theme.getId()).with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("config",
									"{\"schemaVersion\":1,\"tokens\":{\"primary\":\"rebeccapurple\"},"
											+ "\"sections\":[{\"id\":\"a\",\"type\":\"nope\"}]}"))))
					.andExpect(status().isBadRequest())
										// A shop owner fixing a theme needs to see all of it at once; stopping at the
					// first failure turns five corrections into five round trips.
					.andExpect(jsonPath("$.code").value("INVALID_THEME"))
					.andExpect(jsonPath("$.issues.length()")
							.value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
		}

		@Test
		@DisplayName("another shop cannot read my theme")
		void crossShopIsNotFound() throws Exception {
			ShopTheme mine = themes.ensureDefaultTheme(shop, shop.getOwnerUserId());

			mvc.perform(get("/api/v1/shop/theme/" + mine.getId()).with(asOtherShopOwner()))
					// 404 not 403: a 403 would confirm the id exists, making this an existence oracle.
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("THEME_NOT_FOUND"));
		}

		@Test
		@DisplayName("preview returns exactly what the storefront renders")
		void previewMatchesStorefront() throws Exception {
			// The exit criterion stated as an endpoint: what the owner previews is what a customer
			// gets. If these ever diverge, the preview is a lie.
			ThemeTemplate template = themes.createTemplate("t1", "T", null, null,
					"{\"schemaVersion\":1,\"sections\":[{\"id\":\"h\",\"type\":\"hero\","
							+ "\"props\":{\"heading\":\"SAME\"}}]}",
					null, shop.getOwnerUserId());
			ShopTheme theme = themes.installTemplate(shop, template, shop.getOwnerUserId());
			themes.publishTheme(shop, theme.getId(), shop.getOwnerUserId());

			mvc.perform(get("/api/v1/shop/theme/preview").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.fromPublishedTheme").value(true));
		}

		@Test
		@DisplayName("upserts an asset by handle, so re-pointing one needs no section edits")
		void upsertsAsset() throws Exception {
			// With an id, replacing a logo would mean editing every section that used it, and
			// missing one leaves a broken image on a live storefront.
			String first = mvc.perform(put("/api/v1/shop/theme/assets").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("kind", "FOOTER", "handle", "footer-main",
									"name", "Footer", "config", "{}"))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.handle").value("footer-main"))
					.andReturn().getResponse().getContentAsString();

			String second = mvc.perform(put("/api/v1/shop/theme/assets").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("kind", "FOOTER", "handle", "footer-main",
									"name", "Footer v2", "config", "{}"))))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();

			// Same handle, so the same asset: one row, updated rather than duplicated.
			mvc.perform(get("/api/v1/shop/theme/assets").with(asShopOwner()))
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].name").value("Footer v2"));

			org.assertj.core.api.Assertions.assertThat(json.readTree(second).path("id").asText())
					.as("upsert by handle, so the row is the same one")
					.isEqualTo(json.readTree(first).path("id").asText());
		}

		@Test
		@DisplayName("refuses a handle that could never be created")
		void refusesBadHandle() throws Exception {
			mvc.perform(put("/api/v1/shop/theme/assets").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("kind", "FOOTER", "handle", "Not Valid",
									"name", "x", "config", "{}"))))
					.andExpect(status().isBadRequest());
		}

	}

	// ------------------------------------------------------------------ admin

	@Nested
	@DisplayName("the admin marketplace")
	class Admin {

		@Test
		@DisplayName("needs an ADMIN token")
		void needsAdmin() throws Exception {
			mvc.perform(get("/api/v1/admin/theme-templates"))
					.andExpect(status().isUnauthorized());
			mvc.perform(get("/api/v1/admin/theme-templates").with(asShopOwner()))
					// a shop owner must not be able to publish a template for everyone
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("creates a draft that is not in the marketplace")
		void createsDraft() throws Exception {
			mvc.perform(post("/api/v1/admin/theme-templates").with(asAdmin())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of(
									"code", "pharmacy-clean",
									"name", "Pharmacy Clean",
									"shopType", "PHARMACY",
									"config", "{\"schemaVersion\":1,\"sections\":[]}"))))
					.andExpect(status().isCreated())
										.andExpect(jsonPath("$.template.published").value(false))
					.andExpect(jsonPath("$.template.code").value("pharmacy-clean"));
		}

		@Test
		@DisplayName("rejects an invalid document with every problem listed")
		void rejectsInvalid() throws Exception {
			mvc.perform(post("/api/v1/admin/theme-templates").with(asAdmin())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of(
									"code", "bad",
									"name", "Bad",
									"config", "{\"sections\":[{\"id\":\"a\",\"type\":\"nope\"}]}"))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_THEME"));
		}

		@Test
		@DisplayName("publishes, and the template then appears in the marketplace")
		void publishes() throws Exception {
			var created = json.readTree(mvc.perform(post("/api/v1/admin/theme-templates")
					.with(asAdmin())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body(Map.of("code", "t1", "name", "T",
							"config", "{\"schemaVersion\":1,\"sections\":[]}"))))
					.andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString());
			String id = created.path("template").path("id").asText();

			mvc.perform(post("/api/v1/admin/theme-templates/" + id + "/publish").with(asAdmin()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.published").value(true));

			mvc.perform(get("/api/v1/shop/theme/marketplace").with(asShopOwner()))
					.andExpect(jsonPath("$.length()").value(1));
		}

		@Test
		@DisplayName("refuses to edit a published template")
		void refusesToEditPublished() throws Exception {
			var created = json.readTree(mvc.perform(post("/api/v1/admin/theme-templates")
					.with(asAdmin())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body(Map.of("code", "t1", "name", "T",
							"config", "{\"schemaVersion\":1,\"sections\":[]}"))))
					.andReturn().getResponse().getContentAsString());
			String id = created.path("template").path("id").asText();
			mvc.perform(post("/api/v1/admin/theme-templates/" + id + "/publish").with(asAdmin()));

			mvc.perform(patch("/api/v1/admin/theme-templates/" + id).with(asAdmin())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("name", "Renamed"))))
					.andExpect(status().isConflict())
										.andExpect(jsonPath("$.detail")
							.value(org.hamcrest.Matchers.containsString("Unpublish")));
		}
	}
}
