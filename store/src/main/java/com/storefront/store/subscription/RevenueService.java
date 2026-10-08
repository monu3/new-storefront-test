package com.storefront.store.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.wallet.WalletLedgerEntry;

import lombok.RequiredArgsConstructor;

/**
 * Revenue figures for the admin dashboard (Phase 6).
 *
 * <p><b>Every number here states its denominator and its as-of moment.</b> A revenue dashboard is
 * mostly made of ratios, and a ratio without its denominator is a number that means something
 * different on every screen it appears on. MRR, ARPU and "commission this month" are each computed
 * here once, from one definition, so the dashboard and any future report cannot disagree about what
 * they mean.
 *
 * <p><b>Two commission figures, because there are two true ones.</b>
 *
 * <ul>
 *   <li><b>Realised</b> — what the ledger actually debited. Cash that has moved.</li>
 *   <li><b>Waived</b> — the commission that orders <em>did not</em> charge because the shop was
 *       subscribed. Revenue forgone, and the only number that says whether subscriptions are
 *       worth anything.</li>
 * </ul>
 *
 * <p>Reporting only realised commission would make a successful subscription programme look like
 * shrinking revenue, which is exactly the mistake that leads to a platform killing the thing that
 * is retaining its shops.
 */
@Service
@RequiredArgsConstructor
public class RevenueService {

	private final ShopSubscriptionRepository subscriptions;
	private final ShopEntitlementRepository entitlements;
	private final com.storefront.store.wallet.WalletLedgerRepository ledger;
	private final com.storefront.store.order.ShopOrderRepository orders;
	private final ShopRepository shops;

	// ------------------------------------------------------------------ the dashboard

	/**
	 * The whole revenue picture, in one read-consistent pass.
	 *
	 * <p>Read-only and one snapshot, so the components cannot describe two different months.
	 */
	@Transactional(readOnly = true)
	public RevenueSummary summary(Instant now) {

		// Every figure below is "since the start of the current month", computed once. Without this,
		// each call would take its own `Instant.now()` and two panels could disagree by a row that
		// arrived between them -- which reads as a bug in the numbers rather than in the dashboard.
		Instant sinceMonth = now.atZone(java.time.ZoneOffset.UTC)
				.withDayOfMonth(1).toLocalDate()
				.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

		// --- subscriptions
		List<ShopSubscription> active = subscriptions.findByStatusAndExpiresAtAfter(
				ShopSubscription.Status.ACTIVE, now);

		long mrrMinor = 0;
		int paidSubscribers = 0;
		for (ShopSubscription subscription : active) {
			if (!subscription.getPlan().isPaid()) {
				continue;
			}
			// The monthly equivalent, so an annual subscriber contributes a twelfth of their price
			// rather than the whole thing. Counting an annual plan's full price as a month is how a
			// dashboard reports a twelvefold jump every January.
			mrrMinor += subscription.getPlan().monthlyEquivalentMinor();
			paidSubscribers++;
		}

		long waivedCount = entitlements.countGranting(
				FeatureKey.REMOVE_COMMISSION.key(), now);

		// --- shops
		long totalShops = shops.count();
		long commissionFreeShops = waivedCount;
		// Every shop that is verified and not suspended is one that can trade. Counting all shops
		// instead would drag the per-shop average down with shops that have never been approved.
		long tradingShops = shops.countByStatus(Shop.Status.VERIFIED);

		// --- money
		//
		// Gross in, gross out, and the net computed here where the subtraction is visible and named.
		// Commission *earned* is what was charged less what was given back: a reversed commission is
		// not revenue the platform kept, and reporting it as though it were would overstate the month
		// by the size of every cancellation.
		long commissionChargedMinor = ledger(WalletLedgerEntry.EntryType.COMMISSION_DEBIT,
				WalletLedgerEntry.Direction.DEBIT, sinceMonth);

		long commissionReversedMinor = ledger(WalletLedgerEntry.EntryType.COMMISSION_REVERSAL,
				WalletLedgerEntry.Direction.CREDIT, sinceMonth);

		long commissionRealisedMinor = commissionChargedMinor - commissionReversedMinor;

		long topUpsMinor = ledger(WalletLedgerEntry.EntryType.TOPUP_APPROVED,
				WalletLedgerEntry.Direction.CREDIT, sinceMonth);

		long subscriptionChargesMinor = ledger(WalletLedgerEntry.EntryType.SUBSCRIPTION_CHARGE,
				WalletLedgerEntry.Direction.DEBIT, sinceMonth);

		// Waived commission is measured on orders, not the ledger: a waiver leaves no ledger row at
		// all, which is the point of it. The frozen order figures are the only place the forgone
		// amount is written down.
		long commissionWaivedMinor = orders.sumWaivedCommissionMinor(sinceMonth);
		long ordersAccepted = orders.countByStatusAndPlacedAtAfter(ShopOrder.Status.ACCEPTED, sinceMonth);

		// --- ratios
		//
		// Three different denominators on purpose; see the class note. Each is named for what it
		// divides by, because "ARPU" alone cannot be checked by a reader.
		long subscriptionArpuMinor = paidSubscribers == 0 ? 0 : mrrMinor / paidSubscribers;

		// Per *trading* shop, and counting commission as well as subscriptions, because the platform's
		// revenue from a free shop is entirely commission.
		long platformPerShopMinor = tradingShops == 0 ? 0
				: (mrrMinor + commissionRealisedMinor) / tradingShops;

		// Free shops pay commission; subscribers pay subscriptions. The gap between these two is the
		// economic argument for the whole programme.
		long freeShopCommissionMinor = commissionRealisedMinor;
		long perSubscriberCommissionMinor = paidSubscribers == 0 ? 0
				: commissionRealisedMinor / paidSubscribers;

		return new RevenueSummary(
				now,
				Money.ofMinor(mrrMinor).toDisplay(),
				mrrMinor,
				paidSubscribers,
				commissionFreeShops,
				totalShops,
				tradingShops,
				Money.ofMinor(commissionRealisedMinor).toDisplay(),
				commissionRealisedMinor,
				Money.ofMinor(commissionWaivedMinor).toDisplay(),
				commissionWaivedMinor,
				Money.ofMinor(commissionReversedMinor).toDisplay(),
				commissionReversedMinor,
				ordersAccepted,
				Money.ofMinor(topUpsMinor).toDisplay(),
				topUpsMinor,
				Money.ofMinor(subscriptionChargesMinor).toDisplay(),
				subscriptionChargesMinor,
				Money.ofMinor(subscriptionArpuMinor).toDisplay(),
				subscriptionArpuMinor,
				Money.ofMinor(platformPerShopMinor).toDisplay(),
				platformPerShopMinor,
				Money.ofMinor(perSubscriberCommissionMinor).toDisplay(),
				perSubscriberCommissionMinor,
				Money.ofMinor(freeShopCommissionMinor).toDisplay());
	}

