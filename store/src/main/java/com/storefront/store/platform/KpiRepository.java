package com.storefront.store.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The queries behind the admin KPI dashboard and reports (Phase 7).
 *
 * <p><b>Every figure is computed in SQL, not by loading rows.</b> A dashboard that sums a page of
 * orders is correct until the day there are more orders than fit on a page, and it is wrong silently
 * in the one direction that makes the number look fine.
 *
 * <p><b>The shop-type split is the D17 payoff.</b> Nineteen verticals on one platform, and the
 * question "which of them actually make money" has no answer without grouping by type — revenue
 * summed across verticals is one number that describes no vertical at all.
 *
 * <p><b>Churn is defined as activity, not subscription.</b> A shop that stopped receiving orders has
 * churned even if it is still paying for a subscription, and counting only subscription lapses would
 * report a marketplace full of dormant shops as healthy.
 *
 * <p><b>A {@code @Repository} class over {@code NamedParameterJdbcTemplate}, not a Spring Data
 * interface.</b> Every method here is an aggregate with no entity behind it, so the entity manager
 * has nothing to offer and a projection interface would only be a way of disguising that. The
 * failing alternative is worth recording: declaring the interface as
 * {@code extends Repository<KpiRepository, Void>} is the documented Spring Data shape for a
 * query-only repository, and it did <em>not</em> register — the application failed with
 * {@code NoSuchBeanDefinitionException} naming this type, which points at dependency wiring rather
 * than at the one line that caused it. So the code here is plain JDBC, where the only question is
 * whether the SQL is right.
 */
