package com.storefront.store.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.security.KeycloakJwtAuthenticationConverter;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The product HTTP surface, with particular attention to who may reach what.
 *
 * <p>The property under test throughout: <b>no endpoint accepts a shop id</b>. The shop is
 * resolved from the token (D35), so cross-tenant access is not a check that could be forgotten
 * on a new endpoint — it is a request a caller cannot express. The tenancy tests below assert
 * that from the outside: a second owner's token cannot see or touch the first's products.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ProductControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private ProductRepository products;

	@Autowired
	private ProductService service;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		// A verified grocery, so tests can both create drafts and publish them.
		shop = fixtures.createVerifiedShop("Test Grocery " + UUID.randomUUID().toString().substring(0, 6),
				ShopType.GROCERY);
	}

	// ------------------------------------------------------------------ auth helpers

	/**
	 * A token authenticated as the owner of {@code ownerId}.
	 *
	 * <p>The {@code authorities(Converter)} overload is deliberate: it runs the application's
	 * real {@link KeycloakJwtAuthenticationConverter} over the token, so these tests exercise the
	 * production role mapping. A hand-asserted authority list would sail past a broken mapping.
	 *
	 * <p>The subject is set to the given user's id so {@code CurrentUser} resolves to the row
	 * {@link TenantGuard} then looks up — the tenant boundary is only testable if the token and
	 * the database agree about who the caller is.
	 */
	private RequestPostProcessor asShopOwner() {
		return tokenWithRole("SHOP", subOf(shop.getOwnerUserId()));
	}

	/** A token for a different shop owner entirely: the cross-tenant caller. */
	private RequestPostProcessor asOtherShopOwner() {
		Shop other = fixtures.createVerifiedShop("Other " + UUID.randomUUID().toString().substring(0, 6),
				ShopType.GROCERY);
		return tokenWithRole("SHOP", subOf(other.getOwnerUserId()));
	}

	private RequestPostProcessor asAdmin() {
		return tokenWithRole("ADMIN", subOf(shop.getOwnerUserId()));
	}

	private RequestPostProcessor asCustomer() {
		return asCustomRealmToken();
	}

	/** A CUSTOMER role in the customers realm. Shop endpoints must refuse this. */
	private RequestPostProcessor asCustomRealmToken() {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		AppUser customer = users.saveAndFlush(new AppUser(AppUser.Realm.CUSTOMERS,
				"cust-" + UUID.randomUUID(),
				"cust-" + UUID.randomUUID() + "@example.test", "Customer"));
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", CUSTOMER_ISSUER)
						.claim("sub", customer.getKeycloakSub())
						.claim("email", customer.getEmail())
						.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	/**
	 * The user's Keycloak subject.
	 *
	 * <p>IdentitySyncService resolves a token to a row by {@code keycloak_sub}, not by row id,
	 * so the token has to carry the subject or the request resolves to a newly provisioned
	 * caller who owns a different shop. That silently turns every tenancy test into a test of
	 * the wrong thing.
	 */
	private String subOf(UUID userId) {
		return users.findById(userId).orElseThrow().getKeycloakSub();
	}

	private RequestPostProcessor tokenWithRole(String role, String subject) {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", subject)
						.claim("email", role.toLowerCase(java.util.Locale.ROOT) + "@example.test")
						.claim("realm_access", Map.of("roles", List.of(role))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	private String body(Object value) throws Exception {
		return json.writeValueAsString(value);
	}

	// ------------------------------------------------------------------ authorisation

	@Nested
	@DisplayName("authorisation")
	class Authorisation {

		@Test
		@DisplayName("an anonymous caller cannot list products")
		void anonymousCannotList() throws Exception {
			mvc.perform(get("/api/v1/shop/products"))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("a customer cannot list products")
		void customerCannotList() throws Exception {
			// A CUSTOMER token is authenticated but has no shop, so the shop endpoints must
			// refuse it outright rather than letting it reach the service layer.
			mvc.perform(get("/api/v1/shop/products").with(asCustomer()))
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("an admin cannot use the owner-facing product endpoints")
		void adminIsNotAnOwner() throws Exception {
			// There is no staff role (D3): ADMIN is for the platform's own screens, not for
			// impersonating a shop owner. Sharing these endpoints with ADMIN would quietly give
			// every platform admin write access to every shop's catalogue.
			mvc.perform(get("/api/v1/shop/products").with(asAdmin()))
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("an owner cannot create a product in a shop they do not own")
		void cannotTargetAnotherShop() throws Exception {
			// There is no shopId parameter to try. The endpoint is /shop/products and the shop
			// comes from the token, so a caller cannot even express the cross-tenant request.
			UUID theirProduct = service.createDraft(shop, "Mine", ProductCategory.RICE, null, null, null)
					.getId();

			mvc.perform(get("/api/v1/shop/products/" + theirProduct).with(asOtherShopOwner()))
					.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("another owner cannot see my product in a listing")
		void anotherOwnerCannotSeeItInAListing() throws Exception {
			service.createDraft(shop, "Secret Rice", ProductCategory.RICE, null, null, null);

			mvc.perform(get("/api/v1/shop/products").with(asOtherShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.content[?(@.name=='Secret Rice')]").isEmpty());
		}
	}

	// ------------------------------------------------------------------ CRUD

	@Nested
	@DisplayName("creating and reading")
	class Crud {

		@Test
		@DisplayName("creates a draft and returns it")
		void createsDraft() throws Exception {
			mvc.perform(post("/api/v1/shop/products").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of(
									"name", "Sona Masoori Rice 5kg",
									"category", "RICE",
									"shortDescription", "Long grain rice"))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.name").value("Sona Masoori Rice 5kg"))
					.andExpect(jsonPath("$.status").value("DRAFT"))
					.andExpect(jsonPath("$.category").value("RICE"))
					.andExpect(jsonPath("$.categoryLabel").isNotEmpty())
					.andExpect(jsonPath("$.variants").isEmpty());
		}

		@Test
		@DisplayName("refuses a category the shop's type does not sell")
		void refusesForeignCategory() throws Exception {
			// A grocery selling laptop bags. 400 with a machine-readable code, so the console can
			// show the reason rather than a generic failure.
			mvc.perform(post("/api/v1/shop/products").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("name", "Gaming Bag", "category", "LAPTOP"))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));
		}

		@Test
		@DisplayName("rejects a missing name")
		void rejectsMissingName() throws Exception {
			mvc.perform(post("/api/v1/shop/products").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("category", "RICE"))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.fieldErrors.name").exists());
		}

		@Test
		@DisplayName("never returns the cost price to the public gallery path")
		void costPriceIsOwnerOnly() throws Exception {
			// The console needs it to show margins; nothing else does.
			UUID id = service.createDraft(shop, "Rice", ProductCategory.RICE, null, null, null).getId();
			service.update(shop, products.findById(id).orElseThrow(), null, null, null,
					null, 30_000L, null, null, null, null, null, null);

			mvc.perform(get("/api/v1/shop/products/" + id).with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.costPrice").value(30000));
		}

		@Test
		@DisplayName("offers exactly the categories the write endpoint will accept")
		void categoriesEndpointMatchesTheRule() throws Exception {
			// Served from the same validator, so the dropdown cannot offer something the API
			// would then reject.
			mvc.perform(get("/api/v1/shop/products/categories").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.shopType").value("GROCERY"))
					.andExpect(jsonPath("$.allowed[?(@.code=='RICE')]").isNotEmpty())
					.andExpect(jsonPath("$.allowed[?(@.code=='LAPTOP')]").isEmpty());
		}

		@Test
		@DisplayName("lists with a search term")
		void searches() throws Exception {
			service.createDraft(shop, "Basmati Rice", ProductCategory.RICE, null, null, null);
			service.createDraft(shop, "Lentils", ProductCategory.LENTIL, null, null, null);

			mvc.perform(get("/api/v1/shop/products").param("search", "rice").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalElements").value(1))
					.andExpect(jsonPath("$.content[0].name").value("Basmati Rice"));
		}
	}

	// ------------------------------------------------------------------ variants

	@Nested
	@DisplayName("variants")
	class Variants {

		@Test
		@DisplayName("adds a variant and the product picks up its price")
		void addsVariant() throws Exception {
			UUID id = service.createDraft(shop, "Rice", ProductCategory.RICE, null, null, null).getId();

			mvc.perform(post("/api/v1/shop/products/" + id + "/variants").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("name", "5kg bag", "price", 45000L, "sku", "RICE-5"))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.price").value(45000))
					.andExpect(jsonPath("$.sku").value("RICE-5"))
					.andExpect(jsonPath("$.active").value(true));

			mvc.perform(get("/api/v1/shop/products/" + id).with(asShopOwner()))
					.andExpect(jsonPath("$.price").value(45000))
					.andExpect(jsonPath("$.variants.length()").value(1));
		}

		@Test
		@DisplayName("stores option axes in the order the client sent them")
		void preservesOptionOrder() throws Exception {
			// The label is "Large / Red", not "red / L". A JSON object body preserves key order
			// in Jackson, which is the only reason the shop's chosen order reaches the variant.
			UUID id = service.createDraft(shop, "Kurta", ProductCategory.RICE, null, null, null).getId();

			// A LinkedHashMap, because Map.of has no defined iteration order and the order is
			// exactly what this test is about.
			java.util.Map<String, String> axes = new java.util.LinkedHashMap<>();
			axes.put("size", "L");
			axes.put("colour", "red");

			mvc.perform(post("/api/v1/shop/products/" + id + "/variants").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("name", "Large / Red", "price", 250000L,
									"options", axes))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.options.length()").value(2))
					.andExpect(jsonPath("$.options[0].axis").value("size"))
					.andExpect(jsonPath("$.options[0].value").value("L"))
					.andExpect(jsonPath("$.options[1].axis").value("colour"))
					.andExpect(jsonPath("$.optionsLabel")
							.value("L / red"));
		}

		@Test
		@DisplayName("refuses a duplicate SKU with a readable message")
		void refusesDuplicateSku() throws Exception {
			UUID id = service.createDraft(shop, "Rice", ProductCategory.RICE, null, null, null).getId();

			mvc.perform(post("/api/v1/shop/products/" + id + "/variants").with(asShopOwner())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body(Map.of("name", "5kg", "price", 45000L, "sku", "DUP"))))
					.andExpect(status().isOk());

			mvc.perform(post("/api/v1/shop/products/" + id + "/variants").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("name", "10kg", "price", 80000L, "sku", "DUP"))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.detail")
							.value(org.hamcrest.Matchers.containsString("already used")));
		}

		@Test
		@DisplayName("deactivating keeps the row, which is why it is a POST not a DELETE")
		void deactivationIsNotDeletion() throws Exception {
			UUID id = service.createDraft(shop, "Rice", ProductCategory.RICE, null, null, null).getId();
			UUID variantId = service.addVariant(shop, products.findById(id).orElseThrow(),
					"5kg", 45000L, null, ProductService.VariantOptions.empty(), null, null).getId();

			mvc.perform(post("/api/v1/shop/products/variants/" + variantId + "/deactivate")
							.with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.active").value(false));

			// The variant is still listed, just inactive: order history and the shop's sales
			// report both still point at it.
			mvc.perform(get("/api/v1/shop/products/" + id).with(asShopOwner()))
					.andExpect(jsonPath("$.variants.length()").value(1))
					.andExpect(jsonPath("$.variants[0].active").value(false));
		}
	}

	// ------------------------------------------------------------------ lifecycle

	@Nested
	@DisplayName("lifecycle over HTTP")
	class Lifecycle {

		@Test
		@DisplayName("publishing without a variant is a 409, not a 400")
		void publishWithoutVariantIsConflict() throws Exception {
			// The request is well-formed; it conflicts with current state, and the owner needs
			// to be told what to fix.
			UUID id = service.createDraft(shop, "Rice", ProductCategory.RICE, null, null, null).getId();

			mvc.perform(post("/api/v1/shop/products/" + id + "/publish").with(asShopOwner()))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
		}

		@Test
		@DisplayName("publishes a priced product")
		void publishesPriced() throws Exception {
			UUID id = service.createDraft(shop, "Rice", ProductCategory.RICE, null, null, null).getId();
			service.addVariant(shop, products.findById(id).orElseThrow(), "5kg", 45000L, null,
					ProductService.VariantOptions.empty(), null, null);

			mvc.perform(post("/api/v1/shop/products/" + id + "/publish").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("ACTIVE"));
		}

		@Test
		@DisplayName("deleting a product with sales is refused, and archiving is offered")
		void deleteWithSalesRefused() throws Exception {
			UUID id = service.createDraft(shop, "Rice", ProductCategory.RICE, null, null, null).getId();
			service.recordSale(products.findById(id).orElseThrow(), 1);

			mvc.perform(delete("/api/v1/shop/products/" + id).with(asShopOwner()))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.detail")
							.value(org.hamcrest.Matchers.containsString("Archive")));
		}

		@Test
		@DisplayName("a deleted product returns 404 to its owner until restored")
		void deletedIsNotFoundThenRestorable() throws Exception {
			UUID id = service.createDraft(shop, "Mistake", ProductCategory.RICE, null, null, null).getId();

			mvc.perform(delete("/api/v1/shop/products/" + id).with(asShopOwner()))
					.andExpect(status().isOk());

			mvc.perform(get("/api/v1/shop/products/" + id).with(asShopOwner()))
					.andExpect(status().isNotFound());

			mvc.perform(post("/api/v1/shop/products/" + id + "/restore").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("DRAFT"));
		}
	}
}
