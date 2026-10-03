package com.storefront.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.inventory.InventoryCsvService.ImportResult;
import com.storefront.store.inventory.InventoryCsvService.Operation;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * CSV import and export.
 *
 * <p>Three properties decide whether this feature is usable at all:
 *
 * <ul>
 *   <li><b>A bad row never aborts the rest.</b> An owner importing 500 SKUs who has the whole
 *       file rejected on row 137 ends up doing it by hand.</li>
 *   <li><b>Import defaults to adding, not overwriting.</b> A spreadsheet handed to a shop is
 *       nearly always a supplier's delivery note; silently replacing a stock count from one
 *       destroys the count.</li>
 *   <li><b>Formula-looking fields are neutralised.</b> A product legitimately named
 *       {@code =SUM(A1:A9)} must not execute when the owner opens the export in Excel.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class InventoryCsvServiceIT {

	@Autowired
	private InventoryCsvService csv;

	@Autowired
	private InventoryService inventory;

	@Autowired
	private com.storefront.store.catalog.ProductService products;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private ProductVariant rice;
	private ProductVariant lentils;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"CSV Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);

		rice = fixtures.createProductWithSku(shop, "Rice", ProductCategory.RICE, 45_000L, "RICE-5");
		lentils = fixtures.createProductWithSku(shop, "Lentils", ProductCategory.LENTIL, 60_000L,
				"LENTIL-1");
	}

	/** Renames a variant, so name-based lookups in a test are unambiguous. */
	private void renameVariant(ProductVariant variant, String name) {
		products.updateVariant(shop, variant, name, null, null, null, null, null, null, null);
	}

	private int onHandOf(ProductVariant variant) {
		return inventory.find(variant.getProductId(), variant.getId())
				.map(Inventory::getQuantityOnHand).orElse(0);
	}

	// ------------------------------------------------------------------ import

	@Nested
	@DisplayName("importing")
	class Importing {

		@Test
		@DisplayName("adds to existing stock by default")
		void addsByDefault() {
			// The default matters more than it looks. A delivery note saying "+25" must not be read
			// as "the total is now 25".
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,25", Operation.ADD);

			assertThat(onHandOf(rice)).isEqualTo(25);
		}

		@Test
		@DisplayName("two imports accumulate")
		void accumulates() {
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,10", Operation.ADD);
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,15", Operation.ADD);

			assertThat(onHandOf(rice)).isEqualTo(25);
		}

		@Test
		@DisplayName("SET overwrites, and must be asked for explicitly")
		void setOverwrites() {
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,10", Operation.ADD);
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,3", Operation.SET);

			assertThat(onHandOf(rice))
					.as("this is a genuine stocktake, so overwriting is correct here")
					.isEqualTo(3);
		}

		@Test
		@DisplayName("SET to zero is allowed, for a shop that has sold out")
		void setToZero() {
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,0", Operation.SET);

			assertThat(onHandOf(rice)).isZero();
		}

		@Test
		@DisplayName("reports a SKU that is duplicated within the shop, rather than guessing")
		void reportsAmbiguousSku() {
			// A SKU is unique per product, not per shop. Picking the first match would mark the row
			// applied while quietly leaving the other variant untouched, and the owner would trust
			// the summary. Refusing is the only honest option.
			ProductVariant other = fixtures.createProductWithSku(shop, "Twin A",
					ProductCategory.RICE, 10_000L, "TWIN-SKU");
			fixtures.createProductWithSku(shop, "Twin B", ProductCategory.RICE, 10_000L, "TWIN-SKU");

			ImportResult result = csv.importCsv(shop, "TWIN-SKU,Default,Twin,RICE,9", Operation.ADD);

			assertThat(result.applied()).isZero();
			assertThat(result.rows().get(0).message())
					.as("the owner is told exactly what to fix")
					.contains("matches 2 variants")
					.contains("must be unique");
			assertThat(inventory.find(other.getProductId(), other.getId())
					.map(Inventory::getQuantityOnHand).orElse(0))
					.as("nothing was changed, so nothing can be half-applied")
					.isZero();
		}

		@Test
		@DisplayName("matches on the variant name when there is no SKU")
		void matchesByName() {
			// A supplier's sheet will not always carry the shop's own codes, so a variant with no
			// SKU has to be reachable by name. Renamed first, because the name fallback is
			// ambiguous by design when two products share a variant name — see reportsAmbiguousSku.
			renameVariant(rice, "5kg bag");

			csv.importCsv(shop, ",5kg bag,Rice,RICE,7", Operation.ADD);

			assertThat(onHandOf(rice)).isEqualTo(7);
		}

		@Test
		@DisplayName("accepts a per-row operation that overrides the default")
		void perRowOperation() {
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,20,ADD\nLENTIL-1,1kg,Lentils,LENTIL,2,SET",
					Operation.ADD);

			assertThat(onHandOf(rice)).isEqualTo(20);
			assertThat(onHandOf(lentils))
					.as("the row asked for SET, so it overwrote rather than added")
					.isEqualTo(2);
		}
	}

	@Nested
	@DisplayName("one bad row does not stop the rest")
	class PartialFailures {

		@Test
		@DisplayName("applies the good rows and reports the bad one")
		void partialFailure() {
			// This is the property that decides whether the feature is usable. An owner importing
			// 500 SKUs who loses the lot to one typo is doing the job by hand instead.
			ImportResult result = csv.importCsv(shop,
					"RICE-5,5kg,Rice,RICE,10\nNOSUCHSKU,x,Nope,RICE,5\nLENTIL-1,1kg,Lentils,LENTIL,3",
					Operation.ADD);

			assertThat(result.applied()).isEqualTo(2);
			assertThat(result.failed()).isEqualTo(1);
			assertThat(onHandOf(rice)).isEqualTo(10);
			assertThat(onHandOf(lentils)).isEqualTo(3);

			assertThat(result.rows())
					.filteredOn(r -> !r.ok())
					.singleElement()
					.satisfies(row -> {
						assertThat(row.message()).contains("NOSUCHSKU");
						assertThat(row.line()).as("the owner needs the line number to find it").isEqualTo(3);
					});
		}

		@Test
		@DisplayName("does not create products for unknown SKUs")
		void neverCreatesProducts() {
			// Bulk-creating a catalogue from a spreadsheet is a different and far more dangerous
			// operation, with validation the owner has never seen.
			ImportResult result = csv.importCsv(shop, "BRAND-NEW,New Thing,Mystery,RICE,5",
					Operation.ADD);

			assertThat(result.applied()).isZero();
			assertThat(result.rows().get(0).message()).contains("No variant in this shop");
		}

		@Test
		@DisplayName("rejects a non-numeric quantity on that row only")
		void rejectsBadQuantity() {
			ImportResult result = csv.importCsv(shop,
					"RICE-5,5kg,Rice,RICE,10\nLENTIL-1,1kg,Lentils,LENTIL,many", Operation.ADD);

			assertThat(result.applied()).isEqualTo(1);
			assertThat(result.rows().get(1).message()).contains("not a number");
		}

		@Test
		@DisplayName("rejects a negative quantity")
		void rejectsNegative() {
			ImportResult result = csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,-5", Operation.ADD);

			assertThat(result.applied()).isZero();
			assertThat(result.rows().get(0).message()).contains("cannot be negative");
		}

		@Test
		@DisplayName("refuses a SET below what open orders have reserved")
		void refusesSetBelowReserved() {
			// Otherwise the import silently makes the shop oversell an order already placed.
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,10", Operation.ADD);
			inventory.reserve(rice.getProductId(), rice.getId(), 8, UUID.randomUUID(), false);

			ImportResult result = csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,2", Operation.SET);

			assertThat(result.applied()).isZero();
			assertThat(result.rows().get(0).message())
					.contains("already reserved");
			assertThat(onHandOf(rice)).isEqualTo(10);
		}

		@Test
		@DisplayName("rejects a row with neither a SKU nor a name")
		void rejectsIdentlessRow() {
			ImportResult result = csv.importCsv(shop, ",,Rice,RICE,5", Operation.ADD);

			assertThat(result.rows().get(0).message()).contains("at least a sku");
		}

		@Test
		@DisplayName("rejects an unknown operation")
		void rejectsUnknownOperation() {
			ImportResult result = csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,5,0,MULTIPLY",
					Operation.ADD);

			assertThat(result.rows().get(0).message()).contains("must be SET or ADD");
		}
	}

	@Nested
	@DisplayName("file-level problems")
	class FileProblems {

		@Test
		@DisplayName("a row with no quantity is reported, and the other rows still import")
		void missingQuantity() {
			// Headers are optional, so a file without a quantity column is read positionally
			// rather than rejected outright. The owner gets a per-row message, which is more
			// useful than losing the whole file, and it names the field to fix.
			ImportResult result = csv.importCsv(shop,
					"RICE-5,Default,Rice,RICE,10\nLENTIL-1,Default,Lentils,LENTIL", Operation.ADD);

			assertThat(result.applied()).isEqualTo(1);
			assertThat(result.rows().get(1).message()).contains("quantity");
			assertThat(onHandOf(rice)).isEqualTo(10);
		}

		@Test
		@DisplayName("a header with no data rows says so")
		void headerOnly() {
			ImportResult result = csv.importCsv(shop, "sku,quantity", Operation.ADD);

			assertThat(result.applied()).isZero();
			assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("no data rows"));
		}

		@Test
		@DisplayName("an empty file is refused")
		void emptyFile() {
			assertThat(csv.importCsv(shop, "", Operation.ADD).errors()).isNotEmpty();
		}
	}

	@Nested
	@DisplayName("CSV parsing")
	class Parsing {

		@Test
		@DisplayName("a quoted field containing a comma does not shift the columns")
		void quotedCommas() {
			// The classic failure of a naive split(","). A product called "Rice, 5kg" would push
			// every later column one to the right and corrupt the whole import.
			csv.importCsv(shop, "RICE-5,\"5kg, premium\",Rice,RICE,12", Operation.ADD);

			assertThat(onHandOf(rice))
					.as("the quantity is still the quantity, not something shifted by the comma")
					.isEqualTo(12);
		}

		@Test
		@DisplayName("an escaped double quote is read as one quote")
		void escapedQuotes() {
			// RFC 4180: a doubled quote inside a quoted field is one literal quote.
			//
			// Built by concatenation rather than as one escaped literal, because the nesting of
			// backslash-escaped quotes inside Java quotes is unreadable, and a miscount here would
			// look exactly like a parser bug.
			String row = "," + "\"Default \"\"special\"\"\"" + ",Rice,RICE,9";

			ImportResult result = csv.importCsv(shop, row, Operation.ADD);

			assertThat(result.rows()).hasSize(1);
			assertThat(result.rows().get(0).message())
					.as("the quotes collapsed correctly, so the name is Default \"special\" and "
							+ "matches no variant. A parser bug would instead report the raw text.")
					.contains("No variant in this shop");
		}

		@Test
		@DisplayName("columns may be reordered, which is what Excel does")
		void columnOrderIrrelevant() {
			// Accepted by header name rather than position: an owner who drags a column is not
			// doing something wrong, and failing on it would make the feature unusable.
			csv.importCsv(shop, "quantity,sku\n14,RICE-5", Operation.ADD);

			assertThat(onHandOf(rice)).isEqualTo(14);
		}

		@Test
		@DisplayName("blank lines are skipped, not treated as empty rows")
		void blankLinesSkipped() {
			ImportResult result = csv.importCsv(shop,
					"RICE-5,5kg,Rice,RICE,10\n\n\nLENTIL-1,1kg,Lentils,LENTIL,4\n", Operation.ADD);

			assertThat(result.rows()).as("three blank lines would otherwise become three failures")
					.hasSize(2);
			assertThat(result.applied()).isEqualTo(2);
		}

		@Test
		@DisplayName("Windows line endings are handled")
		void windowsLineEndings() {
			csv.importCsv(shop, "RICE-5,5kg,Rice,RICE,6\r\nLENTIL-1,1kg,Lentils,LENTIL,8\r\n",
					Operation.ADD);

			assertThat(onHandOf(rice)).isEqualTo(6);
			assertThat(onHandOf(lentils)).isEqualTo(8);
		}

		@Test
		@DisplayName("header names are matched case-insensitively")
		void headerCaseInsensitive() {
			csv.importCsv(shop, "SKU,Quantity\nRICE-5,11", Operation.ADD);

			assertThat(onHandOf(rice)).isEqualTo(11);
		}
	}

	// ------------------------------------------------------------------ export

	@Nested
	@DisplayName("exporting")
	class Exporting {

		@Test
		@DisplayName("exports on hand, with reserved shown separately")
		void exportsOnHand() {
			// On hand, because that is what a stocktake counts. Exporting "available" was tried and
			// is wrong: SET writes on-hand, so re-importing an available figure silently understates
			// stock by exactly the reserved amount, every time.
			inventory.restock(rice.getProductId(), rice.getId(), 20, null, null);
			inventory.reserve(rice.getProductId(), rice.getId(), 6, UUID.randomUUID(), false);

			String row = rowFor(csv.exportCsv(shop), "RICE-5");
			java.util.List<String> fields = java.util.List.of(row.split(","));

			assertThat(fields.get(4))
					.as("the quantity column is on hand, so the round trip is exact")
					.isEqualTo("20");
			assertThat(fields.get(5))
					.as("reserved is exported beside it so the owner sees what is already promised")
					.isEqualTo("6");
		}

		@Test
		@DisplayName("a reserved column in an imported file is ignored, not rejected")
		void ignoresReservedColumn() {
			// It is exported so an owner can read it. A column they can edit but that has no effect
			// is a trap, and refusing the row would make the export unimportable — so it is accepted
			// and deliberately not applied: the reservation is the platform's to hold.
			inventory.restock(rice.getProductId(), rice.getId(), 20, null, null);
			inventory.reserve(rice.getProductId(), rice.getId(), 6, UUID.randomUUID(), false);

			ImportResult result = csv.importCsv(shop,
					"RICE-5,5kg,Rice,RICE,15,999,SET,45000", Operation.ADD);

			assertThat(result.applied()).isEqualTo(1);
			assertThat(inventory.find(rice.getProductId(), rice.getId()).orElseThrow()
					.getQuantityOnHand())
					.as("the quantity column applied; the reserved one did not")
					.isEqualTo(15);
			assertThat(inventory.find(rice.getProductId(), rice.getId()).orElseThrow()
					.getQuantityReserved())
					.as("the reservation is untouched")
					.isEqualTo(6);
		}

		@Test
		@DisplayName("the export can be re-imported unchanged")
		void roundTrips() {
			// The whole point of an export an owner can edit. A format that cannot survive a
			// round trip is not an import feature.
			inventory.restock(rice.getProductId(), rice.getId(), 30, null, null);
			inventory.restock(lentils.getProductId(), lentils.getId(), 12, null, null);

			String out = csv.exportCsv(shop);
			ImportResult result = csv.importCsv(shop, out, Operation.SET);

			assertThat(result.applied())
					.as("re-importing the file we just produced must apply every row")
					.isGreaterThanOrEqualTo(2);
			assertThat(onHandOf(rice)).isEqualTo(30);
			assertThat(onHandOf(lentils)).isEqualTo(12);

			// And the round trip is exact, which is what makes the export trustworthy.
			String again = csv.exportCsv(shop);
			assertThat(again).isEqualTo(out);
		}

		@Test
		@DisplayName("neutralises a formula-looking product name")
		void neutralisesFormulaInjection() {
			// A product legitimately named "=SUM(A1:A9)", or a reference code beginning "+", is
			// executed as a formula by Excel and Sheets when the owner opens the export. Quoting
			// alone does not prevent it; the character has to be defused.
			fixtures.createProductWithSku(shop, "=HYPERLINK(\"http://evil.test\")",
					ProductCategory.RICE, 1_000L, "EVIL-1");

			String out = csv.exportCsv(shop);

			assertThat(out).doesNotContain(",=HYPERLINK");
			assertThat(out).contains("\"'=HYPERLINK");
		}

		@Test
		@DisplayName("quotes a name containing a comma")
		void quotesCommas() {
			fixtures.createProductWithSku(shop, "Rice, premium", ProductCategory.RICE, 1_000L,
					"COMM-1");

			assertThat(csv.exportCsv(shop)).contains("\"Rice, premium\"");
		}

		@Test
		@DisplayName("never exports another shop's variants")
		void scopedToShop() {
			Shop other = fixtures.createVerifiedShop(
					"Other " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
			fixtures.createProductWithSku(other, "Secret", ProductCategory.RICE, 1_000L, "SECRET-1");

			assertThat(csv.exportCsv(shop)).doesNotContain("SECRET-1");
		}

		@Test
		@DisplayName("exports drafts too, so an owner can edit the whole catalogue")
		void includesDrafts() {
			// An export that silently omits rows an owner then edits and re-imports is a trap:
			// they will believe those products were deleted.
			fixtures.createProductWithSku(shop, "Draft Thing", ProductCategory.RICE, 1_000L,
					"DRAFT-1");

			assertThat(csv.exportCsv(shop)).contains("DRAFT-1");
		}

		private String rowFor(String csv, String sku) {
			return csv.lines().filter(l -> l.startsWith(sku + ",")).findFirst().orElse("");
		}
	}
}