	// ------------------------------------------------------------------ MRR trend

	/**
	 * Month-by-month MRR for the last {@code months} months.
	 *
	 * <p><b>Reconstructed from subscription windows, not from a snapshot table.</b> A monthly MRR
	 * table is a denormalisation that must be backfilled, kept in step with every activation and
	 * expiry, and is wrong for every month before it was first written. Deriving each month from the
	 * windows that were live then is more work per query and is right for the whole history.
	 *
	 * <p>A month counts a subscription if it was live for <em>any</em> part of that month. A
	 * mid-month expiry still contributed revenue that month, so counting it as zero would
	 * understate every month with churn in it — which is to say, every interesting month.
	 */
	@Transactional(readOnly = true)
	public List<MonthRevenue> mrrTrend(int months, Instant now) {
		if (months < 1 || months > 36) {
			// Bounded: a crafted value is a 3,600-row loop over every subscription ever.
			throw new IllegalArgumentException("Ask for between 1 and 36 months, not " + months);
		}

		List<MonthRevenue> out = new java.util.ArrayList<>(months);
		for (int back = months - 1; back >= 0; back--) {

			// Whole-month arithmetic happens on the calendar date, not on the Instant.
			//
			// <p>`Instant.plus(1, ChronoUnit.MONTHS)` throws "Unsupported unit: Months": an Instant is
			// a point on the timeline and has no calendar to walk through, so a month is not a fixed
			// number of seconds. Converting first and adding to a LocalDate is correct, and it is why
			// a 31-day month and a 30-day month each contribute exactly one entry.
			java.time.LocalDate month = now.atZone(java.time.ZoneOffset.UTC)
					.withDayOfMonth(1)
					.toLocalDate()
					.minusMonths(back);

			Instant monthStart = month.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
			Instant monthEnd = month.plusMonths(1)
					.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

			// Window [monthStart, monthEnd) against each subscription's own [startedAt, expiresAt).
			// Overlap is what "contributed in this month" means.
			List<UUID> live = subscriptions.shopsWithRunningSubscription(monthStart);

			long total = 0;
			int count = 0;
			for (UUID shopId : live) {
				for (ShopSubscription subscription : subscriptions
						.findByShopIdOrderByCreatedAtDesc(shopId)) {
					if (!subscription.getPlan().isPaid()
							|| subscription.getStartedAt() == null
							|| subscription.getExpiresAt() == null
							|| !subscription.getStartedAt().isBefore(monthEnd)
							|| !subscription.getExpiresAt().isAfter(monthStart)) {
						continue;
					}
					total += subscription.getPlan().monthlyEquivalentMinor();
					count++;
					break;
				}
			}

			String label = month.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));

