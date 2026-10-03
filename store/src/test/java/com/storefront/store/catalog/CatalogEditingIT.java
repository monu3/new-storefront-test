package com.storefront.store.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ProductService.BulkChange;
import com.storefront.store.catalog.ProductService.BulkResult;
import com.storefront.store.catalog.ProductService.AdjustmentKind;
import com.storefront.store.catalog.ProductService.IncomingVariant;
import com.storefront.store.catalog.ProductService.PriceAdjustment;
import com.storefront.store.catalog.ProductService.VariantOptions;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.inventory.InventoryService;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Bulk editing, duplication and the variants matrix.
 *
 * <p>These are the operations where a mistake is expensive and hard to undo, because they touch
 * many products at once. Three properties are asserted repeatedly:
 *
 * <ul>
 *   <li>a bulk action <b>skips and reports</b> rather than failing, so one bad row does not
 *       abandon the other thirty-nine;</li>
 *   <li>only the fields supplied are touched — a bulk price change must not blank descriptions;</li>
 *   <li>a duplicate is a <b>draft with no SKUs</b>, so it cannot appear on the storefront or
 *       collide with the original.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class CatalogEditingIT {

	@Autowired
	private ProductService products;

	@Autowired
	private InventoryService inventory;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Editing Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
	}

	private Product rice() {
		return fixtures.createProduct(shop, "Rice " + UUID.randomUUID().toString().substring(0, 6),
				ProductCategory.RICE, 45_000L);
	}

	// ------------------------------------------------------------------ bulk fields

	@Nested
	@DisplayName("bulk editing")
	class BulkFields {

		@Test
		@DisplayName("changes only the supplied fields")
		void partialChange() {
			// The failure this prevents is catastrophic and silent: a naive "replace the whole
			// object" bulk edit blanks every description on every selected product, unrecoverably.
			Product product = rice();
			products.update(shop, product, null, "A short description", "A long description",
					null, null, null, null, null, null, null, null);
			Product other = rice();

			BulkResult result = products.bulkUpdate(shop, List.of(product.getId(), other.getId()),
					new BulkChange(ProductCategory.LENTIL, null, null, null, null));

			assertThat(result.appliedCount()).isEqualTo(2);
			assertThat(products.getOwned(shop, product.getId()).getProductCategory())
					.isEqualTo(ProductCategory.LENTIL);
			assertThat(products.getOwned(shop, product.getId()).getShortDescription())
					.as("the description must survive a category change")
					.isEqualTo("A short description");
			assertThat(products.getOwned(shop, other.getId()).getShortDescription())
					.as("and on the product that never had one, nothing is invented")
					.isNull();
		}

		@Test
		@DisplayName("a null in every field changes nothing at all")
		void noOpIsSafe() {
			Product product = rice();

			BulkResult result = products.bulkUpdate(shop, List.of(product.getId()),
					new BulkChange(null, null, null, null, null));

			assertThat(result.appliedCount()).isEqualTo(1);
			assertThat(products.getOwned(shop, product.getId()).getProductCategory())
					.isEqualTo(ProductCategory.RICE);
		}

		@Test
		@DisplayName("skips another shop's products and says why")
		void skipsForeignProducts() {
			Product mine = rice();
			Shop theirs = fixtures.createVerifiedShop(
					"Their Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
			Product theirProduct = fixtures.createProduct(theirs, "Theirs",
					ProductCategory.RICE, 10_000L);

			BulkResult result = products.bulkUpdate(shop,
					List.of(mine.getId(), theirProduct.getId()),
					new BulkChange(ProductCategory.LENTIL, null, null, null, null));

			assertThat(result.appliedCount()).isEqualTo(1);
			assertThat(result.skipped()).singleElement()
					.satisfies(s -> assertThat(s.reason()).contains("Not your product"));
			assertThat(products.getOwned(theirs, theirProduct.getId()).getProductCategory())
					.as("the other shop's product is untouched")
					.isEqualTo(ProductCategory.RICE);
		}

		@Test
		@DisplayName("one bad row does not abandon the rest")
		void partialFailure() {
			// An owner selecting 40 products wants 39 changed and one refused with a reason.
			Product good = rice();
			// Every product is created with one variant, so "no priced variant" means its variant
			// was deactivated — which is how a shop lands in that state in practice.
			Product noVariant = rice();
			products.deactivateVariant(shop, fixtures.defaultVariantOf(noVariant));

			BulkResult result = products.bulkUpdate(shop, List.of(good.getId(), noVariant.getId()),
					new BulkChange(null, Product.Status.ACTIVE, null, null, null));

			assertThat(result.appliedCount()).isEqualTo(1);
			assertThat(result.skipped()).singleElement()
					.satisfies(s -> assertThat(s.reason()).contains("no priced variant"));
			assertThat(products.getOwned(shop, good.getId()).isVisible()).isTrue();
			assertThat(products.getOwned(shop, noVariant.getId()).getStatus())
					.as("the refused product stays a draft rather than becoming a broken live one")
					.isEqualTo(Product.Status.DRAFT);
		}

		@Test
		@DisplayName("a bulk category change cannot escape the shop-type rule")
		void validatesCategory() {
			// A bulk action must not be a way around the rule the single-product endpoint enforces.
			Product product = rice();

			BulkResult result = products.bulkUpdate(shop, List.of(product.getId()),
					new BulkChange(ProductCategory.LAPTOP, null, null, null, null));

			assertThat(result.appliedCount()).isZero();
			assertThat(result.skipped()).singleElement();
			assertThat(products.getOwned(shop, product.getId()).getProductCategory())
					.isEqualTo(ProductCategory.RICE);
		}

		@Test
		@DisplayName("an unknown id is skipped, not thrown")
		void unknownIdSkipped() {
			BulkResult result = products.bulkUpdate(shop,
					List.of(UUID.randomUUID()),
					new BulkChange(ProductCategory.LENTIL, null, null, null, null));

			assertThat(result.appliedCount()).isZero();
			assertThat(result.skippedCount()).isEqualTo(1);
		}

		@Test
		@DisplayName("a soft-deleted product is skipped")
		void deletedSkipped() {
			Product product = rice();
			products.delete(shop, product);

			BulkResult result = products.bulkUpdate(shop, List.of(product.getId()),
					new BulkChange(ProductCategory.LENTIL, null, null, null, null));

			assertThat(result.appliedCount()).isZero();
		}
	}

	// ------------------------------------------------------------------ bulk prices

	@Nested
	@DisplayName("bulk price changes")
	class BulkPrices {

		@Test
		@DisplayName("a percentage change is applied to each variant's own price")
		void percentFromEachVariant() {
			// Applying it to the product's minimum instead would collapse every variant onto one
			// price and destroy the relative spacing between sizes.
			Product product = rice();
			ProductVariant big = products.addVariant(shop, product, "10kg", 90_000L, "BIG",
					VariantOptions.empty(), null, null);

			products.bulkAdjustPrices(shop, List.of(product.getId()),
					new PriceAdjustment(AdjustmentKind.PERCENT, 10));

			assertThat(products.getOwnedVariant(shop, big.getId()).getPriceMinor())
					.as("90_000 + 10%, not 45_000 + 10% applied to both")
					.isEqualTo(99_000L);
		}

		@Test
		@DisplayName("a negative percentage halves the price")
		void negativePercent() {
			Product product = rice();
			ProductVariant variant = fixtures.defaultVariantOf(product);

			products.bulkAdjustPrices(shop, List.of(product.getId()),
					new PriceAdjustment(AdjustmentKind.PERCENT, -50));

			assertThat(products.getOwnedVariant(shop, variant.getId()).getPriceMinor())
					.isEqualTo(22_500L);
		}

		@Test
		@DisplayName("FIXED_SET puts every variant at the same price")
		void setSamePrice() {
			Product product = rice();
			ProductVariant big = products.addVariant(shop, product, "10kg", 90_000L, "BIG",
					VariantOptions.empty(), null, null);

			products.bulkAdjustPrices(shop, List.of(product.getId()),
					new PriceAdjustment(AdjustmentKind.FIXED_SET, 30_000L));

			assertThat(products.getOwnedVariant(shop, big.getId()).getPriceMinor()).isEqualTo(30_000L);
			assertThat(products.getOwnedVariant(shop,
							fixtures.defaultVariantOf(product).getId()).getPriceMinor())
					.isEqualTo(30_000L);
		}

		@Test
		@DisplayName("clamps at zero rather than failing the batch")
		void clampsAtZero() {
			// Refusing to sell at a loss the shop explicitly asked for is not this layer's call,
			// and failing 39 rows over one cheap variant helps nobody.
			Product product = rice();

			// -90% of 45_000 is 4_500, which is fine, so go past the point where the arithmetic
			// would go negative: that is the case the clamp exists for.
			BulkResult result = products.bulkAdjustPrices(shop, List.of(product.getId()),
					new PriceAdjustment(AdjustmentKind.PERCENT, -200));

			assertThat(result.appliedCount()).isEqualTo(1);
			assertThat(products.getOwnedVariant(shop,
							fixtures.defaultVariantOf(product).getId()).getPriceMinor())
					.as("clamped rather than negative, and the row still counts as applied")
					.isZero();
		}

		@Test
		@DisplayName("refreshes the product's displayed minimum")
		void refreshesMirror() {
			// Otherwise a listing keeps showing a price no variant actually costs.
			Product product = rice();
			ProductVariant big = products.addVariant(shop, product, "10kg", 90_000L, "BIG",
					VariantOptions.empty(), null, null);

			products.bulkAdjustPrices(shop, List.of(product.getId()),
					new PriceAdjustment(AdjustmentKind.FIXED_SET, 70_000L));

			assertThat(products.getOwned(shop, product.getId()).getBasePriceMinor())
					.isEqualTo(70_000L);
		}
	}

	// ------------------------------------------------------------------ duplication

	@Nested
	@DisplayName("duplication")
	class Duplication {

		@Test
		@DisplayName("the copy is a draft, never live")
		void copyIsDraft() {
			// Duplicating a live product straight to ACTIVE puts a second copy on the storefront
			// before the owner has finished changing it.
			Product original = fixtures.liveProduct(shop, "Sona Masoori",
					ProductCategory.RICE, 45_000L, 10);

			Product copy = products.duplicate(shop, original, true, false);

			assertThat(copy.getStatus()).isEqualTo(Product.Status.DRAFT);
			assertThat(copy.isVisible()).isFalse();
			assertThat(original.isVisible())
					.as("the original is unaffected")
					.isTrue();
		}

		@Test
		@DisplayName("the copy has its own slug")
		void copyHasOwnSlug() {
			Product original = rice();

			Product copy = products.duplicate(shop, original, true, false);

			assertThat(copy.getSlug())
					.as("the per-shop unique index would reject a duplicate slug outright")
					.isNotEqualTo(original.getSlug());
			assertThat(copy.getSlug()).contains("copy");
		}

		@Test
		@DisplayName("variant SKUs are cleared, because a SKU is unique per product")
		void copiesClearSkus() {
			// Carrying them over would collide the moment the owner saves the copy.
			Product original = rice();
			products.updateVariant(shop, fixtures.defaultVariantOf(original), null, null,
					"RICE-ORIG", null, null, null, null, null);

			Product copy = products.duplicate(shop, original, true, false);

			assertThat(products.variantsOf(copy))
					.allSatisfy(v -> assertThat(v.getSku()).isNull());
			assertThat(products.variantsOf(original).get(0).getSku())
					.as("the original keeps its own SKU")
					.isEqualTo("RICE-ORIG");
		}

		@Test
		@DisplayName("copies price, margin and descriptions")
		void copiesContent() {
			Product original = rice();
			products.update(shop, original, null, "Short", "Long", null, 30_000L,
					1_000, null, null, null, null, null);

			Product copy = products.duplicate(shop, original, true, false);

			assertThat(copy.getShortDescription()).isEqualTo("Short");
			assertThat(copy.getDescription()).isEqualTo("Long");
			assertThat(copy.getCostPriceMinor())
					.as("margin is the owner's own data and is what makes the copy useful")
					.isEqualTo(30_000L);
			assertThat(copy.getWeightGrams()).isEqualTo(1_000);
		}

		@Test
		@DisplayName("is not featured and has no compare-at price")
		void copiesAreNeutral() {
			// A strike-through price copied from the original would be a claim about a product
			// that has just changed.
			Product original = rice();
			products.update(shop, original, null, null, null, 60_000L, null, null, null, null,
					true, null, null);

			Product copy = products.duplicate(shop, original, true, false);

			assertThat(copy.isFeatured()).isFalse();
			assertThat(copy.getCompareAtMinor()).isNull();
		}

		@Test
		@DisplayName("option axes and their order carry over")
		void copiesOptions() {
			Product original = rice();
			// A real axis, so the copy has something to preserve and the order is meaningful.
			java.util.Map<String, String> axes = new java.util.LinkedHashMap<>();
			axes.put("size", "L");
			products.updateVariant(shop, fixtures.defaultVariantOf(original), null, null, null,
					VariantOptions.of(axes), null, null, null, null);

			Product copy = products.duplicate(shop, original, true, false);

			assertThat(products.variantsOf(copy))
					.allSatisfy(v -> assertThat(v.getOptions())
							.as("a clothing copy must keep its size/colour axes in order")
							.hasSize(1));
		}

		@Test
		@DisplayName("can omit variants")
		void withoutVariants() {
			Product original = rice();

			Product copy = products.duplicate(shop, original, false, false);

			assertThat(products.variantsOf(copy))
					.as("a product with no variant cannot be published, which is the point")
					.isEmpty();
		}

		@Test
		@DisplayName("cannot duplicate another shop's product")
		void crossShopRefused() {
			Shop theirs = fixtures.createVerifiedShop(
					"Their Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
			Product theirProduct = fixtures.createProduct(theirs, "Theirs",
					ProductCategory.RICE, 10_000L);

			assertThatThrownBy(() -> products.duplicate(shop, theirProduct, true, false))
					.isInstanceOf(ProductService.ProductNotFoundException.class);
		}
	}

	// ------------------------------------------------------------------ variants matrix

	@Nested
	@DisplayName("the variants matrix")
	class Matrix {

		@Test
		@DisplayName("adds and updates in one request")
		void replacesTheList() {
			Product product = rice();
			ProductVariant original = fixtures.defaultVariantOf(product);

			products.replaceVariants(shop, product, List.of(
					new IncomingVariant(original.getId(), "Renamed", 50_000L, "RICE-1",
							null, null, List.of(), true),
					new IncomingVariant(null, "New Size", 90_000L, "RICE-2",
							null, null, List.of(), true)));

			List<ProductVariant> after = products.variantsOf(product);
			assertThat(after).hasSize(2);
			assertThat(after).extracting(ProductVariant::getName)
					.containsExactlyInAnyOrder("Renamed", "New Size");
			assertThat(after).filteredOn(v -> v.getId().equals(original.getId()))
					.singleElement()
					.satisfies(v -> {
						assertThat(v.getPriceMinor()).isEqualTo(50_000L);
						assertThat(v.getSku()).isEqualTo("RICE-1");
					});
		}

		@Test
		@DisplayName("a variant left out of the request is deactivated, not kept")
		void omitsDeactivate() {
			// A merge would leave behind variants the owner believes they deleted, still holding
			// stock that is still being counted.
			Product product = rice();
			ProductVariant extra = products.addVariant(shop, product, "Will be dropped", 20_000L,
					"DROP-ME", VariantOptions.empty(), null, null);

			products.replaceVariants(shop, product, List.of(
					new IncomingVariant(fixtures.defaultVariantOf(product).getId(),
							"Kept", 45_000L, null, null, null, List.of(), true)));

			assertThat(products.getOwnedVariant(shop, extra.getId()).isActive())
					.as("deactivated, so it stops being sold and is stoppable from orders")
					.isFalse();
		}

		@Test
		@DisplayName("re-saving a variant with its own SKU is allowed")
		void ownSkuIsFine() {
			// Checking uniqueness against the whole product without excluding the row itself would
			// make the matrix impossible to save without blanking every SKU first.
			Product product = rice();
			ProductVariant variant = fixtures.defaultVariantOf(product);
			products.updateVariant(shop, variant, null, null, "KEEP-1", null, null, null, null, null);

			List<ProductVariant> after = products.replaceVariants(shop, product, List.of(
					new IncomingVariant(variant.getId(), "Kept", 46_000L, "KEEP-1",
							null, null, List.of(), true)));

			assertThat(after.get(0).getSku()).isEqualTo("KEEP-1");
		}

		@Test
		@DisplayName("rejects a variant id that belongs to another product")
		void foreignVariantIdRefused() {
			// Silently creating a variant for a typo'd id gives the owner a duplicate they did not
			// ask for and cannot easily find.
			Product mine = rice();
			Product other = rice();
			ProductVariant theirVariant = fixtures.defaultVariantOf(other);

			assertThatThrownBy(() -> products.replaceVariants(shop, mine, List.of(
					new IncomingVariant(theirVariant.getId(), "Hijacked", 1L, null,
							null, null, List.of(), true))))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("not part of this product");
		}

		@Test
		@DisplayName("rejects a SKU already used by a sibling variant")
		void duplicateSkuRefused() {
			Product product = rice();
			ProductVariant a = products.addVariant(shop, product, "A", 10_000L, "SAME",
					VariantOptions.empty(), null, null);
			products.addVariant(shop, product, "B", 20_000L, "OTHER",
					VariantOptions.empty(), null, null);

			assertThatThrownBy(() -> products.replaceVariants(shop, product, List.of(
					new IncomingVariant(a.getId(), "A", 10_000L, "OTHER",
							null, null, List.of(), true),
					new IncomingVariant(null, "C", 30_000L, null,
							null, null, List.of(), true))))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("already used");
		}

		@Test
		@DisplayName("refreshes the product's displayed minimum and SKU index")
		void refreshesDerivedValues() {
			Product product = rice();

			products.replaceVariants(shop, product, List.of(
					new IncomingVariant(null, "Small", 10_000L, "MX-1", null, null, List.of(), true),
					new IncomingVariant(null, "Large", 80_000L, "MX-2", null, null, List.of(), true)));

			assertThat(products.getOwned(shop, product.getId()).getBasePriceMinor())
					.isEqualTo(10_000L);

			assertThat(products.search(shop, "MX-2", Pageable.ofSize(5)).getContent())
					.as("the denormalised SKU list must follow a matrix save")
					.hasSize(1);
		}

		@Test
		@DisplayName("an empty matrix is allowed, producing an unpublishable product")
		void emptyMatrix() {
			// The owner's business: they may be clearing a draft. The publish guard is what stops
			// it reaching the storefront.
			Product product = rice();
			ProductVariant original = fixtures.defaultVariantOf(product);

			List<ProductVariant> after = products.replaceVariants(shop, product, List.of());

			assertThat(after)
					.as("the row is deactivated rather than deleted, so order history still resolves")
					.isNotEmpty()
					.allSatisfy(v -> assertThat(v.isActive()).isFalse());
			assertThat(products.getOwnedVariant(shop, original.getId()).isActive()).isFalse();

			assertThatThrownBy(() -> products.publish(shop, products.getOwned(shop, product.getId())))
					.as("an active-variant count of zero is what stops it reaching the storefront")
					.isInstanceOf(IllegalStateException.class);
		}
	}
}
