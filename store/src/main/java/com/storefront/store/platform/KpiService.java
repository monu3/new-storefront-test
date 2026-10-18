package com.storefront.store.platform;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;
import com.storefront.store.shop.Shop;
import com.storefront.store.subscription.RevenueService;

import lombok.RequiredArgsConstructor;

/**
 * The admin KPI dashboard (Phase 7).
 *
 * <p><b>Every figure states its window and its denominator</b>, for the reason {@code RevenueService}
 * does: a dashboard is mostly ratios, and a ratio whose denominator is unstated means something
 * different on every screen it appears on.
 *
 * <p><b>Growth and churn are measured against the previous window of the same length</b>, not against
 * a fixed period. "Up 20% on last month" is a claim about a specific month, and a comparison against
 * an arbitrary baseline is a claim about nothing.
 *
 * <p><b>Churn is activity churn, and that is the important definition.</b> A shop that stopped
 * receiving orders has churned whether or not its subscription lapsed. Counting only subscription
 * lapses would report a marketplace full of dormant shops as healthy, because they are all still
 * paying for a plan they no longer use.
 */
@Service
@RequiredArgsConstructor
public class KpiService {

	private static final int MAX_DAYS = 730;

	private final KpiRepository kpis;
	private final RevenueService revenue;
	private final PlatformSettings settings;

	@Transactional(readOnly = true)
	public KpiDashboard dashboard(int days) {
		if (days < 1 || days > MAX_DAYS) {
			// Bounded: a crafted value is a full-table aggregate once per day of the window.
			throw new IllegalArgumentException(
					"Ask for between 1 and " + MAX_DAYS + " days, not " + days + ".");
		}

		Instant now = Instant.now();
		Instant since = now.minus(days, ChronoUnit.DAYS);
		Instant previousSince = since.minus(days, ChronoUnit.DAYS);

		KpiRepository.Totals totals = kpis.totalsSince(since);
		KpiRepository.WindowTotals previous = kpis.windowTotals(previousSince, since);

		// --- churn, defined as activity
		var retained = kpis.activeBetween(previousSince, since);
		var churned = kpis.churnedBetween(previousSince, since, since);

		int churnedCount = churned.size();
		int retainedBase = Math.max(retained.size(), churnedCount);
		double churnRate = retainedBase == 0 ? 0d : (churnedCount * 100.0) / retainedBase;

		// --- shops
		Map<String, Long> shopsByStatus = new LinkedHashMap<>();
		kpis.shopCountByStatus().forEach(row ->
				shopsByStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue()));
		long totalShops = shopsByStatus.values().stream().mapToLong(Long::longValue).sum();

		// --- people, cross-realm
		Map<String, Long> peopleByRealm = new LinkedHashMap<>();
		kpis.userCountByRealmAndStatus().forEach(row -> {
			// `realm` holds the Keycloak wire value ("ops"), which means nothing to a person
			// reading a dashboard. Map it to the name the realms actually have.
			String key = switch (String.valueOf(row[0])) {
				case "ops" -> "Operators";
				case "customers" -> "Customers";
				default -> String.valueOf(row[0]);
			} + " / " + row[1];
			peopleByRealm.merge(key, ((Number) row[2]).longValue(), Long::sum);
		});

		// --- revenue split by vertical: the D17 payoff
		List<TypeRow> byType = kpis.revenueByType(since).stream()
				.map(row -> new TypeRow(
						row.shopType(),
						label(row.shopType()),
						row.shops(),
						row.activeShops(),
						row.orders(),
						row.gmvMinor(),
						Money.ofMinor(row.gmvMinor()).toDisplay(),
						row.commissionMinor(),
						Money.ofMinor(row.commissionMinor()).toDisplay(),
						// Commission as a share of GMV. The one number that says whether a vertical
						// is worth the platform's attention: a high share means lots of small orders,
						// which is where 5% actually costs a shop something.
						row.gmvMinor() == 0 ? 0d : round((row.commissionMinor() * 10000.0) / row.gmvMinor()) / 100.0))
				.toList();

		// --- the leaderboard
		List<ShopRow> leaders = kpis.byShop(since).stream()
				.filter(row -> row.orders() > 0)
				.sorted((a, b) -> Long.compare(b.gmvMinor(), a.gmvMinor()))
				.limit(20)
				.map(row -> new ShopRow(
						row.shopId(), row.shopType(), row.shopStatus(), row.featured(),
						row.orders(), row.gmvMinor(), Money.ofMinor(row.gmvMinor()).toDisplay(),
						row.commissionMinor()))
				.toList();

