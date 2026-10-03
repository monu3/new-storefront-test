package com.storefront.store.dashboard;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;
import com.storefront.store.shop.Shop;

import lombok.RequiredArgsConstructor;

/**
 * The shop dashboard's numbers (Phase 4).
 *
 * <p><b>Aggregated in SQL, not assembled from a query per card.</b> A dashboard is the screen a
 * shop owner opens first and most often; six round trips to render it is six on every visit.
 *
 * <p><b>Revenue counts money the shop actually earned.</b> That means orders that reached at
 * least ACCEPTED — a pending order the shop may never fulfil is not revenue, and presenting it as
 * such ends with an owner surprised by their bank balance.
 *
 * <p><b>No invented figures.</b> Everything here is derived from the shop's own rows, and what the
 * platform does not record is reported as unrecorded. A dashboard showing plausible-looking
 * placeholders is worse than one showing zeroes, because an owner cannot tell them apart at a
 * glance and will make a pricing decision on it.
 */
@Service
@RequiredArgsConstructor
public class ShopDashboardService {

	private final JdbcTemplate jdbc;

	/**
	 * Statuses that count as revenue, as a SQL array literal.
	 *
	 * <p>Cancelled is excluded: the goods never shipped. A refunded order has already been
	 * reversed out of the wallet, so counting it would contradict the ledger.
	 *
	 * <p>Written as a literal array rather than bound through {@code ANY (?)}. Postgres refuses to
	 * infer a type from a JDBC {@code Object[]} parameter — "Cannot cast an instance of
	 * [Ljava.lang.Object; to type Types.ARRAY" — and binding a {@code java.sql.Array} means
	 * managing a connection-local array for four queries.
	 *
	 * <p>A literal is safe here precisely because it is a compile-time constant, not user input.
	 * Anything caller-supplied would still have to be a bound parameter.
	 */
	private static final String EARNING_STATUSES_SQL =
			"ARRAY['ACCEPTED','PROCESSING','SHIPPED','DELIVERED']::text[]";

	/** Six months, matching the chart in the reference design. */
	private static final int MONTHS = 6;

	private static final ZoneId NEPAL = ZoneId.of("Asia/Kathmandu");

	@Transactional(readOnly = true)
	public DashboardSnapshot forShop(Shop shop) {
		UUID shopId = shop.getId();
		YearMonth current = YearMonth.now(NEPAL);
		YearMonth first = current.minusMonths(MONTHS - 1);

		Instant since = startOf(first);
		YearMonth previousMonth = current.minusMonths(1);
		Instant twoMonthsAgo = startOf(previousMonth);

		long revenue = revenueBetween(shopId, since, null);
		long sales = ordersBetween(shopId, since, null);
		long customers = distinctCustomersSince(shopId, since);
		long units = unitsSoldSince(shopId, since);

		long prevRevenue = revenueBetween(shopId, twoMonthsAgo, since);
		long prevSales = ordersBetween(shopId, twoMonthsAgo, since);
		long prevCustomers = distinctCustomersSince(shopId, twoMonthsAgo);
		long prevUnits = unitsSoldSince(shopId, twoMonthsAgo);

		return new DashboardSnapshot(
				shop.getSlug(),
				shop.getName(),
				shop.getType().name(),
				shop.getLogoUrl(),
				shop.isVerified(),
				Money.ofMinor(revenue),
				change(revenue, prevRevenue),
				(int) sales,
				change(sales, prevSales),
				(int) customers,
				change(customers, prevCustomers),
				(int) units,
				change(units, prevUnits),
				(int) products(shopId),
				(int) publishedProducts(shopId),
				(int) draftProducts(shopId),
				lowStockVariants(shopId),
				(int) openOrders(shopId),
				shop.getFollowerCount(),
				revenueByMonth(shopId, first, current),
				salesByMonth(shopId, first, current));
	}

	// ------------------------------------------------------------------ aggregates

