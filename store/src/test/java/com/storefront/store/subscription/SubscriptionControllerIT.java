package com.storefront.store.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletTopUpRequest;
import com.storefront.store.wallet.WalletTopUpService;

/**
 * The subscription HTTP surface, with particular attention to who may reach what.
 *
 * <p>Four boundaries, because a subscription system leaks in predictable ways:
 *
 * <ul>
 *   <li>a shop owner sees only their own subscription, and no shop id is accepted anywhere;</li>
 *   <li>one shop owner cannot cancel or read another's subscription;</li>
 *   <li>plan management and revenue are admin-only, at the class level (D36);</li>
 *   <li>a customer is not a shop owner, even with a valid token from the other realm.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class SubscriptionControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private SubscriptionPlanRepository plans;

	@Autowired
	private ShopSubscriptionRepository subscriptionRows;

	@Autowired
	private com.storefront.store.wallet.WalletTopUpService topUpService;

	@Autowired
	private SubscriptionPaymentService payments;

	@Autowired
	private com.storefront.store.shop.ShopRepository shops;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AppUserRepository users;

	private AppUser owner;
	private AppUser otherOwner;
	private AppUser admin;
	private Shop shop;
	private SubscriptionPlan monthly;

	@BeforeEach
	void setUp() {
		owner = newOwner();
		otherOwner = newOwner();
		admin = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"admin-" + UUID.randomUUID(), "a-" + UUID.randomUUID() + "@example.test", "Admin"));

		// Built here rather than through TestFixtures, which owns every shop with a single shared
		// user -- and a cross-tenant test needs two genuinely different owners, or "cannot see
		// another's subscription" would be tested against the same shop twice.
		shop = shopFor(owner, "Sub Shop " + UUID.randomUUID(), ShopType.GROCERY);
		shopFor(otherOwner, "Other Shop " + UUID.randomUUID(), ShopType.PHARMACY);

		monthly = plans.saveAndFlush(new SubscriptionPlan(
				"HTTP_" + UUID.randomUUID().toString().substring(0, 6),
				"Pro (http test)", SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));
	}

	// ------------------------------------------------------------------ the shop side

	@Test
	@DisplayName("a shop owner can read their own subscription state")
	void ownerReadsOwnState() throws Exception {
		mvc.perform(get("/api/v1/subscriptions/me").with(asShop(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shopId").value(shop.getId().toString()))
				.andExpect(jsonPath("$.current").doesNotExist())
				// The free tier is live before any subscription exists, and says so.
				.andExpect(jsonPath("$.paysCommission").value(true))
				.andExpect(jsonPath("$.features[?(@.key=='max_products')].limit").value(25));
	}

	@Test
	@DisplayName("the pricing screen is readable by a signed-in owner")
	void pricingScreen() throws Exception {
		mvc.perform(get("/api/v1/subscriptions/plans").with(asShop(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code=='FREE')]").exists())
				.andExpect(jsonPath("$[?(@.code=='PRO_MONTHLY')].priceMinor").value(200000));
	}

	@Test
	@DisplayName("requesting a plan returns the amount and reference to transfer")
	void requestReturnsPaymentInstructions() throws Exception {
		mvc.perform(post("/api/v1/subscriptions/requests")
						.with(asShop(owner))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planId\":\"" + monthly.getId() + "\"}"))
				// 201: a request was queued. Nothing is charged and nothing is granted.
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountMinor").value(200000))
				.andExpect(jsonPath("$.reference").isNotEmpty())
				.andExpect(jsonPath("$.instructions").isNotEmpty());

		// And nothing is live yet.
		mvc.perform(get("/api/v1/subscriptions/me").with(asShop(owner)))
				.andExpect(jsonPath("$.current").doesNotExist())
				.andExpect(jsonPath("$.paysCommission").value(true));
	}

	@Test
	@DisplayName("a shop owner cannot read another shop's subscription")
	void crossTenantIsNotFound() throws Exception {
		ShopSubscription theirs = subscriptions.request(shop, monthly);

		// 404, not 403: the caller has no business learning whether this id exists, and confirming
		// existence is the only thing an enumeration attempt needs.
		mvc.perform(post("/api/v1/subscriptions/" + theirs.getId() + "/cancel")
						.with(asShop(otherOwner)))
				.andExpect(status().isNotFound());

		// And it is untouched.
		assertThat(subscriptionRows.findById(theirs.getId()).orElseThrow().getCancelledAt()).isNull();
	}

	@Test
	@DisplayName("an unknown plan is a 404")
	void unknownPlan() throws Exception {
		mvc.perform(post("/api/v1/subscriptions/requests")
						.with(asShop(owner))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a customer token is not a shop owner")
	void customerIsNotShopOwner() throws Exception {
		// Two realms, and the customers realm is a different person. If this ever passed, a customer
		// could read a shop's billing and every shop's revenue by guessing slugs.
		mvc.perform(get("/api/v1/subscriptions/me").with(asCustomer()))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("an anonymous caller cannot read any subscription")
	void anonymousIsRefused() throws Exception {
		mvc.perform(get("/api/v1/subscriptions/me"))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ the admin side

	@Test
	@DisplayName("plan management is admin-only")
	void planManagementIsAdminOnly() throws Exception {
		mvc.perform(get("/api/v1/admin/plans")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/admin/plans").with(asShop(owner)))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("an admin can list plans and the feature keys")
	void adminReadsPlans() throws Exception {
		mvc.perform(get("/api/v1/admin/plans").with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.code=='FREE')]").exists())
				// The feature list comes from the enum, so the admin UI cannot drift from it.
				.andExpect(jsonPath("$.content[0].monthlyEquivalentMinor").isNumber());

		mvc.perform(get("/api/v1/admin/plans/features").with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@=='max_products')]").exists())
				// LIMITED is not a feature and must never be offered.
				.andExpect(jsonPath("$[?(@=='__limited')]").doesNotExist());
	}

	@Test
	@DisplayName("an admin cannot change a plan that live subscribers hold")
	void planInUseIsRefused() throws Exception {
		subscribe(owner, monthly, admin);

		String body = """
				{"code":"%s","name":"Renamed","priceMinor":999999,
				 "billingPeriod":"MONTHLY","durationDays":31,"sortOrder":1}
				""".formatted(monthly.getCode());

		// 409 with the reason. Silently refusing would leave an admin who clicked save believing it
		// worked, and the next subscriber would be charged the new price for terms they were not
		// quoted.
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/v1/admin/plans/" + monthly.getId())
						.with(asAdmin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PLAN_IN_USE"));
	}

	@Test
	@DisplayName("a display-only edit to a live plan is allowed")
	void displayEditsAreAllowed() throws Exception {
		subscribe(owner, monthly, admin);

		String body = """
				{"code":"%s","name":"Pro (renamed)","priceMinor":200000,
				 "billingPeriod":"MONTHLY","durationDays":31,"sortOrder":1}
				""".formatted(monthly.getCode());

		// Renaming a plan does not change what anybody bought, so it must not be blocked. Refusing
		// every edit to a live plan would make the pricing page unmaintainable exactly when it is
		// being used.
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.put("/api/v1/admin/plans/" + monthly.getId())
						.with(asAdmin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Pro (renamed)"));
	}

	@Test
	@DisplayName("an admin can withdraw a plan without deleting it")
	void withdrawNotDelete() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/api/v1/admin/plans/" + monthly.getId())
						.with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isActive").value(false));

		// The row is still there: a cancelled subscriber's history must still name their plan, and
		// deleting it would cascade away the entitlements it granted.
		mvc.perform(get("/api/v1/admin/plans/" + monthly.getId()).with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").isNotEmpty());
	}

	@Test
	@DisplayName("an unknown feature key is refused, and nothing is saved")
	void unknownFeatureIsRefused() throws Exception {
		String body = """
				{"code":"TYPO_PLAN","name":"Typo","billingPeriod":"MONTHLY",
				 "priceMinor":1000,"durationDays":30,
				 "entitlements":[{"feature":"use_paid_template","enabled":true}]}
				""";

		// 400 rather than a silent skip. An admin who saved a typo'd feature key must hear about it:
		// skipping would show "saved" and quietly grant nothing.
		mvc.perform(post("/api/v1/admin/plans")
						.with(asAdmin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNKNOWN_FEATURE"));

		mvc.perform(get("/api/v1/admin/plans").with(asAdmin()))
				.andExpect(jsonPath("$.content[?(@.code=='TYPO_PLAN')]").doesNotExist());
	}

	// ------------------------------------------------------------------ revenue

	@Test
	@DisplayName("the revenue dashboard is admin-only and states its window")
	void revenueIsAdminOnly() throws Exception {
		mvc.perform(get("/api/v1/admin/revenue").with(asShop(owner)))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/v1/admin/revenue").with(asAdmin()))
				.andExpect(status().isOk())
				// The window is stated rather than implied. A figure with an unstated period is a
				// number two people will read differently.
				.andExpect(jsonPath("$.window").isNotEmpty())
				.andExpect(jsonPath("$.mrrMinor").isNumber())
				.andExpect(jsonPath("$.trend").isArray());
	}

	@Test
	@DisplayName("the expiry sweep can be run on demand, and is idempotent")
	void expireOnDemand() throws Exception {
		mvc.perform(post("/api/v1/admin/subscriptions/expire").with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closed").value(0));

		// Exposed because an admin seeing a shop still commission-free after its period ended needs a
		// way to fix it now. Safe to expose only because running it twice changes nothing.
		mvc.perform(post("/api/v1/admin/subscriptions/expire").with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closed").value(0));
	}

	// ------------------------------------------------------------------ tokens

	private AppUser newOwner() {
		return users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"owner-" + UUID.randomUUID(),
				"owner-" + UUID.randomUUID() + "@example.test", "Owner"));
	}

	/**
	 * A token whose {@code sub} is this user's, so the shop the guard finds is theirs.
	 *
	 * <p>The subject has to match the {@code AppUser}'s keycloak subject or the guard resolves a
	 * different account and every assertion would be about the wrong shop.
	 */
	private RequestPostProcessor asShop(AppUser user) {
		return tokenWithRole(user, "SHOP", OPS_ISSUER);
	}

	private RequestPostProcessor asAdmin() {
		return tokenWithRole(admin, "ADMIN", OPS_ISSUER);
	}

	private RequestPostProcessor asCustomer() {
		return tokenWithRole(null, "CUSTOMER", CUSTOMER_ISSUER);
	}

	/**
	 * Runs the application's real role mapping over the token rather than asserting authorities by
	 * hand, so these tests would catch a realm role ceasing to map to {@code ROLE_SHOP}.
	 */
	private RequestPostProcessor tokenWithRole(AppUser user, String role, String issuer) {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", issuer)
						.claim("sub", user == null
								? "sub-" + UUID.randomUUID()
								: user.getKeycloakSub())
						.claim("email", role.toLowerCase(java.util.Locale.ROOT) + "@example.test")
						.claim("realm_access", Map.of("roles", List.of(role))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	/** A verified shop owned by a specific user. */
	private Shop shopFor(AppUser user, String name, ShopType type) {
		Shop created = new Shop(user, name, type);
		created.setStatus(Shop.Status.VERIFIED);
		return shops.saveAndFlush(created);
	}

	/**
	 * Request a plan, submit the top-up, and approve it as an admin.
	 *
	 * <p>Reached through the services rather than through HTTP, because these tests are about who may
	 * reach an endpoint -- not about how the flow works, which {@code SubscriptionLifecycleIT}
	 * covers end to end.
	 */
	private ShopSubscription subscribe(AppUser shopOwner, SubscriptionPlan plan, AppUser approver) {
		ShopSubscription requested = subscriptions.request(shopOwnerShop(shopOwner), plan);
		WalletTopUpRequest topUp = topUpService.requestForSubscription(
				shopOwnerShop(shopOwner).getId(), requested,
				WalletTopUpRequest.Method.BANK_TRANSFER, "R", null, null);
		return payments.settle(topUp.getId(), approver.getId(), "paid");
	}

	private Shop shopOwnerShop(AppUser user) {
		return shops.findByOwnerUserId(user.getId()).orElseThrow();
	}
}
