package com.storefront.store.platform;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.platform.ReportService.LedgerRow;
import com.storefront.store.platform.ReportService.ProductRow;
import com.storefront.store.platform.ReportService.PayoutRow;
import com.storefront.store.platform.ReportService.SalesRow;
import com.storefront.store.platform.ReportService.ShopRow;
import com.storefront.store.platform.ReportService.TaxRow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.RequiredArgsConstructor;

/**
 * Reports with CSV export, and user management (Phase 7).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')}") (D36).
 *
 * <p><b>Every report has a {@code ?format=csv} sibling, rendered from the same rows.</b> Not a
 * convenience: a separate streaming export is a second implementation of the same numbers, and two
 * implementations that produce different figures is exactly how a report and its export come to
 * disagree — with nobody noticing until an accountant reconciles them.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin reports and users", description = "Reports, CSV export, and cross-realm accounts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReportController {

	private static final int MAX_PAGE_SIZE = 100;

	private final ReportService reports;

	// ------------------------------------------------------------------ reports

	@GetMapping("/reports/{report}")
	@Operation(summary = "A report, as JSON or CSV",
			description = "One of: sales, products, shops, types, taxes, taxes-by-shop, payouts, ledger.")
	public ResponseEntity<?> report(
			@PathVariable String report,
			@RequestParam(defaultValue = "30") @Min(1) int days,
			@RequestParam(defaultValue = "1000") @Min(1) @Max(100_000) int limit,
			@RequestParam(defaultValue = "json") String format) {

		return switch (report.toLowerCase(java.util.Locale.ROOT)) {
			case "sales" -> jsonOrCsv(report, format,
					reports.sales(days, limit),
					List.of("orderNumber", "shopName", "shopType", "customerId", "placedAt",
							"subtotalMinor", "discountMinor", "shippingMinor", "taxMinor",
							"totalMinor", "totalDisplay", "status"),
					row -> List.of(row.orderNumber(), row.shopName(), row.shopType(), row.customerId(),
							row.placedAt(), row.subtotalMinor(), row.discountMinor(), row.shippingMinor(),
							row.taxMinor(), row.totalMinor(), row.totalDisplay(), row.status()));

			case "products" -> jsonOrCsv(report, format,
					reports.products(days, limit),
					List.of("productName", "category", "shopName", "unitsSold", "revenueMinor",
							"revenueDisplay", "orders"),
					row -> List.of(row.productName(), row.category(), row.shopName(), row.unitsSold(),
							row.revenueMinor(), row.revenueDisplay(), row.orders()));

			case "shops" -> jsonOrCsv(report, format,
					reports.shops(days),
					List.of("shopName", "shopType", "status", "featured", "orders", "gmvMinor",
							"gmvDisplay", "commissionMinor"),
					row -> List.of(row.shopName(), row.shopType(), row.status(), row.featured(),
							row.orders(), row.gmvMinor(), row.gmvDisplay(), row.commissionMinor()));

			case "types" -> jsonOrCsv(report, format,
					reports.byType(days),
					List.of("shopType", "label", "shops", "activeShops", "orders", "gmvMinor",
							"gmvDisplay", "commissionMinor", "commissionSharePercent"),
					row -> List.of(row.shopType(), row.label(), row.shops(), row.activeShops(),
							row.orders(), row.gmvMinor(), row.gmvDisplay(), row.commissionMinor(),
							row.commissionSharePercent()));

			case "taxes" -> jsonOrCsv(report, format,
					List.of(reports.taxes(days)),
					List.of("days", "taxMinor", "taxDisplay", "taxableMinor", "taxableDisplay",
							"orders", "exemptOrders"),
					row -> List.of(row.days(), row.taxMinor(), row.taxDisplay(), row.taxableMinor(),
							row.taxableDisplay(), row.orders(), row.exemptOrders()));

			case "taxes-by-shop" -> jsonOrCsv(report, format,
					reports.taxesByShop(days),
					List.of("shopName", "shopType", "orders", "taxMinor", "taxDisplay",
							"taxableMinor", "taxableDisplay", "collectedMinor",
							"collectedDisplay", "exemptOrders", "effectiveRatePercent"),
					row -> List.of(row.shopName(), row.shopType(), row.orders(), row.taxMinor(),
							row.taxDisplay(), row.taxableMinor(), row.taxableDisplay(),
							row.collectedMinor(), row.collectedDisplay(), row.exemptOrders(),
							row.effectiveRatePercent()));

			case "payouts" -> jsonOrCsv(report, format,
					reports.payouts(days),
					List.of("shopName", "shopType", "orders", "gmvMinor", "gmvDisplay",
							"commissionMinor", "commissionDisplay", "toppedUpMinor",
							"toppedUpDisplay", "subscriptionChargesMinor", "balanceMinor",
							"balanceDisplay"),
					row -> List.of(row.shopName(), row.shopType(), row.orders(), row.gmvMinor(),
							row.gmvDisplay(), row.commissionMinor(), row.commissionDisplay(),
							row.toppedUpMinor(), row.toppedUpDisplay(),
							row.subscriptionChargesMinor(), row.balanceMinor(), row.balanceDisplay()));

			case "ledger" -> jsonOrCsv(report, format,
					reports.ledger(days, limit),
					List.of("entryId", "shopId", "entryType", "direction", "amountMinor",
							"amountDisplay", "balanceAfterMinor", "balanceAfterDisplay",
							"reference", "createdAt"),
					row -> List.of(row.entryId(), row.shopId(), row.entryType(), row.direction(),
							row.amountMinor(), row.amountDisplay(), row.balanceAfterMinor(),
							row.balanceAfterDisplay(), row.reference(), row.createdAt()));

			default -> ResponseEntity.badRequest().body(
					"No report called '" + report + "'. Try: sales, products, shops, types, taxes, "
							+ "taxes-by-shop, payouts, ledger.");
		};
	}

	/** JSON, or CSV rendered from exactly these rows. See the class note on why. */
	private <T> ResponseEntity<?> jsonOrCsv(String name, String format, List<T> rows,
			List<String> headers, java.util.function.Function<T, List<Object>> cells) {

		if (!"csv".equalsIgnoreCase(format)) {
			return ResponseEntity.ok(rows);
		}

		String csv = ReportService.toCsv(rows, cells, headers);
		return ResponseEntity.ok()
				// A filename an admin can recognise in their downloads folder, dated.
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + name + "-"
								+ java.time.LocalDate.now() + ".csv\"")
				.contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
				.body(csv);
	}

	// ------------------------------------------------------------------ people

	/**
	 * Every account in both realms.
	 *
	 * <p>{@code realm} narrows rather than being required, which is the point: an admin's questions
	 * cross the realm line constantly, and a screen that shows only one makes them search the other
	 * by hand and guess.
	 */
}
