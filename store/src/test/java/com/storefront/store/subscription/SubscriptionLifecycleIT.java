package com.storefront.store.subscription;

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

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletLedgerRepository;
import com.storefront.store.wallet.WalletService;
import com.storefront.store.wallet.WalletTopUpRepository;
import com.storefront.store.wallet.WalletTopUpRequest;

/**
 * The whole D7 subscription flow, end to end, against a real database.
 *
 * <p><b>This is the Phase 6 exit criterion as a test.</b> "A shop upgrades, its commission drops to
 * 0% and paid templates unlock, the ledger records the charge, and expiry restores the free tier
 * correctly" — each of those four clauses is asserted below, and the test that would notice if any
 * of them stopped happening is the one place they are checked together.
 *
 * <p>The money path is exercised through the services rather than through HTTP, because the claim
 * under test is about <em>what happened to the rows</em> — the ledger, the entitlements and the
 * commission flag — and an HTTP round trip would only assert the response body, which is written by
 * the same code that could be wrong. {@code SubscriptionControllerIT} covers the HTTP shape and the
 * authorisation boundaries.
 *
 * <p>Every test starts from the seeded FREE plan, so "the free tier" is a real row rather than a
 * hardcoded expectation. That is what makes the lapse assertions meaningful: the fallback is data,
 * and if someone raises the free cap the tests follow it.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class SubscriptionLifecycleIT {

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private SubscriptionPaymentService payments;

	@Autowired
	private SubscriptionPlanRepository plans;

	@Autowired
	private ShopSubscriptionRepository subscriptionRows;

	@Autowired
	private ShopEntitlementRepository entitlements;

	@Autowired
	private EntitlementService entitlementService;

	@Autowired
	private WalletTopUpRepository topUps;

	@Autowired
	private com.storefront.store.wallet.WalletTopUpService topUpService;

	@Autowired
	private WalletLedgerRepository ledger;

	@Autowired
	private WalletService wallet;

	@Autowired
	private SubscriptionExpiryJob expiryJob;

	@Autowired
	private com.storefront.store.shop.ShopRepository shops;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private com.storefront.store.subscription.PlanEntitlementRepository planEntitlements;

	@Autowired
	private AppUserRepository users;

	private Shop shop;
	private AppUser admin;
	private SubscriptionPlan monthly;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Sub Shop " + UUID.randomUUID(), ShopType.GROCERY);
		admin = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"admin-" + UUID.randomUUID(), "admin-" + UUID.randomUUID() + "@example.test", "Admin"));

		// A dedicated plan per test, so a test that changes a plan's entitlements cannot change it
		// for the next test in the same transaction-scoped database.
		monthly = plans.saveAndFlush(new SubscriptionPlan(
				"PRO_T" + UUID.randomUUID().toString().substring(0, 6),
				"Pro (test)", SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

		// And it must GRANT things. A plan with no plan_entitlement rows activates fine and grants
		// nothing at all, which is a correct outcome the tests here are not measuring -- so the plan
		// is given the same entitlements the migration gives PRO_MONTHLY, and the entitlement
		// assertions are then about the mechanism rather than about an empty plan.
		grantProFeatures(monthly);
	}

	/** The PRO_MONTHLY entitlement set, from V13. */
	private void grantProFeatures(SubscriptionPlan plan) {
		planEntitlements.deleteByPlanId(plan.getId());
		planEntitlements.flush();
		for (Object[] row : new Object[][] {
				{ FeatureKey.MAX_PRODUCTS, true, 500L },
				{ FeatureKey.REMOVE_COMMISSION, true, null },
				{ FeatureKey.USE_PAID_TEMPLATES, true, null },
				{ FeatureKey.CUSTOM_CSS, true, null },
				{ FeatureKey.DISCOUNT_CODES, true, null },
				{ FeatureKey.CSV_IMPORT, true, null },
				{ FeatureKey.PRIORITY_SUPPORT, true, null },
		}) {
			planEntitlements.saveAndFlush(new PlanEntitlement(
					plan, (FeatureKey) row[0], (Boolean) row[1], (Long) row[2]));
		}
	}

	/**
	 * The shop, re-read from the database.
	 *
	 * <p><b>Needed for every commission-flag assertion.</b> {@code SubscriptionService.activate}
	 * loads its own {@code Shop} and flips the flag on <em>that</em> instance; the reference this
	 * test holds is a different Java object and keeps its old value for the rest of the test. In
	 * production nothing is stale, because accepting an order is a different request that loads the
	 * shop fresh -- but a test that asserts on its own stale reference would be asserting that
	 * subscriptions do not work.
	 */
	private Shop reloaded() {
		return shops.findById(shop.getId()).orElseThrow();
	}

	// ------------------------------------------------------------------ the whole flow

	@Test
	@DisplayName("clause 1-3: a shop upgrades, commission drops to 0, and the ledger records the charge")
	void upgradeWaivesCommissionAndRecordsTheCharge() {
		// --- before: on commission, on the free tier's cap
		assertThat(reloaded().paysCommission()).isTrue();
		assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isFalse();
		assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS))
				.hasValue(25L);

		// --- request
		ShopSubscription requested = subscriptions.request(shop, monthly);

		// Requesting alone must change nothing. A queue that granted access would make the whole
		// manual-approval design decorative.
		assertThat(requested.getStatus()).isEqualTo(ShopSubscription.Status.PENDING_PAYMENT);
		assertThat(reloaded().paysCommission()).isTrue();
		assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isFalse();

		// --- pay: the shop transfers the plan price and submits the top-up
		WalletTopUpRequest topUp = topUpService.requestForSubscription(
				shop.getId(), requested, WalletTopUpRequest.Method.BANK_TRANSFER,
				"PRO-TEST-001", null, "Pro subscription");
		assertThat(topUp.getSubscriptionId()).isEqualTo(requested.getId());
		assertThat(topUp.getAmountMinor()).isEqualTo(monthly.getPriceMinor());

		// --- admin approves
		ShopSubscription active = payments.settle(topUp.getId(), admin.getId(), "matched the statement");

		assertThat(active.getStatus()).isEqualTo(ShopSubscription.Status.ACTIVE);

		// --- clause 1: commission dropped to zero
		assertThat(reloaded().paysCommission()).isFalse();
		assertThat(reloaded().getCommissionPlan()).isEqualTo(Shop.CommissionPlan.SUBSCRIBED);
		assertThat(entitlementService.commissionsWaived(shop.getId())).isTrue();

		// --- clause 2: paid templates unlocked, and the cap rose
		assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isTrue();
		assertThat(entitlementService.has(shop.getId(), FeatureKey.DISCOUNT_CODES)).isTrue();
		assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS))
				.hasValue(500L);

		// --- clause 3: the ledger records the charge, linked to the subscription
		List<WalletLedgerEntry> charges = ledger.findAll().stream()
				.filter(entry -> entry.getEntryType() == WalletLedgerEntry.EntryType.SUBSCRIPTION_CHARGE)
				.filter(entry -> entry.getShopId().equals(shop.getId()))
				.toList();

		assertThat(charges).hasSize(1);
		assertThat(charges.get(0).getAmountMinor()).isEqualTo(monthly.getPriceMinor());
		assertThat(charges.get(0).getDirection()).isEqualTo(WalletLedgerEntry.Direction.DEBIT);
		assertThat(charges.get(0).getSubscriptionId()).isEqualTo(active.getId());

		// The credit and the charge net to zero, by design -- see SubscriptionPaymentService. What
		// matters is that both are recorded with reasons rather than reconciled by hand.
		assertThat(wallet.balanceOf(shop.getId())).isZero();
	}

	@Test
	@DisplayName("clause 4: expiry restores the free tier correctly")
	void expiryRestoresTheFreeTier() {
		ShopSubscription active = subscribe(monthly);

		// While running: subscribed, commission-free, enriched.
		assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isTrue();
		assertThat(reloaded().paysCommission()).isFalse();

		// --- the window closes
		Instant afterExpiry = active.getExpiresAt().plusSeconds(1);
		assertThat(subscriptions.lapse(active, afterExpiry)).isTrue();

		// --- commission is back
		assertThat(reloaded().paysCommission()).isTrue();
		assertThat(reloaded().getCommissionPlan()).isEqualTo(Shop.CommissionPlan.FREE);

		// --- paid features are gone
		assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isFalse();
		assertThat(entitlementService.has(shop.getId(), FeatureKey.DISCOUNT_CODES)).isFalse();

		// --- and the FREE tier's cap is back, not "no cap at all"
		//
		// This is the assertion that fails if the free baseline is represented as "no rows". Deleting
		// the materialised entitlements would then leave a lapsed subscriber with NO cap -- able to
		// list without limit, which is the opposite of what lapsing should do.
		assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS))
				.hasValue(25L);

		// --- and no commission waiver survives
		assertThat(entitlementService.commissionsWaived(shop.getId())).isFalse();
	}

	// ------------------------------------------------------------------ the money

	@Nested
	@DisplayName("the money")
	class Money {

		@Test
		@DisplayName("the amount always comes from the plan, so it cannot be got wrong")
		void amountComesFromThePlan() {
			// There is deliberately no amount parameter on `requestForSubscription`. A shop cannot
			// ask to be charged one plan's price and be granted another, because it never supplies
			// the number -- which is stronger than validating a number it supplied.
			ShopSubscription requested = subscriptions.request(shop, monthly);

			WalletTopUpRequest topUp = topUpService.requestForSubscription(
					shop.getId(), requested, WalletTopUpRequest.Method.BANK_TRANSFER,
					"REF", null, null);

			assertThat(topUp.getAmountMinor()).isEqualTo(monthly.getPriceMinor());
			assertThat(topUps.findBySubscriptionId(requested.getId())).isPresent();
		}

		@Test
		@DisplayName("a top-up that disagrees with its plan is refused at submit time")
		void amountMustMatchThePlan() {
			// The guard on the entity, reachable only if some future caller builds the request
			// itself. Tested here because it is the last thing standing between an admin's queue and
			// a request whose amount and plan disagree -- an admin should never be handed one and be
			// expected to spot it.
			WalletTopUpRequest mismatched = new WalletTopUpRequest(shop.getId(), 1,
					WalletTopUpRequest.Method.BANK_TRANSFER, "REF", null, null);

			assertThatThrownBy(() -> mismatched.forSubscription(
					UUID.randomUUID(), monthly.getId(), monthly.getPriceMinor()))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Enter the plan's exact price");
		}

		@Test
		@DisplayName("a settled top-up cannot be re-pointed at another plan")
		void settledRequestsAreNotRepointable() {
			// Otherwise a request that has already been credited could be rewritten to name a
			// different plan, and what the admin approved would no longer be what the shop gets.
			ShopSubscription requested = subscriptions.request(shop, monthly);
			WalletTopUpRequest topUp = topUpService.requestForSubscription(
					shop.getId(), requested, WalletTopUpRequest.Method.BANK_TRANSFER,
					"REF", null, null);

			payments.settle(topUp.getId(), admin.getId(), "approved");

			assertThatThrownBy(() -> topUp.forSubscription(
					UUID.randomUUID(), monthly.getId(), monthly.getPriceMinor()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("already been APPROVED");
		}

		@Test
		@DisplayName("approving twice does not charge twice")
		void approvalIsIdempotent() {
			ShopSubscription requested = subscriptions.request(shop, monthly);
			WalletTopUpRequest topUp = topUpService.requestForSubscription(
					shop.getId(), requested, WalletTopUpRequest.Method.BANK_TRANSFER,
					"REF", null, null);

			payments.settle(topUp.getId(), admin.getId(), "first");

			// A replay must not produce a second debit. The ledger's idempotency key is keyed on the
			// subscription, and the subscription is no longer pending, so both guards stop it.
			assertThatThrownBy(() -> payments.settle(topUp.getId(), admin.getId(), "retry"))
					.isInstanceOfAny(RuntimeException.class);

			assertThat(countCharges()).isEqualTo(1);
		}

		@Test
		@DisplayName("a top-up with no subscription is not settled by this path")
		void plainTopUpIsNotASubscription() {
			WalletTopUpRequest plain = topUpService.request(shop.getId(), 500_000,
					WalletTopUpRequest.Method.BANK_TRANSFER, "REF", null, "just topping up");

			// An admin who approves an ordinary top-up through the subscription screen must get an
			// error, not a silent credit with no subscription started -- which would read in the audit
			// trail as a purchase that never happened.
			assertThatThrownBy(() -> payments.settle(plain.getId(), admin.getId(), null))
					.isInstanceOf(SubscriptionPaymentService.TopUpNotForSubscriptionException.class);
		}

		@Test
		@DisplayName("no commission waiver is granted before the money is approved")
		void nothingIsGrantedUntilApproval() {
			ShopSubscription requested = subscriptions.request(shop, monthly);
			topUpService.requestForSubscription(shop.getId(), requested,
					WalletTopUpRequest.Method.BANK_TRANSFER, "REF", null, null);

			assertThat(reloaded().paysCommission()).isTrue();
			assertThat(entitlementService.has(shop.getId(), FeatureKey.REMOVE_COMMISSION)).isFalse();
			assertThat(countCharges()).isZero();
			assertThat(wallet.balanceOf(shop.getId())).isZero();
		}

		private long countCharges() {
			return ledger.findAll().stream()
					.filter(entry -> entry.getEntryType() == WalletLedgerEntry.EntryType.SUBSCRIPTION_CHARGE)
					.filter(entry -> entry.getShopId().equals(shop.getId()))
					.count();
		}
	}

	// ------------------------------------------------------------------ the lifecycle

	@Nested
	@DisplayName("the lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("a shop cannot queue the same plan twice")
		void noDuplicatePendingRequests() {
			subscriptions.request(shop, monthly);

			// Otherwise a double-clicked button accumulates requests and the shop is charged twice.
			assertThatThrownBy(() -> subscriptions.request(shop, monthly))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("already requested");
		}

		@Test
		@DisplayName("an upgrade can be requested while a subscription runs")
		void upgradeIsAllowedImmediately() {
			// A monthly subscriber who decides to go annual must not be told to wait out the month,
			// and a platform where upgrading requires a support message is a platform that loses the
			// upgrade.
			SubscriptionPlan expensive = plans.saveAndFlush(new SubscriptionPlan(
					"BIG_T", "Pro annual-ish", SubscriptionPlan.BillingPeriod.ANNUAL, 900_000, 366));
			grantProFeatures(expensive);

			subscribe(monthly);
			ShopSubscription upgrade = subscriptions.request(shop, expensive);

			assertThat(upgrade.getStatus()).isEqualTo(ShopSubscription.Status.PENDING_PAYMENT);
		}

		@Test
		@DisplayName("a downgrade must wait for the current period to end")
		void downgradeIsRefusedWhileRunning() {
			// The other half of the rule. Switching early would hand the shop a cheaper plan's
			// entitlements immediately -- a refund the system performs by accident, for a period
			// already paid for.
			subscribe(monthly);

			SubscriptionPlan cheap = plans.saveAndFlush(new SubscriptionPlan(
					"CHEAP_D", "Cheap", SubscriptionPlan.BillingPeriod.MONTHLY, 50_000, 31));

			assertThatThrownBy(() -> subscriptions.request(shop, cheap))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("starts when your current one ends");
		}

		@Test
		@DisplayName("changing plan closes the old one and grants only the new one's features")
		void planChangeReplacesEntitlements() {
			// A plan that grants something the first one does not, so the swap is observable.
			// Cheaper than `monthly` (100_000 vs 200_000), so this is the *downgrade* direction --
			// which `request` deliberately refuses while a subscription runs. The upgrade direction is
			// covered separately, by switching from this plan to `monthly`.
			SubscriptionPlan cheap = plans.saveAndFlush(new SubscriptionPlan(
					"CHEAP_T", "Cheap", SubscriptionPlan.BillingPeriod.MONTHLY, 100_000, 31));
			planEntitlement(cheap, FeatureKey.MAX_PRODUCTS, true, 100L);
			planEntitlement(cheap, FeatureKey.USE_PAID_TEMPLATES, true, null);

			subscribe(cheap);
			assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isTrue();

			// An upgrade, so `request` allows it: it costs more than what is running.
			ShopSubscription swapped = subscriptions.request(shop, monthly);
			payments.settle(topUpFor(swapped).getId(), admin.getId(), "upgrade");

			assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS))
					.hasValue(500L);
			assertThat(reloaded().paysCommission()).isFalse();

			// Exactly one running subscription, which the partial unique index also enforces.
			assertThat(subscriptionRows.findRunningByShopId(shop.getId(), Instant.now()))
					.hasValueSatisfying(running ->
							assertThat(running.getId()).isEqualTo(swapped.getId()));
		}

		@Test
		@DisplayName("cancelling keeps the plan until the paid period ends")
		void cancellingDefers() {
			ShopSubscription active = subscribe(monthly);

			subscriptions.cancel(shop.getId(), active.getId());

			// Still commission-free, still entitled: the shop paid for this period.
			assertThat(reloaded().paysCommission()).isFalse();
			assertThat(subscriptionRows.findRunningByShopId(shop.getId(), Instant.now()))
					.isPresent();
			assertThat(active.willNotRenew()).isTrue();

			// And after the window, the sweep records it as cancelled rather than expired.
			subscriptions.lapse(active, active.getExpiresAt().plusSeconds(1));
			assertThat(active.getStatus()).isEqualTo(ShopSubscription.Status.CANCELLED);
		}

		@Test
		@DisplayName("the sweep is idempotent")
		void sweepIsIdempotent() {
			ShopSubscription active = subscribe(monthly);

			// Nothing has lapsed yet, so a sweep now must change nothing at all.
			assertThat(expiryJob.expireNow()).isZero();
			assertThat(reloaded().paysCommission()).isFalse();

			// Sweeping twice after the fact must not damage the shop.
			assertThat(subscriptions.lapse(active, active.getExpiresAt().plusSeconds(1))).isTrue();
			assertThat(subscriptions.lapse(active, active.getExpiresAt().plusSeconds(1))).isFalse();
			assertThat(reloaded().paysCommission()).isTrue();
		}

		@Test
		@DisplayName("a withdrawn plan cannot be requested")
		void withdrawnPlansAreOffSale() {
			monthly.setActive(false);
			plans.saveAndFlush(monthly);

			assertThatThrownBy(() -> subscriptions.request(shop, monthly))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("no longer available");
		}

		@Test
		@DisplayName("a shop cannot cancel another shop's subscription")
		void crossTenantCancelIsRefused() {
			ShopSubscription other = subscribe(monthly);

			Shop elsewhere = fixtures.createVerifiedShop(
					"Other " + UUID.randomUUID(), ShopType.PHARMACY);

			// 404 rather than 403: the caller has no business learning whether this id exists, and
			// the plan's rule is that a cross-tenant lookup answers "not found".
			assertThatThrownBy(() -> subscriptions.cancel(elsewhere.getId(), other.getId()))
					.isInstanceOf(SubscriptionService.NoSuchSubscriptionException.class);

			assertThat(other.getStatus()).isEqualTo(ShopSubscription.Status.ACTIVE);
		}
	}

	// ------------------------------------------------------------------ entitlements on read

	@Nested
	@DisplayName("reading entitlements")
	class Reading {

		@Test
		@DisplayName("a shop with no subscription gets the free tier's caps")
		void freeTierFallback() {
			// The baseline is data, so a shop that never subscribed still has a real product cap.
			assertThat(entitlementService.has(shop.getId(), FeatureKey.MAX_PRODUCTS)).isTrue();
			assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS))
					.hasValue(25L);
			assertThat(entitlementService.has(shop.getId(), FeatureKey.CUSTOM_CSS)).isFalse();
		}

		@Test
		@DisplayName("a lapsed grant is not honoured even before the sweep runs")
		void expiryIsEnforcedOnRead() {
			ShopSubscription active = subscribe(monthly);

			// Backdate every materialised grant's own window, as a scheduler being down would leave
			// them. The read must deny on each row's expiry, not on the subscription's status -- which
			// is still ACTIVE here, because nothing has run.
			assertThat(active.getStatus()).isEqualTo(ShopSubscription.Status.ACTIVE);
			List<ShopEntitlement> grants = entitlements.findByShopId(shop.getId());
			assertThat(grants).isNotEmpty();

			for (ShopEntitlement grant : grants) {
				grant.setExpiresAt(Instant.now().minusSeconds(1));
			}
			entitlements.saveAllAndFlush(grants);

			assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isFalse();
			assertThat(entitlementService.commissionsWaived(shop.getId())).isFalse();

			// And the fallback takes over, so the shop is not left with no cap.
			assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS))
					.hasValue(25L);
		}

		@Test
		@DisplayName("a feature the plan does not mention falls through to the free tier")
		void unmentionedFeaturesFallThrough() {
			SubscriptionPlan minimal = plans.saveAndFlush(new SubscriptionPlan(
					"MIN_T", "Minimal", SubscriptionPlan.BillingPeriod.MONTHLY, 50_000, 31));
			planEntitlement(minimal, FeatureKey.MAX_PRODUCTS, true, 42L);

			subscriptions.activate(subscriptions.request(shop, minimal), Instant.now(), null);

			// The plan says nothing about paid templates, so the free tier's "no" applies.
			assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS))
					.hasValue(42L);
			assertThat(entitlementService.has(shop.getId(), FeatureKey.USE_PAID_TEMPLATES)).isFalse();
		}

		@Test
		@DisplayName("a refused limit names the numbers and the plan to buy")
		void refusalCarriesAnUpgradePath() {
			ShopSubscription active = subscribe(monthly);
			assertThat(active.getId()).isNotNull();

			// Free-tier cap is 25.
			var refused = org.assertj.core.api.Assertions.catchThrowableOfType(
					() -> entitlementService.requireCapacity(shop.getId(),
							FeatureKey.MAX_PRODUCTS, 25, 1),
					EntitlementService.EntitlementDeniedException.class);

			// Unreachable while subscribed at 500 -- so assert the same rule against a shop that has
			// not subscribed, which is the case that actually happens.
			assertThat(refused).isNull();

			Shop unsubscribed = fixtures.createVerifiedShop(
					"Free " + UUID.randomUUID(), ShopType.GROCERY);

			var denial = org.assertj.core.api.Assertions.catchThrowableOfType(
					() -> entitlementService.requireCapacity(unsubscribed.getId(),
							FeatureKey.MAX_PRODUCTS, 25, 1),
					EntitlementService.EntitlementDeniedException.class);

			assertThat(denial).isNotNull();
			assertThat(denial.getMessage())
					.contains("25")     // the limit
					.contains("25");     // what they have
			assertThat(denial.feature()).isEqualTo(FeatureKey.MAX_PRODUCTS);
			assertThat(denial.upgradeToCode()).isNotBlank();
			assertThat(denial.limit().limit()).isEqualTo(25L);
		}

		@Test
		@DisplayName("a capacity that fits is not refused")
		void withinCapacity() {
			Shop unsubscribed = fixtures.createVerifiedShop(
					"Free " + UUID.randomUUID(), ShopType.GROCERY);

			assertThat(entitlementService.hasCapacity(
					unsubscribed.getId(), FeatureKey.MAX_PRODUCTS, 24, 1)).isTrue();
			assertThat(entitlementService.hasCapacity(
					unsubscribed.getId(), FeatureKey.MAX_PRODUCTS, 25, 1)).isFalse();
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Grants a feature on a plan, replacing whatever that plan had. */
	private void planEntitlement(SubscriptionPlan plan, FeatureKey feature,
			boolean enabled, Long limit) {
		planEntitlements.deleteByPlanId(plan.getId());
		planEntitlements.flush();
		planEntitlements.saveAndFlush(new PlanEntitlement(plan, feature, enabled, limit));
	}

	/** Request, pay, approve, activate. The whole D7 flow in three lines. */
	private ShopSubscription subscribe(SubscriptionPlan plan) {
		ShopSubscription requested = subscriptions.request(shop, plan);
		return payments.settle(topUpFor(requested).getId(), admin.getId(), "paid");
	}

	/** The top-up a shop would submit for a queued request. */
	private WalletTopUpRequest topUpFor(ShopSubscription subscription) {
		return topUpService.requestForSubscription(shop.getId(), subscription,
				WalletTopUpRequest.Method.BANK_TRANSFER, "REF-" + subscription.getId(), null, null);
	}
}
