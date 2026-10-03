package com.storefront.store.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ProductService.VariantOptions;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Product and variant rules.
 *
 * <p>The three properties worth the most attention, because each is a place where a plausible
 * implementation is quietly wrong:
 *
 * <ul>
 *   <li>a product cannot be written in a category its shop's type does not permit (D18),</li>
 *   <li>a product belonging to another shop is indistinguishable from one that does not exist
 *       (D35),</li>
 *   <li>the product's displayed price is the cheapest active variant, refreshed on every change.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ProductServiceIT {

	@Autowired
	private ProductService products;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ProductVariantRepository variantRepository;

	@Autowired
	private TestFixtures fixtures;

	// ------------------------------------------------------------------ create

	@Nested
	@DisplayName("creating a product")
	class Create {

		@Test
		@DisplayName("always starts as a draft, never live")
		void startsAsDraft() {
			Shop shop = fixtures.createVerifiedPharmacy();

			Product product = products.createDraft(shop, "Panadol 500mg", ProductCategory.MEDICINE,
					null, "Paracetamol 500mg", "For mild to moderate pain.");

			assertThat(product.getStatus())
					.as("a product created from a half-filled form must not appear in the storefront")
					.isEqualTo(Product.Status.DRAFT);
			assertThat(product.isVisible()).isFalse();
			assertThat(product.getShopId()).isEqualTo(shop.getId());
			assertThat(product.getSlug()).isEqualTo("panadol-500mg");
		}

		@Test
		@DisplayName("rejects a category the shop's type does not sell")
		void rejectsForeignCategory() {
			// A pharmacy selling laptop bags is the cross-vertical leak that a free-text
			// category would let through.
			Shop pharmacy = fixtures.createVerifiedPharmacy();

			assertThatThrownBy(() -> products.createDraft(pharmacy, "Gaming Laptop Bag",
					ProductCategory.BAGS, null, null, null))
					.isInstanceOf(CategoryValidator.InvalidCategoryException.class);
		}

		@Test
		@DisplayName("accepts every category the shop's type does sell")
		void acceptsOwnCategories() {
			Shop pharmacy = fixtures.createVerifiedPharmacy();

			for (ProductCategory category : ShopType.PHARMACY.categories()) {
				Product product = products.createDraft(pharmacy, "Item " + category.name(),
						category, null, null, null);
				assertThat(product.getProductCategory()).isEqualTo(category);
			}
		}

		@Test
		@DisplayName("gives two products with the same name different slugs")
		void slugsAreUniqueWithinAShop() {
			Shop grocery = fixtures.createVerifiedShop("Corner Store", ShopType.GROCERY);

			Product first = products.createDraft(grocery, "Samosa", ProductCategory.SNACKS,
					null, null, null);
			Product second = products.createDraft(grocery, "Samosa", ProductCategory.SNACKS,
					null, null, null);

			assertThat(first.getSlug()).isEqualTo("samosa");
			assertThat(second.getSlug()).isEqualTo("samosa-2");
		}

		@Test
		@DisplayName("two shops may both have a product called Samosa")
		void slugsAreUniquePerShopNotGlobally() {
			// This is the whole point of scoping the slug: one shop owning /samosa must not stop
			// another shop listing the same food.
			Shop first = fixtures.createVerifiedShop("Shop A " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);
			Shop second = fixtures.createVerifiedShop("Shop B " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);

			Product a = products.createDraft(first, "Samosa", ProductCategory.SNACKS, null, null, null);
			Product b = products.createDraft(second, "Samosa", ProductCategory.SNACKS, null, null, null);

			assertThat(a.getSlug()).isEqualTo(b.getSlug());
		}
	}

	// ------------------------------------------------------------------ variants

	@Nested
	@DisplayName("variants")
	class Variants {

		@Test
		@DisplayName("a variant carries the price, and the product mirrors the cheapest")
		void productMirrorsCheapestVariant() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Vitamin C", ProductCategory.SUPPLEMENT,
					null, null, null);

			products.addVariant(shop, product, "250mg x 30", 45_000L, null,
					VariantOptions.empty(), null, null);
			products.addVariant(shop, product, "500mg x 30", 80_000L, null,
					VariantOptions.empty(), null, null);
			products.addVariant(shop, product, "500mg x 90", 200_000L, null,
					VariantOptions.empty(), null, null);

			assertThat(product.getBasePriceMinor())
					.as("a listing shows 'from' the cheapest variant, so this must track it")
					.isEqualTo(45_000L);
		}

		@Test
		@DisplayName("a price change re-mirrors the product")
		void priceChangeRefreshesTheMirror() {
			// The failure this guards against is a stale "from Rs. 45" badge left on a product
			// whose cheapest size is now Rs. 80.
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Vitamin C", ProductCategory.SUPPLEMENT,
					null, null, null);

			ProductVariant cheap = products.addVariant(shop, product, "250mg", 45_000L, null,
					VariantOptions.empty(), null, null);
			products.addVariant(shop, product, "500mg", 80_000L, null, VariantOptions.empty(), null, null);

			products.updateVariant(shop, cheap, null, 95_000L, null, null, null, null, null, null);

			assertThat(product.getBasePriceMinor()).isEqualTo(80_000L);
		}

		@Test
		@DisplayName("deactivating the cheapest variant re-mirrors the product")
		void deactivationRefreshesTheMirror() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Vitamin C", ProductCategory.SUPPLEMENT,
					null, null, null);

			ProductVariant cheap = products.addVariant(shop, product, "250mg", 45_000L, null,
					VariantOptions.empty(), null, null);
			products.addVariant(shop, product, "500mg", 80_000L, null, VariantOptions.empty(), null, null);

			products.deactivateVariant(shop, cheap);

			assertThat(product.getBasePriceMinor()).isEqualTo(80_000L);
		}

		@Test
		@DisplayName("refuses a duplicate SKU within one product")
		void refusesDuplicateSku() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Paracetamol", ProductCategory.MEDICINE,
					null, null, null);

			products.addVariant(shop, product, "16 tablets", 40_000L, "SKU-1",
					VariantOptions.empty(), null, null);

			assertThatThrownBy(() -> products.addVariant(shop, product, "32 tablets", 70_000L,
					"SKU-1", VariantOptions.empty(), null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("already used");
		}

		@Test
		@DisplayName("the same SKU in two different products is fine")
		void skuIsUniquePerProductNotGlobally() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product a = products.createDraft(shop, "Paracetamol", ProductCategory.MEDICINE,
					null, null, null);
			Product b = products.createDraft(shop, "Ibuprofen", ProductCategory.MEDICINE,
					null, null, null);

			products.addVariant(shop, a, "16 tablets", 40_000L, "SKU-1", VariantOptions.empty(),
					null, null);
			products.addVariant(shop, b, "16 tablets", 60_000L, "SKU-1", VariantOptions.empty(),
					null, null);

			assertThat(products.variantsOf(a)).hasSize(1);
			assertThat(products.variantsOf(b)).hasSize(1);
		}

		@Test
		@DisplayName("stores option axes as data, so a new vertical needs no migration")
		void storesOptionsAsJson() {
			Shop shop = fixtures.createVerifiedShop("Kurta Shop " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.FASHION);
			Product product = products.createDraft(shop, "Kurta", ProductCategory.MENS_CLOTHING,
					null, null, null);

			// A LinkedHashMap, which is what Jackson produces from the request body, so the
			// shop's chosen axis order is what reaches the service.
			java.util.Map<String, String> axes = new java.util.LinkedHashMap<>();
			axes.put("size", "L");
			axes.put("colour", "red");

			ProductVariant variant = products.addVariant(shop, product, "Large / Red", 250_000L,
					"K-RED-L", VariantOptions.of(axes), null, null);

			assertThat(variant.optionMap())
					.containsEntry("size", "L")
					.containsEntry("colour", "red");
			assertThat(variant.optionsLabel())
					.as("the shop's axis order must survive the round trip through jsonb")
					.isEqualTo("L / red");
			assertThat(variant.option("colour")).isEqualTo("red");
			assertThat(variant.option("missing")).isNull();
		}

		@Test
		@DisplayName("a variant with no options reports none, rather than an empty label")
		void noOptionsMeansNoLabel() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);

			ProductVariant variant = products.addVariant(shop, product, "Single", 40_000L, null,
					VariantOptions.empty(), null, null);

			assertThat(variant.optionMap()).isEmpty();
			assertThat(variant.optionsLabel())
					.as("null, not an empty string: a caller must be able to tell 'no axes' apart")
					.isNull();
		}

		@Test
		@DisplayName("rejects a negative price")
		void rejectsNegativePrice() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);

			assertThatThrownBy(() -> products.addVariant(shop, product, "Bad", -1L, null,
					VariantOptions.empty(), null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("rejects a compare-at price below the current price")
		void rejectsNonsenseCompareAt() {
			// A "save 40%" badge computed from a compare-at below the price is negative, and
			// reads to a customer as a pricing bug on the shop.
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);

			assertThatThrownBy(() -> products.addVariant(shop, product, "16 tablets", 40_000L,
					null, VariantOptions.empty(), null, 20_000L))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("greater than");
		}
	}

	// ------------------------------------------------------------------ lifecycle

	@Nested
	@DisplayName("publishing")
	class Publishing {

		@Test
		@DisplayName("refuses a product with no priced variant")
		void refusesUnpriced() {
			// Publishing this puts a row in a basket that cannot be bought, and the customer
			// only finds out after typing their address.
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Empty", ProductCategory.MEDICINE,
					null, null, null);

			assertThatThrownBy(() -> products.publish(shop, product))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("at least one variant");
		}

		@Test
		@DisplayName("refuses to publish for a shop that is not verified")
		void refusesUnverifiedShop() {
			// Otherwise the verification gate is bypassed entirely by listing a product.
			Shop shop = fixtures.createShop("Draft Shop", ShopType.PHARMACY);
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);
			products.addVariant(shop, product, "16 tablets", 40_000L, null, VariantOptions.empty(),
					null, null);

			assertThatThrownBy(() -> products.publish(shop, product))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("verified");
		}

		@Test
		@DisplayName("publishes a priced product and records its minimum price")
		void publishes() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);
			products.addVariant(shop, product, "16 tablets", 40_000L, null, VariantOptions.empty(),
					null, null);

			Product published = products.publish(shop, product);

			assertThat(published.isVisible()).isTrue();
			assertThat(published.getBasePriceMinor()).isEqualTo(40_000L);
		}

		@Test
		@DisplayName("unpublishing returns it to draft without losing variants")
		void unpublishKeepsVariants() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);
			products.addVariant(shop, product, "16 tablets", 40_000L, null, VariantOptions.empty(),
					null, null);
			products.publish(shop, product);

			products.unpublish(shop, product);

			assertThat(product.getStatus()).isEqualTo(Product.Status.DRAFT);
			assertThat(products.variantsOf(product)).hasSize(1);
		}
	}

	@Nested
	@DisplayName("deleting")
	class Deleting {

		@Test
		@DisplayName("refuses to delete a product that has sold")
		void refusesToStrandOrderHistory() {
			// The shop's sales report and its inventory history both point at this row.
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);
			products.recordSale(product, 3);

			assertThatThrownBy(() -> products.delete(shop, product))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Archive");
		}

		@Test
		@DisplayName("archiving works instead, and hides it from the storefront")
		void archiveInstead() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);
			products.recordSale(product, 3);

			products.archive(shop, product);

			assertThat(product.getStatus()).isEqualTo(Product.Status.ARCHIVED);
			assertThat(product.isVisible()).isFalse();
			assertThat(product.isDeleted()).isFalse();
		}

		@Test
		@DisplayName("deletes an unsold product softly, so it leaves listings but survives")
		void softDeletes() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Mistake", ProductCategory.MEDICINE,
					null, null, null);

			products.delete(shop, product);

			assertThat(product.isDeleted()).isTrue();
			assertThat(productRepository.findByIdAndDeletedAtIsNull(product.getId())).isEmpty();
			assertThat(productRepository.findById(product.getId())).isPresent();
		}

		@Test
		@DisplayName("restore brings it back as a draft, not as published")
		void restoreIsADraft() {
			// Restoring straight to ACTIVE would skip the price and image checks, publishing
			// something the owner never re-reviewed.
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Mistake", ProductCategory.MEDICINE,
					null, null, null);
			products.addVariant(shop, product, "16 tablets", 40_000L, null, VariantOptions.empty(),
					null, null);
			products.publish(shop, product);
			products.delete(shop, product);

			Product restored = products.restore(shop, product);

			assertThat(restored.isDeleted()).isFalse();
			assertThat(restored.getStatus()).isEqualTo(Product.Status.DRAFT);
		}
	}

	// ------------------------------------------------------------------ tenancy

	@Nested
	@DisplayName("tenant isolation")
	class Tenancy {

		@Test
		@DisplayName("another shop's product reads as not found, not as forbidden")
		void crossShopReadIsIndistinguishable() {
			// A 403 here would confirm the id exists somewhere, turning this endpoint into an
			// oracle for enumerating another shop's catalogue.
			Shop mine = fixtures.createVerifiedPharmacy();
			Shop theirs = fixtures.createVerifiedPharmacy();
			Product theirProduct = products.createDraft(theirs, "Theirs", ProductCategory.MEDICINE,
					null, null, null);

			assertThatThrownBy(() -> products.getOwned(mine, theirProduct.getId()))
					.isInstanceOf(ProductService.ProductNotFoundException.class);
		}

		@Test
		@DisplayName("another shop cannot write to my product")
		void crossShopWriteIsRefused() {
			Shop mine = fixtures.createVerifiedPharmacy();
			Shop theirs = fixtures.createVerifiedPharmacy();
			Product myProduct = products.createDraft(mine, "Mine", ProductCategory.MEDICINE,
					null, null, null);

			assertThatThrownBy(() -> products.update(theirs, myProduct, "Hijacked", null, null,
					null, null, null, null, null, null, null, null))
					.isInstanceOf(ProductService.ProductNotFoundException.class);
			assertThatThrownBy(() -> products.delete(theirs, myProduct))
					.isInstanceOf(ProductService.ProductNotFoundException.class);
			assertThatThrownBy(() -> products.publish(theirs, myProduct))
					.isInstanceOf(ProductService.ProductNotFoundException.class);
		}

		@Test
		@DisplayName("another shop cannot add a variant to my product")
		void crossShopVariantWriteIsRefused() {
			Shop mine = fixtures.createVerifiedPharmacy();
			Shop theirs = fixtures.createVerifiedPharmacy();
			Product myProduct = products.createDraft(mine, "Mine", ProductCategory.MEDICINE,
					null, null, null);

			assertThatThrownBy(() -> products.addVariant(theirs, myProduct, "Sneaky", 1L, null,
					VariantOptions.empty(), null, null))
					.isInstanceOf(ProductService.ProductNotFoundException.class);
		}

		@Test
		@DisplayName("listing is scoped to the shop")
		void listingIsScoped() {
			Shop mine = fixtures.createVerifiedPharmacy();
			Shop theirs = fixtures.createVerifiedPharmacy();
			products.createDraft(mine, "Mine", ProductCategory.MEDICINE, null, null, null);
			products.createDraft(theirs, "Theirs", ProductCategory.MEDICINE, null, null, null);

			List<Product> visible = productRepository
					.findByShopIdAndDeletedAtIsNull(mine.getId(),
							org.springframework.data.domain.Pageable.unpaged())
					.getContent();

			assertThat(visible).extracting(Product::getName).containsExactly("Mine");
		}

		@Test
		@DisplayName("categoriesInUse is scoped, so the type-change picker is accurate")
		void categoriesInUseIsScoped() {
			Shop mine = fixtures.createVerifiedPharmacy();
			Shop theirs = fixtures.createVerifiedPharmacy();
			products.createDraft(mine, "Mine", ProductCategory.SUPPLEMENT, null, null, null);
			products.createDraft(theirs, "Theirs", ProductCategory.MEDICINE, null, null, null);

			assertThat(products.categoriesInUse(mine)).containsExactly(ProductCategory.SUPPLEMENT);
		}
	}

	// ------------------------------------------------------------------ counters

	@Nested
	@DisplayName("counters")
	class Counters {

		@Test
		@DisplayName("a sale increments soldCount")
		void saleIncrements() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);

			products.recordSale(product, 2);

			assertThat(productRepository.findById(product.getId()).orElseThrow().getSoldCount())
					.isEqualTo(2);
		}

		@Test
		@DisplayName("a view increments viewCount")
		void viewIncrements() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);

			products.recordView(product);

			assertThat(productRepository.findById(product.getId()).orElseThrow().getViewCount())
					.isEqualTo(1);
		}

		@Test
		@DisplayName("a zero or negative sale is ignored")
		void ignoresNonPositiveSale() {
			Shop shop = fixtures.createVerifiedPharmacy();
			Product product = products.createDraft(shop, "Panadol", ProductCategory.MEDICINE,
					null, null, null);

			products.recordSale(product, 0);
			products.recordSale(product, -5);

			assertThat(product.getSoldCount()).isZero();
		}
	}
}