			out.add(new MonthRevenue(label, monthStart, total,
					Money.ofMinor(total).toDisplay(), count));
		}
		return out;
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Gross money in one ledger entry type, in one direction.
	 *
	 * <p>From the ledger rather than from orders, because the ledger is the record of money that
	 * moved. An order's frozen commission figure is what was <em>supposed</em> to be charged, and a
	 * reversal is a case where the two deliberately differ.
	 */
	private long ledger(WalletLedgerEntry.EntryType type, WalletLedgerEntry.Direction direction,
			Instant since) {
		return ledger.sumByTypeAndDirection(type, direction, since);
	}

	// ------------------------------------------------------------------ types

	/**
	 * The dashboard, in one object.
	 *
	 * <p>Every money field is present twice: {@code *Minor} for arithmetic and {@code *Display} for
	 * rendering. The frontend does not reimplement the formatting, because a rupee symbol placed in
	 * two places is a place they will eventually disagree.
	 */
	public record RevenueSummary(
			Instant asOf,

			// --- subscriptions
			String mrrDisplay,
			long mrrMinor,
			/** Shops with a running paid subscription right now. */
			int paidSubscribers,
			/** Shops currently commission-free, by entitlement. */
			long commissionFreeShops,
			long totalShops,
			/** Verified shops -- the ones that can actually trade. */
			long tradingShops,

			// --- commission
			String commissionRealisedDisplay,
			long commissionRealisedMinor,
			String commissionWaivedDisplay,
			long commissionWaivedMinor,
			String commissionReversedDisplay,
			long commissionReversedMinor,
			/** Accepted orders in the same window -- the denominator for a per-order figure. */
			long ordersAccepted,

			// --- other money in
			// <p>These overlap, and that is not a bug: buying a subscription IS a top-up credit,
			// because that is how D7's manual flow moves money -- the shop transfers to the platform,
			// the platform credits the shop's wallet, and the subscription charge then debits it. So
			// `topUpsMinor` counts bank transfers in, subscriptions included, while
			// `subscriptionChargesMinor` counts what those subscriptions cost. Netting them would hide
			// how much money actually moved, which is the one thing this record exists to say.
			String topUpsDisplay,
			long topUpsMinor,
			String subscriptionChargesDisplay,
			long subscriptionChargesMinor,

			// --- ratios, each with its denominator stated in the field name
			String subscriptionArpuDisplay,
			long subscriptionArpuMinor,
			String platformPerTradingShopDisplay,
			long platformPerTradingShopMinor,
			String commissionPerSubscriberDisplay,
			long commissionPerSubscriberMinor,
			String commissionFromFreeShopsDisplay) {

		/**
		 * The ratio the dashboard leads with: subscriptions earned against commission forgone.
		 *
		 * <p>Greater than one means every subscriber costs more in waived commission than it pays in
		 * subscription revenue — which is the honest answer for a plan priced below 5% of monthly
		 * GMV, and the number that tells a platform whether its commission rate and its subscription
		 * price are consistent with each other.
		 *
		 * <p>Null rather than zero when there is no waived commission, because "we forgone nothing"
		 * and "we cannot tell yet" are different answers.
		 */
		public BigDecimal waiverCoverage() {
			if (commissionWaivedMinor == 0) {
				return null;
			}
			return BigDecimal.valueOf(mrrMinor)
					.divide(BigDecimal.valueOf(commissionWaivedMinor), 4, RoundingMode.HALF_UP);
		}
	}

	/** One month of the MRR trend. */
	public record MonthRevenue(String month, Instant from, long mrrMinor,
			String mrrDisplay, int subscribers) {
	}
}