	/**
	 * Converts a boundary for JDBC.
	 *
	 * <p>{@link Instant} has no JDBC mapping, and passing one directly fails with "Can't infer the
	 * SQL type to use for an instance of java.time.Instant". {@link OffsetDateTime} is the type the
	 * driver does understand, so every boundary is converted once, here, rather than at each call
	 * site.
	 */
	private static java.time.OffsetDateTime jdbc(Instant instant) {
		return instant.atOffset(java.time.ZoneOffset.UTC);
	}

	/**
	 * Sum of order totals in a window.
	 *
	 * <p>The optional upper bound is built into the statement rather than passed as a null the
	 * driver has to interpret — {@code placed_at < null} is never true, so a null bound would
	 * silently return nothing.
	 */
	private long revenueBetween(UUID shopId, Instant from, Instant to) {
		String sql = """
				SELECT coalesce(sum(total_minor), 0) FROM shop_order
				WHERE shop_id = ? AND status = ANY (%s) AND placed_at >= ?%s
				""".formatted(EARNING_STATUSES_SQL, to == null ? "" : " AND placed_at < ?");
		Object[] args = to == null
				? new Object[] { shopId, jdbc(from) }
				: new Object[] { shopId, jdbc(from), jdbc(to) };
		return n(jdbc.queryForObject(sql, Long.class, args));
	}

	private long ordersBetween(UUID shopId, Instant from, Instant to) {
		String sql = """
				SELECT count(*) FROM shop_order
				WHERE shop_id = ? AND status = ANY (%s) AND placed_at >= ?%s
				""".formatted(EARNING_STATUSES_SQL, to == null ? "" : " AND placed_at < ?");
		Object[] args = to == null
				? new Object[] { shopId, jdbc(from) }
				: new Object[] { shopId, jdbc(from), jdbc(to) };
		return n(jdbc.queryForObject(sql, Long.class, args));
	}

	/**
	 * Distinct buyers since a point in time.
	 *
	 * <p>A guest with no account counts once per distinct email. Counting orders instead would make
	 * "New Customers" track order frequency, which is a different metric wearing this name.
	 */
	private long distinctCustomersSince(UUID shopId, Instant from) {
		return n(jdbc.queryForObject("""
				SELECT count(DISTINCT coalesce(customer_id::text, lower(guest_email)))
				FROM shop_order
				WHERE shop_id = ? AND placed_at >= ?
				  AND (customer_id IS NOT NULL OR guest_email IS NOT NULL)
				""", Long.class, shopId, jdbc(from)));
	}

	private long unitsSoldSince(UUID shopId, Instant from) {
		return n(jdbc.queryForObject("""
				SELECT coalesce(sum(i.quantity), 0)
				FROM order_item i
				JOIN shop_order o ON o.id = i.order_id
				WHERE o.shop_id = ? AND o.status = ANY (%s) AND o.placed_at >= ?
				""".formatted(EARNING_STATUSES_SQL), Long.class, shopId, jdbc(from)));
	}

	private long products(UUID shopId) {
		return n(jdbc.queryForObject(
				"SELECT count(*) FROM product WHERE shop_id = ? AND deleted_at IS NULL",
				Long.class, shopId));
	}

	private long publishedProducts(UUID shopId) {
		return n(jdbc.queryForObject("""
				SELECT count(*) FROM product
				WHERE shop_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
				""", Long.class, shopId));
	}

	private long draftProducts(UUID shopId) {
		return n(jdbc.queryForObject("""
				SELECT count(*) FROM product
				WHERE shop_id = ? AND deleted_at IS NULL AND status = 'DRAFT'
				""", Long.class, shopId));
	}

	/** Variants at or below their own threshold. A shop's action list, not a warning about the platform. */
	private long lowStockVariants(UUID shopId) {
		return n(jdbc.queryForObject("""
				SELECT count(*) FROM inventory i
				JOIN product_variant v ON v.id = i.variant_id
				JOIN product p ON p.id = i.product_id
				WHERE p.shop_id = ? AND p.deleted_at IS NULL
				  AND (i.quantity_on_hand - i.quantity_reserved) <= i.low_stock_threshold
				""", Long.class, shopId));
	}

