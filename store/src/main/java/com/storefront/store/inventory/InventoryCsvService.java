package com.storefront.store.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ProductVariantRepository;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CSV import and export for stock.
 *
 * <p><b>Every row is reported on, and a bad row never aborts the rest.</b> An owner importing 500
 * SKUs and having the whole thing rejected on row 137 because one SKU is duplicated would end up
 * doing it by hand. So each row is applied independently and the response says, per row, what
 * happened — which is also the only way they can fix the 3 that failed.
 *
 * <p><b>Stock is never absolute on import unless asked for explicitly.</b> The default is
 * "<em>add</em> these units", because a stocktake file is nearly always a top-up delivery note and
 * silently overwriting a count is how a shop loses track of what it has. Absolute is available
 * because a stocktake genuinely needs it, but it must be chosen.
 *
 * <p><b>Nothing creates products.</b> A CSV row that names an unknown SKU is reported as an
 * error, not used to invent a catalogue. Bulk-creating products from a spreadsheet is a different
 * and far more dangerous operation, with validation the owner has not seen.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryCsvService {

	/** A spreadsheet with more rows than this is a mistake or an attack. */
	private static final int MAX_ROWS = 10_000;

	/**
	 * Column headers, fixed and documented.
	 *
	 * <p>Accepted by name so a shop owner can reorder columns in Excel without breaking the
	 * import — the single most common way a CSV feature fails in practice.
	 */
	private static final List<String> COLUMNS = List.of(
			"sku", "variant_name", "product_name", "category", "quantity", "reserved", "operation",
			"price");

	private final ProductRepository products;
	private final ProductVariantRepository variants;
	private final ProductService productService;
	private final InventoryService inventory;

	// ------------------------------------------------------------------ export

	/**
	 * Exports every variant with its stock, ready to edit in a spreadsheet and re-import.
	 *
	 * <p>{@code quantity} is <b>on hand</b>, because that is what a stocktake counts: the units
	 * physically on the shelf. Exporting "available" instead was tried first and is wrong — a
	 * round trip through the file then lost the reservation, because {@code SET} writes on-hand and
	 * the exported figure had the reserved part subtracted. Re-importing would silently understate
	 * stock by exactly the number of units promised to customers, every time.
	 *
	 * <p>{@code reserved} is exported alongside, read-only, so the owner can see what is already
	 * committed and understand why "I have 20 on the shelf" is not 20 sellable. It is ignored on
	 * import: a spreadsheet column an owner can edit but that has no effect is a trap.
	 *
	 * <p>Includes drafts and archived products. An export an owner then cannot re-import is worse
	 * than no export, because they will edit it expecting it to work.
	 */
	@Transactional(readOnly = true)
	public String exportCsv(Shop shop) {
		List<UUID> productIds = products.findByShopIdAndDeletedAtIsNull(shop.getId(),
				org.springframework.data.domain.Pageable.unpaged()).getContent().stream()
				.map(Product::getId).toList();

		StringBuilder csv = new StringBuilder();
		csv.append(String.join(",", COLUMNS)).append('\n');

		for (UUID productId : productIds) {
			Product product = products.findById(productId).orElse(null);
			if (product == null) {
				continue;
			}
			for (ProductVariant variant : variants
					.findByProductIdOrderBySortOrderAscNameAsc(productId)) {
				Inventory row = inventory.find(productId, variant.getId()).orElse(null);
				int onHand = row == null ? 0 : row.getQuantityOnHand();
				int reserved = row == null ? 0 : row.getQuantityReserved();

				csv.append(csvCell(variant.getSku())).append(',')
						.append(csvCell(variant.getName())).append(',')
						.append(csvCell(product.getName())).append(',')
						.append(product.getProductCategory().name()).append(',')
						.append(onHand).append(',')
						// Informational: ignored on import, because a column an owner can edit that
						// has no effect is a trap.
						.append(reserved).append(',')
						.append("SET").append(',')
						.append(variant.getPriceMinor())
						.append('\n');
			}
		}
		return csv.toString();
	}

	// ------------------------------------------------------------------ import

	/**
	 * Applies a CSV.
	 *
	 * @param operation {@code SET} to overwrite the count, {@code ADD} to add to it. Defaults to
	 *                  {@code ADD} — see the class note on why that is the default.
	 */
	@Transactional
	public ImportResult importCsv(Shop shop, String csv, Operation operation) {

		List<RowResult> results = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		List<Map<String, String>> parsed = parse(csv, errors);

		if (parsed.isEmpty() && errors.isEmpty()) {
			errors.add("The file had a header row but no data rows");
		}
		if (parsed.size() > MAX_ROWS) {
			errors.add("The file has " + parsed.size() + " rows; the limit is " + MAX_ROWS);
			return new ImportResult(0, results, errors);
		}

		int applied = 0;
		int lineNumber = 1; // header

		for (Map<String, String> row : parsed) {
			lineNumber++;
			try {
				RowResult result = applyRow(shop, row, operation);
				results.add(result);
				if (result.ok()) {
					applied++;
				}
			} catch (RuntimeException e) {
				// One bad row must not abandon the other 499.
				String reason = e.getMessage() == null ? "Unknown error" : e.getMessage();
				results.add(new RowResult(lineNumber, row.get("sku"),
						row.get("variant_name"), false, reason));
			}
		}

		log.info("CSV import for shop {}: {} applied of {} rows", shop.getId(), applied, parsed.size());
		return new ImportResult(applied, results, errors);
	}

	private RowResult applyRow(Shop shop, Map<String, String> row, Operation defaultOperation) {
		String sku = blankToNull(row.get("sku"));
		String variantName = blankToNull(row.get("variant_name"));

		if (sku == null && variantName == null) {
			throw new IllegalArgumentException("Needs at least a sku or a variant name to identify the row");
		}

		String what = sku != null ? "sku '" + sku + "'" : "'" + variantName + "'";

		// A SKU is unique per product, not per shop, so two products can carry the same code. The
		// query returns up to two so ambiguity can be reported rather than guessed at: taking the
		// first would mark the row applied while quietly leaving the other variant untouched, and
		// the owner would trust the summary.
		java.util.List<ProductVariant> matches = sku != null
				? variants.findBySkuInShop(shop.getId(), sku)
				: variants.findByNameInShop(shop.getId(), variantName);

		if (matches.isEmpty()) {
			// Not "create it": bulk-creating a catalogue from a spreadsheet is a different and much
			// more dangerous operation, with validation the owner has never seen.
			throw new IllegalArgumentException("No variant in this shop matches " + what);
		}
		if (matches.size() > 1) {
			throw new IllegalArgumentException(what + " matches " + matches.size()
					+ " variants in this shop. A SKU must be unique; rename one before importing.");
		}
		ProductVariant variant = matches.get(0);

		int quantity = parseInt(row.get("quantity"), "quantity");
		if (quantity < 0) {
			throw new IllegalArgumentException("quantity cannot be negative");
		}

		Operation op = parseOperation(row.get("operation"), defaultOperation);

		Inventory stock = inventory.find(variant.getProductId(), variant.getId()).orElse(null);
		int onHand = stock == null ? 0 : stock.getQuantityOnHand();
		int target = op == Operation.ADD ? onHand + quantity : quantity;

		if (stock != null && op == Operation.SET && target < stock.getQuantityReserved()) {
			throw new IllegalArgumentException(
					"Cannot set " + target + ": " + stock.getQuantityReserved()
							+ " units are already reserved by open orders");
		}

		if (op == Operation.ADD && quantity > 0) {
			inventory.restock(variant.getProductId(), variant.getId(), quantity, null,
					"CSV import");
		} else if (op == Operation.SET) {
			inventory.setOnHand(variant.getProductId(), variant.getId(), target, null,
					"CSV import (set)");
		}

		return new RowResult(0, sku, variant.getName(), true,
				op + " " + quantity + " -> " + target + " on hand");
	}

	// ------------------------------------------------------------------ parsing

	/**
	 * Reads the CSV into maps keyed by column name.
	 *
	 * <p><b>The header row is optional.</b> It is detected by the presence of a {@code quantity}
	 * column rather than assumed to be row one, because a spreadsheet written from scratch often
	 * begins with data and a header-less file that looks correct to its author must not be
	 * rejected with "no quantity column". Without a header the columns are read in the same order
	 * {@link #exportCsv} writes them.
	 *
	 * <p>A real RFC 4180 parser, not {@code split(",")}. Quoted fields containing commas are
	 * routine — a product called "Rice, 5kg" — and splitting on the comma would silently shift
	 * every later column, corrupting a whole import.
	 */
	private List<Map<String, String>> parse(String csv, List<String> errors) {
		List<List<String>> records = new ArrayList<>();
		List<String> current = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean inQuotes = false;

		for (int i = 0; i < csv.length(); i++) {
			char c = csv.charAt(i);

			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
						field.append('"');   // an escaped quote
						i++;
					} else {
						inQuotes = false;
					}
				} else {
					field.append(c);
				}
			} else if (c == '"') {
				inQuotes = true;
			} else if (c == ',') {
				current.add(field.toString());
				field.setLength(0);
			} else if (c == '\n') {
				current.add(field.toString());
				field.setLength(0);
				records.add(current);
				current = new ArrayList<>();
			} else if (c != '\r') {
				field.append(c);
			}
		}
		if (field.length() > 0 || !current.isEmpty()) {
			current.add(field.toString());
			records.add(current);
		}

		if (records.isEmpty()) {
			return List.of();
		}

		// A header row is optional. Detected by looking for "quantity" rather than by row
		// position, because a shop owner writing a spreadsheet from scratch often types their data
		// starting in the first row and gets a confusing "no quantity column" for a file that
		// looks perfectly correct to them. A missing header is not an error; it just means the
		// columns are in the export's default order.
		List<String> first = records.get(0).stream()
				.map(h -> h.trim().toLowerCase(java.util.Locale.ROOT)).toList();

		List<String> header;
		if (first.contains("quantity")) {
			header = first;
			records.remove(0);
		} else {
			header = COLUMNS.stream().map(c -> c.toLowerCase(java.util.Locale.ROOT)).toList();
		}

		List<Map<String, String>> rows = new ArrayList<>();
		for (List<String> record : records) {
			if (record.isEmpty() || (record.size() == 1 && record.get(0).isBlank())) {
				continue;   // blank line, not a row
			}
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.size(); i++) {
				row.put(header.get(i), i < record.size() ? record.get(i).trim() : "");
			}
			rows.add(row);
		}
		return rows;
	}

	private static int parseInt(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is empty");
		}
		try {
			return Integer.parseInt(value.replace(",", "").trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(field + " is not a number: '" + value + "'");
		}
	}

	/** Parses a non-negative integer, used for the informational reserved column. */
	private static int parsePositive(String value, String field) {
		int parsed = parseInt(value, field);
		if (parsed < 0) {
			throw new IllegalArgumentException(field + " cannot be negative");
		}
		return parsed;
	}

	private static Operation parseOperation(String value, Operation fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
			case "SET", "ABSOLUTE", "STOCKTAKE" -> Operation.SET;
			case "ADD", "INCREMENT", "RESTOCK" -> Operation.ADD;
			default -> throw new IllegalArgumentException(
					"operation must be SET or ADD, not '" + value + "'");
		};
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/**
	 * Quotes and escapes one CSV field.
	 *
	 * <p>Also neutralises a leading {@code =}, {@code +}, {@code -} or {@code @}. Those are
	 * formula prefixes, and a product legitimately named "=SUM(A1:A9)" or a reference code
	 * "+977..." would be executed as a formula by Excel and Sheets when the owner opens the
	 * export. That is CSV injection, and quoting alone does not prevent it.
	 */
	private static String csvCell(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String safe = value;
		if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
			safe = "'" + safe;
		}
		if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
			return '"' + safe.replace("\"", "\"\"") + '"';
		}
		return safe;
	}

	// ------------------------------------------------------------------ types

	/**
	 * How an imported quantity changes the count.
	 *
	 * <p>{@link #ADD} is the default because a spreadsheet handed to a shop is almost always a
	 * supplier's delivery note, and overwriting stock from one destroys the count.
	 */
	public enum Operation {
		SET,
		ADD
	}

	/** What happened to one row. Reported for every row, successful or not. */
	public record RowResult(int line, String sku, String variantName, boolean ok, String message) {
	}

	/**
	 * @param applied how many rows changed stock
	 * @param rows    one entry per data row, so the owner can fix the failures
	 * @param errors  whole-file problems: a bad header, too many rows. Distinct from row errors,
	 *                because a file-level problem means nothing was imported at all.
	 */
	public record ImportResult(int applied, List<RowResult> rows, List<String> errors) {

		public int failed() {
			return (int) rows.stream().filter(r -> !r.ok()).count();
		}

		public boolean isClean() {
			return errors.isEmpty() && failed() == 0;
		}
	}
}