@Repository
public class KpiRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public KpiRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A window bound with an explicit SQL type.
	 *
	 * <p>The type is named because the driver cannot infer one: {@code setObject(Instant)} raises
	 * {@code "Can't infer the SQL type to use for an instance of java.time.Instant"} at the first
	 * call, which reads as a schema problem rather than a binding one. Every timestamp column in
	 * this schema is {@code timestamptz}, so {@code TIMESTAMP_WITH_TIMEZONE} is the only correct
	 * answer -- and naming it also keeps the comparison in the server's zone rather than the JVM's.
	 */
	private static MapSqlParameterSource window(Instant since) {
		return instant("since", since);
	}

	/** An {@link Instant} in the form the JDBC driver will bind to a {@code timestamptz}. */
	private static java.time.OffsetDateTime utc(Instant value) {
		return java.time.OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
	}

	private static MapSqlParameterSource instant(String name, Instant value) {
		// Two things are needed here and neither is guessable from the signature.
		//
		// <p>The type must be named: `setObject(Instant)` fails with "Can't infer the SQL type".
		// <p>And the value must not be an `Instant`: the driver refuses to cast one to
		// TIMESTAMP_WITH_TIMEZONE. An `OffsetDateTime` at UTC is the same instant in the form the
		// driver accepts, so the comparison still happens in the server's zone rather than the JVM's.
		return new MapSqlParameterSource()
				.addValue(name, utc(value), java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
	}

	// ------------------------------------------------------------------ the dashboard

	/**
	 * Platform-wide totals over a window.
	 *
	 * <p><b>GMV is {@code total_minor}, not subtotal.</b> GMV is what customers spent, which includes
	 * delivery — the platform's cut is a separate question answered by {@code commission_minor},
	 * which excludes shipping and tax by the rule in {@code CommissionCalculator}.
	 *
	 * <p>Cancels are not subtracted: a cancelled order was still merchandise that moved, and
	 * netting it out makes GMV disagree with the order list an admin is reading beside it.
	 */
	public Totals totalsSince(Instant since) {
		return jdbc.queryForObject("""
				SELECT count(id)                                    AS orders,
				       COALESCE(SUM(total_minor), 0)                 AS gmv,
				       COALESCE(SUM(commission_minor), 0)            AS commission,
				       COALESCE(SUM(CASE WHEN commission_waived
				                         THEN commission_minor ELSE 0 END), 0) AS waived,
				       count(DISTINCT shop_id)                       AS active_shops,
				       count(DISTINCT customer_id)                   AS buyers
				FROM shop_order
				WHERE placed_at >= :since
				""", window(since), (rs, n) -> new Totals(
				rs.getLong("orders"), rs.getLong("gmv"), rs.getLong("commission"),
				rs.getLong("waived"), rs.getLong("active_shops"), rs.getLong("buyers")));
	}

	/** The same, for the window <em>before</em> this one, so growth and churn have a baseline. */
	public WindowTotals windowTotals(Instant from, Instant to) {
		return jdbc.queryForObject("""
				SELECT count(id)                              AS orders,
				       COALESCE(SUM(total_minor), 0)           AS gmv,
				       count(DISTINCT shop_id)                 AS active_shops
				FROM shop_order
				WHERE placed_at >= :from AND placed_at < :to
				""", instant("from", from).addValue("to", utc(to), java.sql.Types.TIMESTAMP_WITH_TIMEZONE),
				(rs, n) -> new WindowTotals(
						rs.getLong("orders"), rs.getLong("gmv"), rs.getLong("active_shops")));
	}

	/**
	 * Revenue split by vertical — the D17 payoff.
	 *
	 * <p>{@code LEFT JOIN} on purpose, so a vertical with no orders at all still appears with zero
	 * rather than vanishing. A shop type that has silently disappeared from the revenue table is
	 * exactly the thing an operator needs to notice.
	 */
	public List<TypeRevenue> revenueByType(Instant since) {
		return jdbc.query("""
				SELECT s.shop_type                AS shop_type,
				       count(DISTINCT s.id)       AS shops,
				       count(DISTINCT o.shop_id)  AS active_shops,
				       count(o.id)                 AS orders,
				       COALESCE(SUM(o.total_minor), 0)       AS gmv,
				       COALESCE(SUM(o.commission_minor), 0)  AS commission
				FROM shop s
				LEFT JOIN shop_order o
				       ON o.shop_id = s.id AND o.placed_at >= :since
				WHERE s.status <> 'DRAFT'
				GROUP BY s.shop_type
				ORDER BY gmv DESC, s.shop_type ASC
				""", window(since), (rs, n) -> new TypeRevenue(
				rs.getString("shop_type"),
				rs.getLong("shops"),
				rs.getLong("active_shops"),
				rs.getLong("orders"),
				rs.getLong("gmv"),
				rs.getLong("commission")));
	}

	/** Per-shop figures, for the leaderboard. Zero-order shops included; the service filters them. */
	public List<ShopRevenue> byShop(Instant since) {
		return jdbc.query("""
				SELECT s.id                                   AS shop_id,
				       s.shop_type                            AS shop_type,
				       s.status                               AS shop_status,
				       s.is_featured                          AS featured,
				       count(o.id)                    AS orders,
				       COALESCE(SUM(o.total_minor), 0)       AS gmv,
				       COALESCE(SUM(o.commission_minor), 0)  AS commission
				FROM shop s
				LEFT JOIN shop_order o
				       ON o.shop_id = s.id AND o.placed_at >= :since
				WHERE s.status <> 'DRAFT'
				GROUP BY s.id, s.shop_type, s.status, s.is_featured
				""", window(since), (rs, n) -> new ShopRevenue(
				rs.getObject("shop_id", UUID.class),
				rs.getString("shop_type"),
				rs.getString("shop_status"),
				rs.getBoolean("featured"),
				rs.getLong("orders"),
				rs.getLong("gmv"),
				rs.getLong("commission")));
	}

	/**
	 * Shops that traded in the earlier window and did not trade in this one.
	 *
	 * <p><b>Activity churn, stated in the query.</b> A shop that stopped receiving orders has churned
	 * whether or not its subscription lapsed, and counting only lapses would report a marketplace
	 * full of dormant shops as healthy. The earlier window is what makes it churn rather than merely
	 * "not new".
	 */
	public List<UUID> churnedBetween(Instant earlierFrom, Instant to, Instant since) {
		return jdbc.queryForList("""
				SELECT s.id
				FROM shop s
				WHERE s.status = 'VERIFIED'
				  AND EXISTS (SELECT 1 FROM shop_order o
				              WHERE o.shop_id = s.id
				                AND o.placed_at >= :earlierFrom AND o.placed_at < :to)
				  AND NOT EXISTS (SELECT 1 FROM shop_order o
				                  WHERE o.shop_id = s.id AND o.placed_at >= :since)
				""", instant("earlierFrom", earlierFrom)
				.addValue("to", utc(to), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
				.addValue("since", utc(since), java.sql.Types.TIMESTAMP_WITH_TIMEZONE),
				UUID.class);
	}

	/** Shops still trading, for the retained count churn is measured against. */
	public List<UUID> activeBetween(Instant earlierFrom, Instant to) {
		return jdbc.queryForList("""
				SELECT s.id
				FROM shop s
				WHERE s.status = 'VERIFIED'
				  AND EXISTS (SELECT 1 FROM shop_order o
				              WHERE o.shop_id = s.id
				                AND o.placed_at >= :earlierFrom AND o.placed_at < :to)
				""", instant("earlierFrom", earlierFrom)
				.addValue("to", utc(to), java.sql.Types.TIMESTAMP_WITH_TIMEZONE),
				UUID.class);
	}

	/** Shop counts by status, for the admin's overview tiles. Every status, including empty ones. */
	public List<Object[]> shopCountByStatus() {
		return jdbc.query("""
				SELECT status, count(id) FROM shop GROUP BY status ORDER BY status
				""", new MapSqlParameterSource(), (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2)});
	}

	/** People by realm and status — cross-realm awareness (Phase 7). */
	public List<Object[]> userCountByRealmAndStatus() {
		return jdbc.query("""
				SELECT realm, status, count(id) FROM app_user
				GROUP BY realm, status ORDER BY realm, status
				""", new MapSqlParameterSource(),
				(rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)});
	}

	// ------------------------------------------------------------------ reports
	//
	// No LIMIT except where a limit is the point. A report that pages is not a report: an admin
	// exporting sales for a quarter needs every row, and a silent 1,000-row cap produces a file
	// that looks complete and is not.

	private static final RowMapper<ReportService.SalesRow> SALES = (rs, n) -> new ReportService.SalesRow(
			rs.getString("order_number"),
			rs.getObject("shop_id", UUID.class),
			rs.getString("shop_name"),
			rs.getString("shop_type"),
			rs.getString("customer_id"),
			instant(rs, "placed_at"),
			rs.getLong("subtotal_minor"),
			rs.getLong("discount_minor"),
			rs.getLong("shipping_minor"),
			rs.getLong("tax_minor"),
			rs.getLong("total_minor"),
			ReportService.display(rs.getLong("total_minor")),
			rs.getString("status"));

	public List<ReportService.SalesRow> salesReport(Instant since, int limit) {
		return jdbc.query("""
				SELECT o.order_number, o.shop_id, s.name AS shop_name, s.shop_type,
				       o.customer_id::text AS customer_id, o.placed_at,
				       o.subtotal_minor, o.discount_minor, o.shipping_minor, o.tax_minor,
				       o.total_minor, o.status
				FROM shop_order o
				JOIN shop s ON s.id = o.shop_id
				WHERE o.placed_at >= :since
				ORDER BY o.placed_at DESC
				LIMIT :limit
				""", window(since).addValue("limit", limit), SALES);
	}

	/** Products by units sold. Capped — this is a leaderboard, not an export of the catalogue. */
	public List<ReportService.ProductRow> productReport(Instant since, int limit) {
		return jdbc.query("""
				SELECT p.name AS product_name, p.product_category AS category, s.name AS shop_name,
				       COALESCE(SUM(i.quantity), 0)                          AS units_sold,
				       COALESCE(SUM(i.quantity * i.unit_price_minor), 0)    AS revenue,
				       count(DISTINCT i.order_id)                            AS orders
				FROM order_item i
				JOIN shop_order o ON o.id = i.order_id
				JOIN shop s ON s.id = o.shop_id
				JOIN product p ON p.id = i.product_id
				WHERE o.placed_at >= :since
				GROUP BY p.name, p.product_category, s.name
				ORDER BY revenue DESC
				LIMIT :limit
				""", window(since).addValue("limit", limit), (rs, n) -> new ReportService.ProductRow(
				rs.getString("product_name"), rs.getString("category"), rs.getString("shop_name"),
				rs.getLong("units_sold"), rs.getLong("revenue"),
				ReportService.display(rs.getLong("revenue")), rs.getLong("orders")));
	}

	/** Shops by GMV. Every shop, not just the ones that traded — silence is a finding. */
	public List<ReportService.ShopRow> shopReport(Instant since) {
		return jdbc.query("""
				SELECT s.id AS shop_id, s.name AS shop_name, s.shop_type, s.status,
				       s.is_featured AS featured,
				       count(o.id)               AS orders,
				       COALESCE(SUM(o.total_minor), 0)       AS gmv,
				       COALESCE(SUM(o.commission_minor), 0)  AS commission
				FROM shop s
				LEFT JOIN shop_order o ON o.shop_id = s.id AND o.placed_at >= :since
				WHERE s.status <> 'DRAFT'
				GROUP BY s.id, s.name, s.shop_type, s.status, s.is_featured
				ORDER BY gmv DESC, s.name ASC
				""", window(since), (rs, n) -> new ReportService.ShopRow(
				rs.getObject("shop_id", UUID.class),
				rs.getString("shop_name"), rs.getString("shop_type"), rs.getString("status"),
				rs.getBoolean("featured"),
				rs.getLong("orders"), rs.getLong("gmv"),
				ReportService.display(rs.getLong("gmv")), rs.getLong("commission")));
	}

	/**
	 * Tax collected, from the orders' frozen figures.
	 *
	 * <p>Not recomputed from current rates: a shop that changed its rate mid-window genuinely
	 * collected two different amounts, and applying today's rate to last month's orders is fiction.
	 */
	public TaxTotals taxTotalsSince(Instant since) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(tax_minor), 0)                              AS tax,
				       COALESCE(SUM(subtotal_minor + shipping_minor), 0)       AS taxable,
				       count(id)                                                 AS orders,
				       count(*) FILTER (WHERE tax_minor = 0)                    AS exempt
				FROM shop_order
				WHERE placed_at >= :since
				""", window(since), (rs, n) -> new TaxTotals(
				rs.getLong("tax"), rs.getLong("taxable"), rs.getLong("orders"), rs.getLong("exempt")));
	}

	/**
	 * Per-shop money: what it owes, what it paid, and what is left.
	 *
	 * <p>The balance sums the whole ledger, not just the window — a balance is a position, not a
	 * period, and a windowed balance would show a shop as empty while it held money from last month.
	 */
	public List<PayoutTotals> payoutReport(Instant since) {
		return jdbc.query("""
				SELECT s.id AS shop_id, s.name AS shop_name, s.shop_type,
				       count(DISTINCT o.id)                   AS orders,
				       COALESCE(SUM(o.total_minor), 0)        AS gmv,
				       COALESCE(SUM(o.commission_minor), 0)   AS commission,
				       COALESCE((SELECT SUM(e.amount_minor) FROM wallet_ledger_entry e
				                 WHERE e.shop_id = s.id AND e.created_at >= :since
				                   AND e.entry_type = 'TOPUP_APPROVED'
				                   AND e.direction = 'CREDIT'), 0) AS topped_up,
				       COALESCE((SELECT SUM(e.amount_minor) FROM wallet_ledger_entry e
				                 WHERE e.shop_id = s.id AND e.created_at >= :since
				                   AND e.entry_type = 'SUBSCRIPTION_CHARGE'
				                   AND e.direction = 'DEBIT'), 0)  AS subscription_charges,
				       COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor
				                                  ELSE -e.amount_minor END)
				                 FROM wallet_ledger_entry e
				                 WHERE e.shop_id = s.id), 0)      AS balance
				FROM shop s
				LEFT JOIN shop_order o ON o.shop_id = s.id AND o.placed_at >= :since
				WHERE s.status <> 'DRAFT'
				GROUP BY s.id, s.name, s.shop_type
				ORDER BY commission DESC, s.name ASC
				""", window(since), (rs, n) -> new PayoutTotals(
				rs.getObject("shop_id", UUID.class),
				rs.getString("shop_name"), rs.getString("shop_type"),
				rs.getLong("orders"), rs.getLong("gmv"), rs.getLong("commission"),
				rs.getLong("topped_up"), rs.getLong("subscription_charges"), rs.getLong("balance")));
	}

	/**
	 * Tax per shop, in a window (Phase 8).
	 *
	 * <p><b>From the orders' frozen figures, never recomputed.</b> A shop that changed its rate
	 * mid-window genuinely collected two different amounts, and applying today's rate to last
	 * month's orders is fiction. The column here is what was collected, not what today's rule says
	 * should have been.
	 *
	 * <p><b>Exempt orders are counted, not filtered out.</b> A shop with a high exempt share needs to
	 * see that in its own row; hiding it makes the rate look uniform across the platform when it is
	 * not.
	 */
	public List<ShopTax> shopTaxSince(Instant since) {
		return jdbc.query("""
				SELECT s.id AS shop_id, s.name AS shop_name, s.shop_type,
				       count(o.id)                                                AS orders,
				       COALESCE(SUM(o.tax_minor), 0)                             AS tax,
				       COALESCE(SUM(o.subtotal_minor + o.shipping_minor), 0)     AS taxable,
				       COALESCE(SUM(o.total_minor), 0)                            AS collected,
				       count(*) FILTER (WHERE o.tax_minor = 0)                   AS exempt
				FROM shop s
				JOIN shop_order o ON o.shop_id = s.id AND o.placed_at >= :since
				WHERE s.status <> 'DRAFT'
				GROUP BY s.id, s.name, s.shop_type
				ORDER BY tax DESC, s.name ASC
				""", window(since), (rs, n) -> new ShopTax(
				rs.getObject("shop_id", UUID.class),
				rs.getString("shop_name"),
				rs.getString("shop_type"),
				rs.getLong("orders"),
				rs.getLong("tax"),
				rs.getLong("taxable"),
				rs.getLong("collected"),
				rs.getLong("exempt")));
	}

	/** The ledger itself, for an admin who wants rows rather than a summary. */
	public List<LedgerRow> ledgerReport(Instant since, int limit) {
		return jdbc.query("""
				SELECT e.id::text        AS entry_id,
				       e.shop_id          AS shop_id,
				       e.entry_type       AS entry_type,
				       e.direction        AS direction,
				       e.amount_minor     AS amount,
				       e.balance_after_minor AS balance_after,
				       COALESCE(e.description, '') AS reference,
				       e.created_at       AS created_at
				FROM wallet_ledger_entry e
				WHERE e.created_at >= :since
				ORDER BY e.created_at DESC
				LIMIT :limit
				""", window(since).addValue("limit", limit), (rs, n) -> new LedgerRow(
				rs.getString("entry_id"), rs.getObject("shop_id", UUID.class),
				rs.getString("entry_type"), rs.getString("direction"),
				rs.getLong("amount"), rs.getLong("balance_after"),
				rs.getString("reference"), instant(rs, "created_at")));
	}

	/**
	 * A {@code timestamptz} column as an {@link Instant}.
	 *
	 * <p>Read through {@code OffsetDateTime}, not {@code getObject(…, Instant.class)}: the driver
	 * does not support that conversion and fails with "conversion to class java.time.Instant from
	 * timestamptz not supported", which reads as a schema mismatch rather than a driver limitation.
	 * Going via the offset and discarding it is exact — an Instant is the same point either way.
	 */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		java.time.OffsetDateTime value =
				rs.getObject(column, java.time.OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	// ------------------------------------------------------------------ types

	/** One shop's contribution over a window. */
	public record ShopRevenue(
			UUID shopId, String shopType, String shopStatus, boolean featured,
			long orders, long gmvMinor, long commissionMinor) {
	}

	/** One vertical's totals, for the revenue-split table. */
	public record TypeRevenue(
			String shopType, long shops, long activeShops,
			long orders, long gmvMinor, long commissionMinor) {
	}

	public record Totals(
			long orders, long gmvMinor, long commissionMinor,
			long waivedMinor, long activeShops, long buyers) {
	}

	public record WindowTotals(long orders, long gmvMinor, long activeShops) {
	}

	public record TaxTotals(long taxMinor, long taxableMinor, long orders, long exemptOrders) {
	}

	/** One shop's tax position in a window. */
	public record ShopTax(
			UUID shopId, String shopName, String shopType,
			long orders, long taxMinor, long taxableMinor, long collectedMinor, long exemptOrders) {
	}

	public record PayoutTotals(
			UUID shopId, String shopName, String shopType,
			long orders, long gmvMinor, long commissionMinor,
			long toppedUpMinor, long subscriptionChargesMinor, long balanceMinor) {
	}

	public record LedgerRow(
			String entryId, UUID shopId, String entryType, String direction,
			long amountMinor, long balanceAfterMinor, String reference, Instant createdAt) {
	}
}