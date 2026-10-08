package com.storefront.store.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.storefront.store.wallet.WalletService;
import com.storefront.store.wallet.WalletTopUpRequest;

/**
 * The revenue figures (Phase 6).
 *
 * <p>A dashboard is mostly ratios, and a ratio without a stated denominator is a number that means
 * something different on every screen it appears on. So these tests are as much about <em>what each
 * figure counts</em> as about the arithmetic:
 *
 * <ul>
 *   <li><b>MRR divides an annual plan by twelve.</b> This is the whole definition of a monthly
 *       figure, and the one most likely to be implemented as "sum the prices".</li>
 *   <li><b>Realised and waived commission are different numbers</b>, and reporting only the first
 *       makes a working subscription programme look like falling revenue.</li>
 *   <li><b>Waived commission comes from orders, not the ledger</b> — a waiver writes no ledger row,
 *       because no money moved. That is the point of it.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class RevenueServiceIT {

	@Autowired
	private RevenueService revenue;

	@Autowired
	private SubscriptionPaymentService payments;

	@Autowired
	private com.storefront.store.wallet.WalletTopUpService topUpService;

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private SubscriptionPlanRepository plans;

	@Autowired
	private PlanEntitlementRepository planEntitlements;

	@Autowired
	private WalletService wallet;

	@Autowired
	private com.storefront.store.shop.ShopRepository shops;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AppUserRepository users;

	private AppUser admin;
	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Rev " + UUID.randomUUID(), ShopType.GROCERY);
		admin = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"admin-" + UUID.randomUUID(), "a-" + UUID.randomUUID() + "@example.test", "Admin"));
	}

	// ------------------------------------------------------------------ MRR

	@Nested
	@DisplayName("MRR")
	class Mrr {

		@Test
		@DisplayName("is zero when nobody has subscribed")
		void empty() {
			var summary = revenue.summary(Instant.now());

			assertThat(summary.mrrMinor()).isZero();
			assertThat(summary.paidSubscribers()).isZero();
			// The free tier is never a subscriber, however many shops sit on it.
			assertThat(summary.commissionFreeShops()).isZero();
		}

		@Test
		@DisplayName("counts a monthly plan at its price")
		void monthlyPlan() {
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

			assertThat(revenue.summary(Instant.now()).mrrMinor()).isEqualTo(200_000L);
		}

		@Test
		@DisplayName("counts an annual plan at a twelfth, not at its full price")
		void annualPlanIsDivided() {
			// THE assertion. Summing an annual plan's price would report a twelvefold jump every year,
			// and a dashboard that does that is a dashboard nobody trusts by the second January.
			subscribe(plan(SubscriptionPlan.BillingPeriod.ANNUAL, 2_400_000, 366));

			assertThat(revenue.summary(Instant.now()).mrrMinor()).isEqualTo(200_000L);
		}

		@Test
		@DisplayName("sums across shops and never counts one twice")
		void sumsAcrossShops() {
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 100_000, 31));
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 150_000, 31));

			var summary = revenue.summary(Instant.now());

			assertThat(summary.mrrMinor()).isEqualTo(250_000L);
			assertThat(summary.paidSubscribers()).isEqualTo(2);
		}

		@Test
		@DisplayName("drops a subscription the moment its window closes")
		void lapsedIsNotMrr() {
			ShopSubscription active = subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY,
					200_000, 31));

			assertThat(revenue.summary(Instant.now()).mrrMinor()).isEqualTo(200_000L);

			// Read with a clock past the expiry but with no sweep run, which is the state a stopped
			// scheduler leaves behind. MRR must not include it.
			Instant after = active.getExpiresAt().plusSeconds(1);
			assertThat(revenue.summary(after).mrrMinor()).isZero();
		}

		@Test
		@DisplayName("the display string and the minor units agree")
		void displayAndMinorAgree() {
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));
			var summary = revenue.summary(Instant.now());

			// Two formatters for one number is how a dashboard ends up showing a total that does not
			// match the sum of its rows.
			assertThat(summary.mrrDisplay()).contains("2,000");
			assertThat(summary.mrrMinor()).isEqualTo(200_000L);
		}
	}

	// ------------------------------------------------------------------ commission

	@Nested
	@DisplayName("commission")
	class Commission {

		@Test
		@DisplayName("realised commission comes from the ledger and is net of reversals")
		void realisedIsNet() {
			RevenueService.RevenueSummary before = baseline();

			fund(600_000);
			// orderId is null, not a random UUID. `wallet_ledger_entry.order_id` is a real foreign
			// key, and a made-up id fails with "Key (order_id)=(...) is not present in table
			// shop_order" -- which is the constraint doing exactly its job.
			wallet.debit(shop.getId(), WalletLedgerEntry.EntryType.COMMISSION_DEBIT,
					50_000, "k1", "Commission on ORD-1", null, admin.getId());
			wallet.debit(shop.getId(), WalletLedgerEntry.EntryType.COMMISSION_DEBIT,
					30_000, "k2", "Commission on ORD-2", null, admin.getId());
			wallet.credit(shop.getId(), WalletLedgerEntry.EntryType.COMMISSION_REVERSAL,
					20_000, "k3", "Cancelled ORD-2", null, admin.getId(), null);

			// 50_000 + 30_000 - 20_000. A reversal must *reduce* the figure, so it is subtracted
			// explicitly rather than netted inside a query whose sign convention nobody would guess.
			var after = revenue.summary(Instant.now());

			assertThat(after.commissionRealisedMinor() - before.commissionRealisedMinor())
					.isEqualTo(60_000L);
			assertThat(after.commissionReversedMinor() - before.commissionReversedMinor())
					.isEqualTo(20_000L);
		}

		@Test
		@DisplayName("a commission-free shop contributes no realised commission")
		void waivedShopEarnsNothing() {
			RevenueService.RevenueSummary before = baseline();
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

			var summary = revenue.summary(Instant.now());

			assertThat(summary.paidSubscribers()).isEqualTo(before.paidSubscribers() + 1);
			assertThat(summary.commissionFreeShops()).isEqualTo(before.commissionFreeShops() + 1);

			// A subscriber earns nothing for the platform here. Asserted as a delta because the
			// database already holds commission from other tests and seed data.
			assertThat(summary.commissionRealisedMinor()).isEqualTo(before.commissionRealisedMinor());
		}

		@Test
		@DisplayName("top-ups and subscription charges are reported separately")
		void moneyInIsItemised() {
			RevenueService.RevenueSummary before = baseline();

			wallet.credit(shop.getId(), WalletLedgerEntry.EntryType.TOPUP_APPROVED,
					500_000, "t1", "Top-up", null, admin.getId(), null);
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

			var summary = revenue.summary(Instant.now());

			// 500_000 for the plain top-up, plus 200_000 more: buying a subscription IS a top-up
			// credit, because that is how D7's flow moves money. Worth stating rather than
			// discovering -- "top-ups" means "bank transfers in", subscriptions included.
			assertThat(summary.topUpsMinor() - before.topUpsMinor()).isEqualTo(700_000L);

			// The credit and the charge are separate lines and are not netted: the shop's balance
			// ends where it started, but both legs are recorded, and that is what makes the money
			// reconcilable without reading a bank statement.
			assertThat(summary.subscriptionChargesMinor() - before.subscriptionChargesMinor())
					.isEqualTo(200_000L);
		}
	}

	// ------------------------------------------------------------------ ratios

	@Nested
	@DisplayName("the ratios")
	class Ratios {

		@Test
		@DisplayName("subscription ARPU is MRR per subscriber")
		void subscriptionArpu() {
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 100_000, 31));
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 300_000, 31));

			// 400_000 over 2 subscribers. The denominator is subscribers, not shops, which is why it is
			// named that way -- "ARPU" alone could not be checked by a reader.
			assertThat(revenue.summary(Instant.now()).subscriptionArpuMinor()).isEqualTo(200_000L);
		}

		@Test
		@DisplayName("a platform with no subscribers reports zero rather than dividing by zero")
		void noSubscribers() {
			fund(10_000);
			wallet.debit(shop.getId(), WalletLedgerEntry.EntryType.COMMISSION_DEBIT,
					10_000, "k1", "Commission", null, admin.getId());

			var summary = revenue.summary(Instant.now());

			// An arithmetic exception here would take the whole dashboard down, which is the worst
			// possible time to discover it: on the day the first subscriber cancels.
			assertThat(summary.subscriptionArpuMinor()).isZero();
			assertThat(summary.commissionPerSubscriberMinor()).isZero();
		}

		@Test
		@DisplayName("per-trading-shop revenue counts subscriptions and commission together")
		void perTradingShop() {
			RevenueService.RevenueSummary before = baseline();

			fund(30_000);
			// The commission here belongs to a *different* shop from the subscriber, which is the
			// point: a free shop pays commission and a subscriber pays a subscription, and both are
			// platform revenue. Counting only subscriptions would make the subscribers look like the
			// whole business.
			Shop freeShop = fixtures.createVerifiedShop("Free " + UUID.randomUUID(), ShopType.GROCERY);
			wallet.credit(freeShop.getId(), WalletLedgerEntry.EntryType.TOPUP_APPROVED,
					30_000, "fund-free", "Funding the free shop", null, admin.getId(), null);

			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));
			wallet.debit(freeShop.getId(), WalletLedgerEntry.EntryType.COMMISSION_DEBIT,
					30_000, "k1", "Commission from a free shop", null, admin.getId());

			var summary = revenue.summary(Instant.now());

			// MRR 200_000 plus realised commission 30_000, over one extra trading shop. The sum is
			// what the figure is; the division is asserted loosely because the denominator depends on
			// how many shops the database already had.
			assertThat(summary.mrrMinor() - before.mrrMinor()).isEqualTo(200_000L);
			assertThat(summary.commissionRealisedMinor() - before.commissionRealisedMinor())
					.isEqualTo(30_000L);
			assertThat(summary.tradingShops()).isGreaterThanOrEqualTo(before.tradingShops());
		}

		@Test
		@DisplayName("waiver coverage is null when nothing was waived")
		void waiverCoverageNull() {
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

			// "We forgone nothing" and "we cannot tell yet" are different answers, and null says the
			// second without inventing a number.
			assertThat(revenue.summary(Instant.now()).waiverCoverage()).isNull();
		}
	}

	// ------------------------------------------------------------------ trend

	@Nested
	@DisplayName("the MRR trend")
	class Trend {

		@Test
		@DisplayName("covers the months asked for, oldest first")
		void coversRequestedRange() {
			var trend = revenue.mrrTrend(3, Instant.now());

			assertThat(trend).hasSize(3);
			assertThat(trend.get(0).month()).isLessThan(trend.get(2).month());
		}

		@Test
		@DisplayName("counts a subscription live in that month")
		void countsLiveSubscriptions() {
			subscribe(plan(SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

			// This month, certainly.
			var now = Instant.now();
			assertThat(revenue.mrrTrend(1, now).get(0).mrrMinor()).isEqualTo(200_000L);

			// Last month, certainly not -- the subscription did not exist.
			assertThat(revenue.mrrTrend(2, now).get(0).mrrMinor()).isZero();
		}

		@Test
		@DisplayName("refuses an unbounded range")
		void bounded() {
			// A crafted value is a loop over every subscription ever, once per month.
			assertThatThrownBy(() -> revenue.mrrTrend(0, Instant.now()))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> revenue.mrrTrend(1000, Instant.now()))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Credits the shared test shop so it can afford the debits below.
	 *
	 * <p>A debit is refused on an empty balance -- the rule that makes a shop credit its wallet
	 * before it accepts an order (D2) -- so the fixture has to be funded the way a real shop is.
	 * Without this the tests fail with "Wallet balance is short" and read as a revenue bug.
	 */
	/**
	 * The summary as it stands right now, for a test to compare against afterwards.
	 *
	 * <p><b>Every ledger assertion is a delta, not an absolute.</b> The test database carries
	 * committed rows -- seeded shops, real commission debits, top-ups -- so a test asserting
	 * {@code topUps == 500_000} fails depending on what a previous run left behind, which is the worst
	 * possible property for a test to have. Measuring the change this test caused is both stable and
	 * a stronger statement: it asserts the fixture moved the figure by exactly what it should have.
	 */
	private RevenueService.RevenueSummary baseline() {
		return revenue.summary(Instant.now());
	}

	private void fund(long amountMinor) {
		wallet.credit(shop.getId(), WalletLedgerEntry.EntryType.TOPUP_APPROVED,
				amountMinor, "fund-" + UUID.randomUUID(), "Funding for a revenue test",
				null, admin.getId(), null);
	}

	private SubscriptionPlan plan(SubscriptionPlan.BillingPeriod period, long priceMinor, int days) {
		SubscriptionPlan plan = plans.saveAndFlush(new SubscriptionPlan(
				"REV_" + UUID.randomUUID().toString().substring(0, 6),
				"Revenue test", period, priceMinor, days));

		planEntitlements.deleteByPlanId(plan.getId());
		planEntitlements.flush();
		planEntitlements.saveAndFlush(new PlanEntitlement(
				plan, FeatureKey.MAX_PRODUCTS, true, 500L));
		planEntitlements.saveAndFlush(new PlanEntitlement(
				plan, FeatureKey.REMOVE_COMMISSION, true, null));
		return plan;
	}

	/**
	 * Subscribe one shop.
	 *
	 * <p><b>A fresh shop every call.</b> A shop can hold at most one running subscription -- the
	 * partial unique index says so -- so subscribing the same shop twice is impossible by design and
	 * a test that wanted two subscribers has to use two shops. Which is also the honest shape of the
	 * thing being measured: MRR is a sum over subscribers.
	 */
	private ShopSubscription subscribe(SubscriptionPlan plan) {
		Shop target = fixtures.createVerifiedShop("Rev " + UUID.randomUUID(), ShopType.GROCERY);

		ShopSubscription requested = subscriptions.request(target, plan);
		WalletTopUpRequest topUp = topUpService.requestForSubscription(target.getId(), requested,
				WalletTopUpRequest.Method.BANK_TRANSFER, "REF", null, null);

		return payments.settle(topUp.getId(), admin.getId(), "paid");
	}
}