	private long openOrders(UUID shopId) {
		return n(jdbc.queryForObject("""
				SELECT count(*) FROM shop_order
				WHERE shop_id = ? AND status IN ('PENDING','ACCEPTED','PROCESSING','SHIPPED')
				""", Long.class, shopId));
	}

	private List<MonthRevenue> revenueByMonth(UUID shopId, YearMonth first, YearMonth current) {
		List<MonthRevenue> out = new ArrayList<>();
		for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
			out.add(new MonthRevenue(label(m),
					revenueBetween(shopId, startOf(m), startOf(m.plusMonths(1)))));
		}
		return out;
	}

	private List<MonthSales> salesByMonth(UUID shopId, YearMonth first, YearMonth current) {
		List<MonthSales> out = new ArrayList<>();
		for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
			out.add(new MonthSales(label(m),
					(int) ordersBetween(shopId, startOf(m), startOf(m.plusMonths(1)))));
		}
		return out;
	}

	private static Instant startOf(YearMonth month) {
		return month.atDay(1).atStartOfDay(NEPAL).toInstant();
	}

	private static String label(YearMonth m) {
		return m.getMonth().name().substring(0, 3);
	}

	/**
	 * Percentage change, or null when there is nothing to compare against.
	 *
	 * <p>Null rather than 0% when the previous period was zero. "0%" asserts "no change", which is
	 * a claim; with no previous figure there is nothing to have changed from.
	 */
	private Double change(long current, long previous) {
		if (previous == 0) {
			return null;
		}
		return Math.round(((current - previous) * 10000.0) / previous) / 100.0;
	}

	private static long n(Long boxed) {
		return boxed == null ? 0L : boxed;
	}

	// ------------------------------------------------------------------ types

	public record MonthRevenue(String month, long amountMinor) {
	}

	public record MonthSales(String month, int orders) {
	}

	/**
	 * The dashboard payload.
	 *
	 * <p>No traffic-source figures. Nothing in the platform records where a visitor came from, so
	 * that number would have to be invented — and a donut chart of plausible percentages is a lie
	 * an owner might make a marketing decision on. Phase 4 adds a traffic table before that card
	 * renders numbers.
	 */
	public record DashboardSnapshot(
			String shopSlug,
			String shopName,
			String shopType,
			String logoUrl,
			boolean verified,
			Money totalRevenue,
			Double revenueChangePct,
			int sales,
			Double salesChangePct,
			int newCustomers,
			Double newCustomersChangePct,
			int unitsSold,
			Double unitsSoldChangePct,
			int products,
			int publishedProducts,
			int draftProducts,
			long lowStockVariants,
			int openOrders,
			int followers,
			List<MonthRevenue> revenueByMonth,
			List<MonthSales> salesByMonth) {

		public DashboardView toView() {
			return new DashboardView(
					shopSlug, shopName, shopType, logoUrl, verified,
					totalRevenue.toDisplay(), totalRevenue.minor(), revenueChangePct,
					sales, salesChangePct,
					newCustomers, newCustomersChangePct,
					unitsSold, unitsSoldChangePct,
					products, publishedProducts, draftProducts,
					lowStockVariants, openOrders, followers,
					revenueByMonth, salesByMonth);
		}
	}

	/** What the console receives. Money arrives pre-formatted AND as minor units. */
	public record DashboardView(
			String shopSlug,
			String shopName,
			String shopType,
			String logoUrl,
			boolean verified,
			String totalRevenueDisplay,
			long totalRevenueMinor,
			Double revenueChangePct,
			int sales,
			Double salesChangePct,
			int newCustomers,
			Double newCustomersChangePct,
			int unitsSold,
			Double unitsSoldChangePct,
			int products,
			int publishedProducts,
			int draftProducts,
			long lowStockVariants,
			int openOrders,
			int followers,
			List<MonthRevenue> revenueByMonth,
			List<MonthSales> salesByMonth) {
	}
}
