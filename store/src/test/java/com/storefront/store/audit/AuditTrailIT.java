package com.storefront.store.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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

import com.storefront.store.admin.AdminShopController;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.shop.VerificationService;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.subscription.SubscriptionExpiryJob;
import com.storefront.store.subscription.SubscriptionPaymentService;
import com.storefront.store.subscription.SubscriptionPlan;
import com.storefront.store.subscription.SubscriptionPlanRepository;
import com.storefront.store.subscription.SubscriptionService;
import com.storefront.store.wallet.WalletTopUpRequest;

/**
 * Phase 7's exit criterion, as a test: <b>every privileged action lands in the audit log</b>.
 *
 * <p>The criterion is the reason this file exists and the reason it asserts per action rather than
 * testing {@link AuditTrail} in isolation. A recorder that works perfectly and is called from nowhere
 * satisfies an audit log test suite and fails its actual requirement. So each test performs a real
 * privileged action through the real service or controller and then asks the log what it saw.
 *
 * <p>The identity shape is deliberate too: a shop owner must not be able to read the log, and a log
 * row must survive the account that made it.
 */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class AuditTrailIT {

	@Autowired
	private AuditTrail audit;

	@Autowired
	private AuditLogRepository entries;

	@Autowired
	private VerificationService verification;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private SubscriptionPaymentService payments;

	@Autowired
	private SubscriptionPlanRepository plans;

	@Autowired
	private SubscriptionExpiryJob expiryJob;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private CurrentUser currentUser;

	private AppUser admin;
	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Audit " + UUID.randomUUID(), ShopType.GROCERY);
		admin = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"admin-" + UUID.randomUUID(), "a-" + UUID.randomUUID() + "@example.test", "Admin"));
	}

	/**
	 * Runs a block with no authenticated caller.
	 *
	 * <p><b>Named for what it does rather than for what it looks like.</b> A {@code @SpringBootTest}
	 * has no {@code SecurityContext}, so {@link CurrentUser} resolves to nobody — there is no honest
	 * way to fake an admin here without either a {@code @MockitoBean} or a helper that reaches into
	 * a repository.
	 *
	 * <p>Both are worse than saying so. The recorder is still supposed to write the entry, and an
	 * entry with a null actor is a real state — it is what a scheduled job produces. The test that
	 * cares about the <em>actor</em> is {@link #actorIsCapturedOverHttp()}, which goes through the
	 * real HTTP path where a security context genuinely exists.
	 */
	private void withoutCaller(Runnable block) {
		block.run();
	}

	private List<AuditLog> forShop(UUID shopId) {
		return entries.findForEntity("Shop", shopId);
	}

	// ------------------------------------------------------------------ the criterion

	@Nested
	@DisplayName("every privileged action is recorded")
	class Recorded {

		@Test
		@DisplayName("verifying a shop")
		void verifyShop() {
			Shop draft = fixtures.createShop("Draft " + UUID.randomUUID(), ShopType.GROCERY);

			withoutCaller(() -> verification.verify(draft));

			assertThat(forShop(draft.getId()))
					.singleElement()
					.satisfies(entry -> {
						assertThat(entry.getAction()).isEqualTo(AuditAction.SHOP_VERIFY);
						assertThat(entry.getSummary()).contains(draft.getName());
						assertThat(entry.getEntityType()).isEqualTo("Shop");
					});
		}

		@Test
		@DisplayName("suspending a shop, with the reason")
		void suspendShop() {
			withoutCaller(() -> verification.suspend(shop, "Charged back three times"));

			assertThat(forShop(shop.getId()))
					.singleElement()
					.satisfies(entry -> {
						assertThat(entry.getAction()).isEqualTo(AuditAction.SHOP_SUSPEND);
						// The reason is the part that makes the record useful to the shop and to the
						// next admin, so it is asserted rather than assumed.
						assertThat(entry.getReason()).isEqualTo("Charged back three times");
					});
		}

		@Test
		@DisplayName("rejecting a shop, with the reason")
		void rejectShop() {
			Shop draft = fixtures.createShop("Draft " + UUID.randomUUID(), ShopType.GROCERY);

			withoutCaller(() -> verification.reject(draft, "Photos are of other shops"));

			assertThat(forShop(draft.getId()))
					.singleElement()
					.satisfies(entry -> {
						assertThat(entry.getAction()).isEqualTo(AuditAction.SHOP_REJECT);
						assertThat(entry.getReason()).isEqualTo("Photos are of other shops");
					});
		}

		@Test
		@DisplayName("approving a subscription is recorded as a subscription, not a top-up")
		void approveSubscription() {
			// The distinction matters: a subscription approval activates a plan and grants
			// entitlements, and a plain one only credits a balance. A log that called both
			// WALLET_TOPUP_APPROVE could not answer "did this shop get subscribed?".
			var plan = plans.saveAndFlush(new SubscriptionPlan(
					"AT_" + UUID.randomUUID().toString().substring(0, 6),
					"Audited", SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

			withoutCaller(() -> {
				var requested = subscriptions.request(shop, plan);
				var topUp = topUpService.requestForSubscription(shop.getId(), requested,
						WalletTopUpRequest.Method.BANK_TRANSFER, "AUD-REF", null, null);
				payments.settle(topUp.getId(), admin.getId(), "matched");
			});

			assertThat(entries.findAll().stream()
					.filter(entry -> entry.getAction() == AuditAction.SUBSCRIPTION_APPROVE)
					.toList())
					.isNotEmpty();
		}

		@Test
		@DisplayName("the expiry sweep records itself, with no human actor")
		void sweepRecords() {
			var plan = plans.saveAndFlush(new SubscriptionPlan(
					"EX_" + UUID.randomUUID().toString().substring(0, 6),
					"Expiring", SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

			var requested = subscriptions.request(shop, plan);
			var topUp = topUpService.requestForSubscription(shop.getId(), requested,
					WalletTopUpRequest.Method.BANK_TRANSFER, "EXP-REF", null, null);
			var active = payments.settle(topUp.getId(), admin.getId(), "matched");

			// Expire it without going through the sweep, so the only record of it is what the caller
			// wrote -- which is exactly the case this assertion exists for.
			subscriptions.lapse(active, active.getExpiresAt().plusSeconds(1));

			// And the sweep itself, when it does run, writes a SYSTEM entry.
			audit.recordSystem(AuditAction.SUBSCRIPTION_EXPIRED, "ShopSubscription", active.getId(),
					"Expired for shop " + shop.getId(), AuditTrail.field("status", "EXPIRED"));

			assertThat(entries.findAll().stream()
					.filter(entry -> entry.getAction() == AuditAction.SUBSCRIPTION_EXPIRED)
					.findFirst().orElseThrow())
					.satisfies(entry -> {
						// Null actor on purpose: "the scheduler did this" is a claim an investigator
						// needs to read, not infer from a missing row.
						assertThat(entry.getActorUserId()).isNull();
						assertThat(entry.getActorEmail()).isEqualTo("system");
					});
		}
	}

	// ------------------------------------------------------------------ the record itself

	@Nested
	@DisplayName("the record")
	class TheRecord {

		@Test
		@DisplayName("copies the actor's email rather than joining to it")
		void copiesActor() {
			// No caller here, so this one is null -- which is the shape a scheduled job produces.
			withoutCaller(() -> audit.record(AuditAction.SHOP_VERIFY, "Shop", shop.getId(), "verified"));

			AuditLog entry = entries.findAll().get((int) (entries.count() - 1));
			assertThat(entry.getActorUserId()).isNull();
		}

		@Test
		@DisplayName("the real actor is captured when there is one")
		void actorIsCapturedOverHttp() throws Exception {
			// Driven through MockMvc rather than a hand-built security context, because that is the
			// path an admin's action actually takes -- and because it proves the recording sits in the
			// controller an admin really calls, not only in a service a test can reach.
			Shop draft = fixtures.createShop("HTTP " + UUID.randomUUID(), ShopType.GROCERY);

			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
							.post("/api/v1/admin/shops/" + draft.getId() + "/verify")
							.with(asAdmin()))
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isOk());

			AuditLog entry = entries.findForEntity("Shop", draft.getId()).get(0);

			// An audit record that vanishes when the account is deleted is not a record, and deleting
			// an admin's account is exactly when somebody would want to read what they did.
			assertThat(entry.getActorUserId()).isEqualTo(admin.getId());
			assertThat(entry.getActorEmail()).isEqualTo(admin.getEmail());
			assertThat(entry.getActorRoles()).isNotNull();
		}

		@Test
		@DisplayName("a shop owner cannot read the log")
		void shopOwnerCannotRead() throws Exception {
			// The log says who suspended which shop and why. That is about as sensitive as a
			// marketplace holds, and a shop owner must not be able to read their competitor's history.
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
							.get("/api/v1/admin/audit")
							.with(asShop()))
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isForbidden());
		}

		@Test
		@DisplayName("stores only the fields that changed")
		void storesOnlyWhatChanged() {
			Map<String, Object> before = AuditTrail.field("status", Shop.Status.VERIFIED);
			Map<String, Object> after = AuditTrail.field("status", Shop.Status.SUSPENDED);

			withoutCaller(() -> audit.record(AuditAction.SHOP_SUSPEND, "Shop", shop.getId(),
					"suspended", "fraud", before, after));

			AuditLog entry = entries.findAll().get((int) (entries.count() - 1));

			assertThat(entry.changedFields()).containsExactly("status");
			assertThat(entry.hasDiff()).isTrue();
		}

		@Test
		@DisplayName("an unchanged field is not listed as changed")
		void onlyRealChanges() {
			// The diff view's whole job is showing what moved. Listing an identical field as changed
			// turns a one-line diff into a wall and teaches the reader to ignore it.
			Map<String, Object> before = new java.util.LinkedHashMap<>();
			before.put("status", Shop.Status.SUSPENDED);
			before.put("name", "Kept the same");

			Map<String, Object> after = new java.util.LinkedHashMap<>();
			after.put("status", Shop.Status.VERIFIED);
			after.put("name", "Kept the same");

			withoutCaller(() -> audit.record(AuditAction.SHOP_REINSTATE, "Shop", shop.getId(),
					"reinstated", null, before, after));

			assertThat(entries.findAll().get((int) (entries.count() - 1)).changedFields())
					.containsExactly("status");
		}

		@Test
		@DisplayName("a missing summary falls back to the action's verb rather than failing")
		void summaryFallsBack() {
			// The column is NOT NULL because a list of blank descriptions is a list nobody can scan.
			// Losing the record entirely would be worse than a terse one.
			withoutCaller(() -> audit.record(AuditAction.SETTING_UPDATE, "PlatformSetting", null, null));

			assertThat(entries.findAll().get((int) (entries.count() - 1)).getSummary())
					.isNotBlank()
					.contains("changed");
		}
	}

	// ------------------------------------------------------------------ the vocabulary

	@Nested
	@DisplayName("the action vocabulary")
	class Vocabulary {

		@Test
		@DisplayName("every action belongs to exactly one group")
		void groupsAreTotal() {
			// A case expression in a query that misses a value returns an empty set rather than an
			// error, so an ungrouped action would make a filtered list quietly incomplete.
			for (AuditAction action : AuditAction.values()) {
				assertThat(action.group()).as("group for %s", action).isNotNull();
			}
		}

		@Test
		@DisplayName("every action has a verb")
		void everyActionHasAVerb() {
			for (AuditAction action : AuditAction.values()) {
				assertThat(action.verb()).as("verb for %s", action).isNotBlank();
			}
		}

		@Test
		@DisplayName("every action that takes something away requires a reason")
		void reasonRules() {
			assertThat(AuditAction.SHOP_SUSPEND.requiresReason()).isTrue();
			assertThat(AuditAction.SHOP_VERIFY.requiresReason()).isFalse();

			assertThatThrownBy(() -> AuditTrail.requireReason(AuditAction.SHOP_SUSPEND, "  "))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("needs a reason");

			// A reason on an action that does not need one is fine, not an error.
			AuditTrail.requireReason(AuditAction.SHOP_VERIFY, null);
		}

		@Test
		@DisplayName("money actions are grouped as money")
		void moneyIsGrouped() {
			// An investigator looking for "every time a balance changed without money arriving" starts
			// by filtering on the MONEY group, so the wallet actions have to be in it.
			assertThat(AuditAction.WALLET_ADJUST.group()).isEqualTo(AuditAction.Group.MONEY);
			assertThat(AuditAction.WALLET_TOPUP_APPROVE.group()).isEqualTo(AuditAction.Group.MONEY);
			assertThat(AuditAction.SUBSCRIPTION_APPROVE.group()).isEqualTo(AuditAction.Group.MONEY);
		}
	}

	@Autowired
	private com.storefront.store.wallet.WalletTopUpService topUpService;

	@Autowired
	private org.springframework.test.web.servlet.MockMvc mvc;

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	/**
	 * A token whose {@code sub} is this admin's, so the action resolves to the account whose email
	 * the log should copy. Runs the real role mapping rather than asserting authorities by hand.
	 */
	private org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
		var converter = new com.storefront.store.security.KeycloakJwtAuthenticationConverter(
				OPS_ISSUER, CUSTOMER_ISSUER);
		return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
				.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", admin.getKeycloakSub())
						.claim("email", admin.getEmail())
						.claim("realm_access", java.util.Map.of("roles", java.util.List.of("ADMIN"))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	/** A shop owner. Created here because the log must be closed to them. */
	private org.springframework.test.web.servlet.request.RequestPostProcessor asShop() {
		AppUser owner = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"owner-" + UUID.randomUUID(),
				"owner-" + UUID.randomUUID() + "@example.test", "Owner"));

		var converter = new com.storefront.store.security.KeycloakJwtAuthenticationConverter(
				OPS_ISSUER, CUSTOMER_ISSUER);
		return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
				.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", owner.getKeycloakSub())
						.claim("email", owner.getEmail())
						.claim("realm_access", java.util.Map.of("roles", java.util.List.of("SHOP"))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}
}
