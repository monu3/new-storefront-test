package com.storefront.store.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
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

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.security.KeycloakJwtAuthenticationConverter;
import com.storefront.store.support.TestConfig;

/**
 * The shop HTTP surface, with particular attention to who may reach what.
 *
 * <p>Three boundaries are asserted here, because each is a place where a mistake is silent:
 * <ul>
 *   <li>anonymous callers can read the gallery but not a dashboard,</li>
 *   <li>a shop owner cannot read another shop's dashboard,</li>
 *   <li>only an admin can reach the verification queue.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ShopControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private com.storefront.store.support.TestFixtures fixtures;

	private AppUser owner() {
		return users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"owner-" + UUID.randomUUID(),
				"owner-" + UUID.randomUUID() + "@example.test", "Owner"));
	}

	/**
	 * A token authenticated as a shop owner.
	 *
	 * <p>The {@code authorities(Converter)} overload is used deliberately: it runs the
	 * application's real {@link KeycloakJwtAuthenticationConverter} over the token rather than
	 * asserting authorities by hand. That makes these tests exercise the production role mapping
	 * — if a realm role stopped mapping to {@code ROLE_SHOP}, these would fail, which a
	 * hand-asserted authority list would happily sail past.
	 */
	private RequestPostProcessor asShop() {
		return tokenWithRole("SHOP");
	}

	/** A token authenticated as a platform admin. */
	private RequestPostProcessor asAdmin() {
		return tokenWithRole("ADMIN");
	}

	private RequestPostProcessor tokenWithRole(String role) {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", "sub-" + UUID.randomUUID())
						.claim("email", role.toLowerCase(java.util.Locale.ROOT) + "@example.test")
						.claim("realm_access", Map.of("roles", List.of(role))))
				// Adapt the production converter to the collection the post-processor wants, so
				// the test exercises the real role mapping rather than a hand-written stand-in.
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	@Test
	@DisplayName("the gallery is public")
	void galleryIsPublic() throws Exception {
		mvc.perform(get("/api/v1/shops"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shops").isArray());
	}

	@Test
	@DisplayName("the gallery accepts a shop-type filter")
	void galleryFiltersByType() throws Exception {
		mvc.perform(get("/api/v1/shops").param("type", "PHARMACY"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shops").isArray());
	}

	@Test
	@DisplayName("the gallery caps page size so a public endpoint cannot be asked for everything")
	void galleryCapsPageSize() throws Exception {
		mvc.perform(get("/api/v1/shops").param("limit", "100000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(48));
	}

	@Test
	@DisplayName("an unverified shop is absent from the gallery")
	void unverifiedShopsAreHidden() throws Exception {
		fixtures.createShop("Draft Shop", ShopType.PHARMACY, Shop.Status.DRAFT);

		mvc.perform(get("/api/v1/shops"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shops[?(@.name=='Draft Shop')]").isEmpty());
	}

	@Test
	@DisplayName("a verified shop appears in the gallery")
	void verifiedShopsAppear() throws Exception {
		fixtures.createVerifiedShop("Visible Pharmacy", ShopType.PHARMACY);

		mvc.perform(get("/api/v1/shops"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shops[?(@.name=='Visible Pharmacy')].shopType")
						.value("PHARMACY"));
	}

	@Test
	@DisplayName("an unknown shop slug is a 404, not a 500")
	void unknownSlugIs404() throws Exception {
		mvc.perform(get("/api/v1/shops/no-such-shop"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("the storefront carries the categories the shop's vertical permits")
	void storefrontCarriesCategories() throws Exception {
		Shop shop = fixtures.createVerifiedShop("Category Pharmacy", ShopType.PHARMACY);

		mvc.perform(get("/api/v1/shops/" + shop.getSlug()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shopType").value("PHARMACY"))
				// Derived from the enum, so a pharmacy never offers clothing.
				.andExpect(jsonPath("$.categories[0].code").value("MEDICINE"))
				.andExpect(jsonPath("$.categories[0].slug").value("medicine"))
				.andExpect(jsonPath("$.categories[?(@.code=='KIDS_CLOTHING')]").isEmpty());
	}

	@Test
	@DisplayName("a suspended shop's storefront is a 404, not a 403")
	void suspendedShopIs404() throws Exception {
		Shop shop = fixtures.createVerifiedShop("Suspended Shop", ShopType.GROCERY);
		shop.setStatus(Shop.Status.SUSPENDED);
		shops.saveAndFlush(shop);

		mvc.perform(get("/api/v1/shops/" + shop.getSlug()))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- shop dashboard

	@Test
	@DisplayName("the shop dashboard requires a token")
	void shopDashboardRequiresAuth() throws Exception {
		mvc.perform(get("/api/v1/shop/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("a shop owner's first request auto-provisions a placeholder shop")
	void firstRequestProvisionsShop() throws Exception {
		// IdentitySyncService creates a DRAFT shop the moment a SHOP token is seen, so the
		// dashboard always has something to render. An earlier version of this test expected a
		// 404 here, which was a wrong premise rather than a bug: the whole point of
		// auto-provisioning is that the owner never sees an empty state.
		mvc.perform(get("/api/v1/shop/me").with(asShop()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				// Never public until an admin approves it.
				.andExpect(jsonPath("$.shopType").exists());
	}

	@Test
	@DisplayName("a customer token gets no shop, so the dashboard is empty rather than forbidden")
	void customerCannotReachShopDashboard() throws Exception {
		// A customers-realm token maps to ROLE_CUSTOMER and nothing else (D3), so
		// IdentitySyncService provisions no shop for it and the dashboard finds none.
		//
		// 404 rather than 403 is the correct outcome: the customer legitimately has no shop, and
		// saying "forbidden" would imply they have one they are not allowed to see. The important
		// assertion is that no shop data leaks either way.
		mvc.perform(get("/api/v1/shop/me").with(
						SecurityMockMvcRequestPostProcessors.jwt()
								.jwt(jwt -> jwt
										.claim("iss", CUSTOMER_ISSUER)
										.claim("sub", "cust-" + UUID.randomUUID())
										.claim("email", "c@example.test")
										.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
								.authorities(jwt -> new KeycloakJwtAuthenticationConverter(
										OPS_ISSUER, CUSTOMER_ISSUER).convert(jwt).getAuthorities())))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a customer token is refused outright on a SHOP-only endpoint")
	void customerIsRefusedOnShopOnlyEndpoint() throws Exception {
		// Sign-up carries an explicit hasRole('SHOP'), so a customer is rejected before any
		// lookup happens. This is the real cross-realm guard, as opposed to the previous test
		// which happened to 404 because no shop existed.
		mvc.perform(post("/api/v1/shop/signup")
						.with(customerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Sneaky\",\"shopType\":\"PHARMACY\"}"))
				.andExpect(status().isForbidden());
	}

	/** A customers-realm token, mapped through the production converter. */
	private RequestPostProcessor customerToken() {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", CUSTOMER_ISSUER)
						.claim("sub", "cust-" + UUID.randomUUID())
						.claim("email", "c@example.test")
						.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	@Test
	@DisplayName("signup creates the caller's shop")
	void signupCreatesShop() throws Exception {
		String body = """
				{"name":"Ravi Pharmacy","shopType":"PHARMACY",
				 "description":"Family pharmacy","slugPreference":"ravi-pharmacy"}
				""";

		mvc.perform(post("/api/v1/shop/signup")
						.with(asShop())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.slug").value("ravi-pharmacy"))
				.andExpect(jsonPath("$.shopType").value("PHARMACY"))
				// Never public on creation.
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.paysCommission").value(true));
	}

	@Test
	@DisplayName("signup rejects an unknown shop type")
	void signupRejectsBadType() throws Exception {
		mvc.perform(post("/api/v1/shop/signup")
						.with(asShop())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"X\",\"shopType\":\"BAKERY\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("signup rejects a blank name")
	void signupRejectsBlankName() throws Exception {
		mvc.perform(post("/api/v1/shop/signup")
						.with(asShop())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"   \",\"shopType\":\"PHARMACY\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("submitting for review moves the shop into the queue")
	void submitForVerification() throws Exception {
		Shop shop = fixtures.createShop("Awaiting", ShopType.PHARMACY);

		mvc.perform(post("/api/v1/shop/submit-verification")
						.with(asShop())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"note\":\"Documents attached\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
	}

	// ---------------------------------------------------------------- admin

	@Test
	@DisplayName("the verification queue is admin-only")
	void queueIsAdminOnly() throws Exception {
		mvc.perform(get("/api/v1/admin/shops/verification-queue"))
				.andExpect(status().isUnauthorized());

		mvc.perform(get("/api/v1/admin/shops/verification-queue").with(asShop()))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("an admin can see the queue")
	void adminSeesQueue() throws Exception {
		mvc.perform(get("/api/v1/admin/shops/verification-queue").with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray());
	}

	@Test
	@DisplayName("an admin can approve a shop")
	void adminVerifies() throws Exception {
		Shop shop = fixtures.createShop("Approve Me", ShopType.PHARMACY, Shop.Status.PENDING_REVIEW);

		mvc.perform(post("/api/v1/admin/shops/" + shop.getId() + "/verify").with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("VERIFIED"))
				.andExpect(jsonPath("$.verifiedAt").exists());
	}

	@Test
	@DisplayName("a shop owner cannot approve their own shop")
	void shopCannotSelfApprove() throws Exception {
		Shop shop = fixtures.createShop("Self Approve", ShopType.PHARMACY, Shop.Status.PENDING_REVIEW);

		mvc.perform(post("/api/v1/admin/shops/" + shop.getId() + "/verify").with(asShop()))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("an admin rejection requires a reason")
	void rejectRequiresReason() throws Exception {
		Shop shop = fixtures.createShop("Needs Reason", ShopType.PHARMACY, Shop.Status.PENDING_REVIEW);

		mvc.perform(post("/api/v1/admin/shops/" + shop.getId() + "/reject")
						.with(asAdmin())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"\"}"))
				.andExpect(status().isBadRequest());
	}
}
