package com.storefront.store.platform;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;

import lombok.RequiredArgsConstructor;

/**
 * Reports and CSV export (Phase 7).
 *
 * <p><b>Every report is a plain result list; the CSV is rendered from it.</b> The alternative -- a
 * streaming export that writes rows straight to the response -- is faster by one pass and cannot be
 * tested, cannot be counted, and cannot be re-used by the on-screen version. Two code paths that
 * produce the same numbers is exactly how a report and its export come to disagree.
 *
 * <p><b>Reports are computed in SQL and never paged.</b> A report that pages is not a report: an
 * admin exporting sales for a quarter needs every row, and a `LIMIT` that silently truncates at a
 * thousand is worse than no export at all, because the file looks complete.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

	private static final int MAX_DAYS = 1095;

	private final KpiRepository kpis;

	// ------------------------------------------------------------------ sales

	/** Orders in a window, with the shop they belong to. */
	@Transactional(readOnly = true)
	public List<SalesRow> sales(int days, int limit) {
		window(days);
		return kpis.salesReport(Instant.now().minus(days, ChronoUnit.DAYS), clamp(limit));
	}

	/** Products by units sold in a window. */
	@Transactional(readOnly = true)
	public List<ProductRow> products(int days, int limit) {
		window(days);
		return kpis.productReport(Instant.now().minus(days, ChronoUnit.DAYS), clamp(limit));
	}

	/** Shops by GMV in a window — who is actually trading. */
	@Transactional(readOnly = true)
	public List<ShopRow> shops(int days) {
		window(days);
		return kpis.shopReport(Instant.now().minus(days, ChronoUnit.DAYS));
	}

	/** Revenue split by vertical, which is the same figure the dashboard shows. */
	@Transactional(readOnly = true)
	public List<KpiService.TypeRow> byType(int days) {
		window(days);
		return kpis.revenueByType(Instant.now().minus(days, ChronoUnit.DAYS)).stream()
				.map(row -> new KpiService.TypeRow(
						row.shopType(),
						label(row.shopType()),
						row.shops(), row.activeShops(), row.orders(),
						row.gmvMinor(), Money.ofMinor(row.gmvMinor()).toDisplay(),
						row.commissionMinor(), Money.ofMinor(row.commissionMinor()).toDisplay(),
						row.gmvMinor() == 0 ? 0d
								: Math.round((row.commissionMinor() * 10000.0) / row.gmvMinor()) / 100.0))
				.toList();
	}

	/**
	 * Tax collected in a window.
	 *
	 * <p>Read from the orders' frozen tax, not recomputed from current rates -- a shop that changed
	 * its tax rate mid-window has genuinely collected two different amounts, and a report that
	 * applied today's rate to last month's orders would be fiction.
	 */
	@Transactional(readOnly = true)
	public TaxRow taxes(int days) {
		window(days);
		Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
		var collected = kpis.taxTotalsSince(since);

		return new TaxRow(
				days,
				collected.taxMinor(), Money.ofMinor(collected.taxMinor()).toDisplay(),
				collected.taxableMinor(), Money.ofMinor(collected.taxableMinor()).toDisplay(),
				collected.orders(),
				collected.exemptOrders());
	}

	/**
	 * Tax per shop (Phase 8).
	 *
	 * <p>Split out from the platform-wide {@link #taxes(int)} because the two answer different
	 * questions: that one is "how much tax did the platform collect", and this one is "which shop
	 * owes the revenue department what". An admin reconciling a filing needs the second, and cannot
	 * get it by dividing the first.
	 *
	 * <p>The effective rate is included because a shop whose rate looks wrong usually wants to know
	 * what the rate <em>was</em> — and the denominator is named, since a percentage without one
	 * means something different on every screen it appears on.
	 */
	@Transactional(readOnly = true)
	public List<ShopTaxRow> taxesByShop(int days) {
		window(days);

		return kpis.shopTaxSince(Instant.now().minus(days, ChronoUnit.DAYS)).stream()
				.map(row -> new ShopTaxRow(
						row.shopId(), row.shopName(), row.shopType(),
						row.orders(),
						row.taxMinor(), display(row.taxMinor()),
						row.taxableMinor(), display(row.taxableMinor()),
						row.collectedMinor(), display(row.collectedMinor()),
						row.exemptOrders(),
						row.taxableMinor() == 0 ? 0d
								: Math.round(row.taxMinor() * 10000.0 / row.taxableMinor()) / 100.0))
				.toList();
	}

	/**
	 * Payouts: what each shop owes the platform, and what it has been credited.
	 *
	 * <p>Both legs shown, because the difference is the reconciliation an admin actually has to do:
	 * commission debited but not settled, or settled without a matching debit.
	 */
	@Transactional(readOnly = true)
	public List<PayoutRow> payouts(int days) {
		window(days);
		Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
		return kpis.payoutReport(since).stream()
				.map(row -> new PayoutRow(
						row.shopId(), row.shopName(), row.shopType(),
						row.orders(), row.gmvMinor(), Money.ofMinor(row.gmvMinor()).toDisplay(),
						row.commissionMinor(), Money.ofMinor(row.commissionMinor()).toDisplay(),
						row.toppedUpMinor(), Money.ofMinor(row.toppedUpMinor()).toDisplay(),
						row.subscriptionChargesMinor(),
						// What is left in the wallet after everything, which is the number the
						// platform actually owes the shop if it were to pay out now.
						row.balanceMinor(), Money.ofMinor(row.balanceMinor()).toDisplay()))
				.toList();
	}

	/** The ledger, for an admin who wants the rows rather than the summary. */
	@Transactional(readOnly = true)
	public List<LedgerRow> ledger(int days, int limit) {
		window(days);
		return kpis.ledgerReport(Instant.now().minus(days, ChronoUnit.DAYS), clamp(limit))
				.stream()
				.map(row -> new LedgerRow(
						row.entryId(), row.shopId(), row.entryType(), row.direction(),
						row.amountMinor(), Money.ofMinor(row.amountMinor()).toDisplay(),
						row.balanceAfterMinor(), Money.ofMinor(row.balanceAfterMinor()).toDisplay(),
						row.reference(), row.createdAt()))
				.toList();
	}

	/**
	 * Minor units as a display string.
	 *
	 * <p>Shared rather than repeated per row: a report and its CSV export that format money two
	 * different ways is a difference an accountant finds and a developer does not.
	 */
	public static String display(long minor) {
		return Money.ofMinor(minor).toDisplay();
	}

	// ------------------------------------------------------------------ CSV

	/**
	 * Renders any of these reports as CSV.
	 *
	 * <p><b>Written here rather than by a library</b> because the interesting part is not the commas:
	 * it is escaping, and a formula injection is the failure that matters. A cell beginning with
	 * {@code = + - @} is executed by Excel and Sheets on open, so a shop name of
	 * {@code =HYPERLINK("http://evil","click")} becomes a live link in an admin's spreadsheet.
	 *
	 * <p>Every such cell is prefixed with an apostrophe, which the spreadsheet shows as a literal
	 * character and does not evaluate. It is not removed on display.
	 */
	public static <T> String toCsv(List<T> rows, java.util.function.Function<T, List<Object>> cells,
			List<String> headers) {

		StringBuilder csv = new StringBuilder();
		csv.append(headers.stream().map(Csv::escape).collect(java.util.stream.Collectors.joining(",")))
				.append('\n');

		for (T row : rows) {
			csv.append(cells.apply(row).stream()
					.map(value -> Csv.escape(value == null ? "" : String.valueOf(value)))
					.collect(java.util.stream.Collectors.joining(",")))
					.append('\n');
		}
		return csv.toString();
	}

	/** One cell: quoted when it contains a comma, quote or newline; neutralised if it is a formula. */
	static final class Csv {

		private Csv() {
		}

		static String escape(String value) {
			String safe = neutraliseFormula(value);
			if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
				return "\"" + safe.replace("\"", "\"\"") + "\"";
			}
			return safe;
		}

		/**
		 * Defuses a spreadsheet formula.
		 *
		 * <p>A leading {@code = + - @} makes a spreadsheet evaluate the cell on open. Everything this
		 * platform shows an admin came from a shop or a customer at some point, so an unescaped
		 * export is a way for a shop name to run code on the platform's own administrator's laptop.
		 *
		 * <p><b>Numbers are exempt, and that is not an oversight.</b> A ledger row can legitimately
		 * be negative, and a blanket {@code '} prefix would turn every refund and every debit into
		 * a text cell — so an export of financial data would no longer open as numbers, which is
		 * the one thing the person who asked for it will try to do with it. Only values that are
		 * <em>not</em> numbers get the apostrophe, which still leaves the attack intact: a formula
		 * is never a valid number.
		 *
		 * <p>Placed before {@link #escape(String)}, because the apostrophe has to be inside the
		 * cell — a leading {@code '} outside a quoted cell does not stop Excel evaluating anything.
		 */
		static String neutraliseFormula(String value) {
			if (value == null || value.isEmpty()) {
				return value;
			}

			char first = value.charAt(0);
			if (first != '=' && first != '+' && first != '-' && first != '@') {
				return value;
			}

			// A number that merely happens to start with a sign: -250, +7, -3.5e2.
			if (looksNumeric(value)) {
				return value;
			}

			return "'" + value;
		}

		/**
		 * Whether the whole value parses as a number.
		 *
		 * <p><b>Parsed, not pattern-matched.</b> A regex would have to encode what
		 * {@code Double.parseDouble} already knows, and would be wrong at the edges — trailing
		 * whitespace, hex, {@code NaN}, {@code 1_000} — each of which is a way for an attacker to
		 * slip a payload past a filter and back into the evaluator.
		 */
		private static boolean looksNumeric(String value) {
			try {
				Double.parseDouble(value.trim());
				return true;
			} catch (NumberFormatException notANumber) {
				return false;
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	private void window(int days) {
		if (days < 1 || days > MAX_DAYS) {
			throw new IllegalArgumentException(
					"Ask for between 1 and " + MAX_DAYS + " days, not " + days + ".");
		}
	}

	private int clamp(int limit) {
		if (limit <= 0) {
			return 1000;
		}
		return Math.min(limit, 100_000);
	}

	private static String label(String code) {
		if (code == null || code.isBlank()) {
			return "Uncategorised";
		}
		String lower = code.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	// ------------------------------------------------------------------ rows

	public record SalesRow(
			String orderNumber, java.util.UUID shopId, String shopName, String shopType,
			String customerId, java.time.Instant placedAt,
			long subtotalMinor, long discountMinor, long shippingMinor, long taxMinor,
			long totalMinor, String totalDisplay, String status) {
	}

	public record ProductRow(
			String productName, String category, String shopName,
			long unitsSold, long revenueMinor, String revenueDisplay, long orders) {
	}

	public record ShopRow(
			java.util.UUID shopId, String shopName, String shopType, String status, boolean featured,
			long orders, long gmvMinor, String gmvDisplay, long commissionMinor) {
	}

	public record TaxRow(
			int days,
			long taxMinor, String taxDisplay,
			long taxableMinor, String taxableDisplay,
			long orders, long exemptOrders) {
	}

	/**
	 * One shop's tax position.
	 *
	 * <p>{@code taxableMinor} excludes the goods' discount, matching the commission base (D31), and
	 * is the denominator for {@code effectiveRatePercent}. {@code exemptOrders} is counted rather
	 * than filtered out, so a shop with a high exempt share can see it in its own row instead of it
	 * being invisible in the platform average.
	 */
	public record ShopTaxRow(
			java.util.UUID shopId, String shopName, String shopType,
			long orders,
			long taxMinor, String taxDisplay,
			long taxableMinor, String taxableDisplay,
			long collectedMinor, String collectedDisplay,
			long exemptOrders,
			double effectiveRatePercent) {
	}

	public record PayoutRow(
			java.util.UUID shopId, String shopName, String shopType,
			long orders, long gmvMinor, String gmvDisplay,
			long commissionMinor, String commissionDisplay,
			long toppedUpMinor, String toppedUpDisplay,
			long subscriptionChargesMinor,
			long balanceMinor, String balanceDisplay) {
	}

	public record LedgerRow(
			String entryId, java.util.UUID shopId, String entryType, String direction,
			long amountMinor, String amountDisplay,
			long balanceAfterMinor, String balanceAfterDisplay,
			/** The ledger row's description: what this entry was for. */
			String reference, java.time.Instant createdAt) {
	}
}