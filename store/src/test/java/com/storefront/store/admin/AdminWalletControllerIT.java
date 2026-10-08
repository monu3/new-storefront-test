package com.storefront.store.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.storefront.store.subscription.SubscriptionPlan;
import com.storefront.store.subscription.SubscriptionPlanRepository;
import com.storefront.store.subscription.SubscriptionPaymentService;
import com.storefront.store.subscription.SubscriptionService;
import com.storefront.store.wallet.WalletTopUpService;

/**
 * The admin top-up queue, with attention to the field the Phase 6 UI depends on (Phase 6).
 *
 * <p>One property matters more than the rest: the queue has to say whether a request is buying a
 * subscription, because the two approvals do different things. Approving a subscription through the
 * plain endpoint credits the wallet and never starts the plan — the shop's bank statement matches,
 * its balance goes up, and it has no subscription. From both sides that looks like a success, which
 * is what makes it dangerous.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class AdminWalletControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private WalletTopUpService topUps;

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private SubscriptionPaymentService payments;

	@Autowired
	private SubscriptionPlanRepository plans;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	private AppUser admin;
	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Wallet " + UUID.randomUUID(), ShopType.GROCERY);
		admin = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"admin-" + UUID.randomUUID(), "a-" + UUID.randomUUID() + "@example.test", "Admin"));
	}

	@Test
	@DisplayName("a plain top-up is flagged as not a subscription")
	void plainTopUp() throws Exception {
		topUps.request(shop.getId(), 250_000,
				com.storefront.store.wallet.WalletTopUpRequest.Method.BANK_TRANSFER,
				"BANK-REF-1", null, "just topping up");

		mvc.perform(get("/api/v1/admin/wallet/topups?status=PENDING").with(asAdmin()))
				.andExpect(status().isOk())
				// Null rather than false: "this is not a subscription" and "we have no idea" are
				// different, and a client defaulting this to false would mis-route a request whose
				// data it failed to read.
				.andExpect(jsonPath("$.content[0].isSubscription").doesNotExist())
				.andExpect(jsonPath("$.content[0].planCode").doesNotExist())
				// The reference is the column an admin reconciles against, so it is on the row.
				.andExpect(jsonPath("$.content[0].reference").value("BANK-REF-1"));
	}

	@Test
	@DisplayName("a subscription purchase is flagged, with the plan and whether the price matches")
	void subscriptionTopUpIsFlagged() throws Exception {
		SubscriptionPlan plan = plans.saveAndFlush(new SubscriptionPlan(
				"Q_T" + UUID.randomUUID().toString().substring(0, 6),
				"Queued", SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

		var requested = subscriptions.request(shop, plan);
		topUps.requestForSubscription(shop.getId(), requested,
				com.storefront.store.wallet.WalletTopUpRequest.Method.ESAWA,
				"PRO-REF-2", null, "Pro subscription");

		mvc.perform(get("/api/v1/admin/wallet/topups?status=PENDING").with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].isSubscription").value(true))
				.andExpect(jsonPath("$.content[0].planCode").value(plan.getCode()))
				// Asserted explicitly because a mismatch is the failure this whole field exists to
				// prevent: approving it would charge a price the shop did not agree to.
				.andExpect(jsonPath("$.content[0].isPlanPrice").value(true))
				.andExpect(jsonPath("$.content[0].amountDisplay").exists());
	}

	@Test
	@DisplayName("approving a subscription through the subscription endpoint activates it")
	void approvingActivates() throws Exception {
		SubscriptionPlan plan = plans.saveAndFlush(new SubscriptionPlan(
				"ACT_T" + UUID.randomUUID().toString().substring(0, 6),
				"Activated", SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

		var requested = subscriptions.request(shop, plan);
		var topUp = topUps.requestForSubscription(shop.getId(), requested,
				com.storefront.store.wallet.WalletTopUpRequest.Method.BANK_TRANSFER,
				"ACT-REF", null, null);

		assertThat(requested.getStatus().name()).isEqualTo("PENDING_PAYMENT");

		payments.settle(topUp.getId(), admin.getId(), "matched the statement");

		// The whole point of routing subscription rows to the subscription endpoint.
		assertThat(requested.getStatus().name()).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("the queue is admin-only")
	void adminOnly() throws Exception {
		mvc.perform(get("/api/v1/admin/wallet/topups"))
				.andExpect(status().isUnauthorized());
	}

	private RequestPostProcessor asAdmin() {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", admin.getKeycloakSub())
						.claim("email", "admin@example.test")
						.claim("realm_access", Map.of("roles", List.of("ADMIN"))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}
}