		// --- ARPU, per active shop AND per verified shop, because they answer different questions
		long arpuPerActiveShopMinor = totals.activeShops() == 0
				? 0 : totals.gmvMinor() / totals.activeShops();
		long arpuPerVerifiedShopMinor = shopsByStatus.getOrDefault("VERIFIED", 0L) == 0
				? 0 : totals.gmvMinor() / shopsByStatus.get("VERIFIED");

		// --- growth against the previous window of the same length
		// Double, not double: growth() returns null when there is no baseline to compare against,
		// and assigning it to a primitive unboxes that null and throws. The null is the honest
		// answer -- "we have no previous period" -- so it has to survive all the way to the client
		// rather than being destroyed one line after it was computed.
		Double orderGrowth = growth(totals.orders(), previous.orders());
		Double gmvGrowth = growth(totals.gmvMinor(), previous.gmvMinor());

		RevenueService.RevenueSummary money = revenue.summary(now);

		return new KpiDashboard(
				now, days,
				"orders and GMV since " + since + "; MRR is live; growth and churn compare against the "
						+ days + " days before that",
				totals.orders(), previous.orders(), orderGrowth,
				totals.gmvMinor(), Money.ofMinor(totals.gmvMinor()).toDisplay(),
				previous.gmvMinor(), gmvGrowth,
				totals.commissionMinor(), Money.ofMinor(totals.commissionMinor()).toDisplay(),
				totals.waivedMinor(), Money.ofMinor(totals.waivedMinor()).toDisplay(),
				settings.commissionRateBp(), settings.commissionPercent(),
				totals.buyers(),
				totals.activeShops(), totalShops, shopsByStatus.getOrDefault("VERIFIED", 0L),
				churnedCount, churnRate,
				arpuPerActiveShopMinor, Money.ofMinor(arpuPerActiveShopMinor).toDisplay(),
				arpuPerVerifiedShopMinor, Money.ofMinor(arpuPerVerifiedShopMinor).toDisplay(),
				byType, leaders, peopleByRealm,
				money.mrrDisplay(), money.mrrMinor(), money.paidSubscribers());
	}

	/**
	 * Percentage change, or null when there is nothing to compare against.
	 *
	 * <p>Null rather than zero, because "up 0%" from no prior orders is a claim; "we had no previous
	 * period" is the truth, and a dashboard that says 0% is telling an operator growth was zero when
	 * it is unknown.
	 */
	private static Double growth(long now, long before) {
		if (before == 0) {
			return null;
		}
		return Math.round(((now - before) * 10000.0) / before) / 100.0;
	}

	private static double round(double value) {
		return Math.round(value);
	}

	/** "GROCERY" -> "Grocery". Falls back to the raw code rather than blanking an unknown type. */
	private static String label(String code) {
		if (code == null || code.isBlank()) {
			return "Uncategorised";
		}
		String lower = code.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	// ------------------------------------------------------------------ types

	public record KpiDashboard(
			Instant asOf,
			int windowDays,
			String window,

			// --- orders and GMV
			long orders, long previousOrders, Double orderGrowthPercent,
			long gmvMinor, String gmvDisplay,
			long previousGmvMinor, Double gmvGrowthPercent,

			// --- commission
			long commissionMinor, String commissionDisplay,
			long waivedMinor, String waivedDisplay,
			int commissionRateBp, double commissionRatePercent,

			// --- shops and people
			long buyers,
			long activeShops, long totalShops, long verifiedShops,
			int churnedShops, double churnRatePercent,

			// --- ARPU, two denominators because they answer different questions
			long arpuPerActiveShopMinor, String arpuPerActiveShopDisplay,
			long arpuPerVerifiedShopMinor, String arpuPerVerifiedShopDisplay,

			List<TypeRow> revenueByType,
			List<ShopRow> topShops,
			Map<String, Long> peopleByRealmAndStatus,

			// --- the subscription money from Phase 6, so the dashboard is one screen
			String mrrDisplay, long mrrMinor, int paidSubscribers) {
	}

	/** One vertical's row in the revenue split. The D17 payoff, made visible. */
	public record TypeRow(
			String shopType,
			String label,
			long shops,
			long activeShops,
			long orders,
			long gmvMinor,
			String gmvDisplay,
			long commissionMinor,
			String commissionDisplay,
			double commissionSharePercent) {
	}

	public record ShopRow(
			UUID shopId,
			String shopType,
			String shopStatus,
			boolean featured,
			long orders,
			long gmvMinor,
			String gmvDisplay,
			long commissionMinor) {
	}
}