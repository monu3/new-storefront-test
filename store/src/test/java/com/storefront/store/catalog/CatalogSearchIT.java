package com.storefront.store.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ProductService.VariantOptions;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Catalogue search: full-text plus trigram typo tolerance.
 *
 * <p>The property that matters: <b>a plausible misspelling still finds the product</b>. "paracetmol"
 * for paracetamol is not a hypothetical — it is a common way the word is typed. A search box that
 * returns nothing for it leaves the owner convinced their stock is not in the system, which is a
 * worse outcome than having no search box.
 *
 * <p>The other property: ranking. An exact word match must outrank a fuzzy one, so searching
 * "rice" shows the product actually named rice above the one that merely resembles it.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class CatalogSearchIT {

	@Autowired
	private ProductService products;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Search Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.PHARMACY);

		fixtures.liveProduct(shop, "Paracetamol 500mg", ProductCategory.MEDICINE, 40_000L, 10);
		fixtures.liveProduct(shop, "Vitamin C 250mg", ProductCategory.SUPPLEMENT, 45_000L, 10);
		fixtures.liveProduct(shop, "Digital Thermometer", ProductCategory.MEDICAL_DEVICE, 80_000L, 10);
	}

	private Page<Product> search(String term) {
		return products.search(shop, term, Pageable.ofSize(20));
	}

	/**
	 * Sets a product's description.
	 *
	 * <p>A helper rather than the eleven-argument {@code update} call at each site. Counting
	 * positional nulls is how a test ends up editing the wrong field.
	 */
	private void setDescription(Product product, String description) {
		products.update(shop, product,
				/* name */ null,
				/* shortDescription */ null,
				description,
				/* compareAt */ null,
				/* costPrice */ null,
				/* weight */ null,
				/* trackInventory */ null,
				/* allowBackorder */ null,
				/* featured */ null,
				/* seoTitle */ null,
				/* seoDescription */ null);
	}

	private java.util.List<String> names(Page<Product> page) {
		return page.getContent().stream().map(Product::getName).toList();
	}

	@Nested
	@DisplayName("full-text matching")
	class FullText {

		@Test
		@DisplayName("finds an exact word in the name")
		void exactWord() {
			assertThat(names(search("paracetamol")))
					.contains("Paracetamol 500mg");
		}

		@Test
		@DisplayName("is case-insensitive")
		void caseInsensitive() {
			assertThat(names(search("PARACETAMOL")))
					.as("an owner typing in capitals is not a different query")
					.contains("Paracetamol 500mg");
		}

		@Test
		@DisplayName("matches part of a word")
		void partialWord() {
			// The ILIKE floor in the query handles this; a strict word match would not.
			assertThat(names(search("para")))
					.contains("Paracetamol 500mg");
		}

		@Test
		@DisplayName("a SKU matches with or without its separators")
		void skuSeparatorInsensitive() {
			// A code read off a delivery note may or may not carry its hyphens. Full text
			// tokenises "SKU-0042" into two lexemes and "SKU0042" into one, so without
			// normalisation the two spellings are different searches.
			fixtures.createProductWithSku(shop, "Special Order Item",
					ProductCategory.MEDICINE, 10_000L, "SPEC-9911");

			assertThat(names(search("SPEC-9911"))).contains("Special Order Item");
			assertThat(names(search("SPEC9911")))
					.as("the separators are stripped on both sides, so both spellings find it")
					.contains("Special Order Item");
			assertThat(names(search("spec9911"))).contains("Special Order Item");
		}

		@Test
		@DisplayName("SKU matching is exact, not fuzzy")
		void skuMatchingIsNotFuzzy() {
			// Trigram on the SKU list was tried and was wrong: every SKU shares the "SPEC-" prefix,
			// so their trigrams overlap heavily and similarity came out around 0.57 between ANY two
			// of them. "SPEC-9911X" matched all 1,000 products that have a SKU.
			fixtures.createProductWithSku(shop, "First Code Item",
					ProductCategory.MEDICINE, 10_000L, "CODE-1001");
			fixtures.createProductWithSku(shop, "Second Code Item",
					ProductCategory.MEDICINE, 10_000L, "CODE-2002");

			assertThat(names(search("CODE-1001X")))
					.as("a code that matches nothing must return nothing, not the whole catalogue")
					.isEmpty();
			assertThat(names(search("CODE-1001"))).containsExactly("First Code Item");
		}

		@Test
		@DisplayName("matches a SKU")
		void matchesSku() {
			// A shop owner searching by the code on a delivery note is a real and common case.
			fixtures.createProductWithSku(shop, "Special Order Item",
					ProductCategory.MEDICINE, 10_000L, "SPEC-9911");

			assertThat(names(search("SPEC-9911")))
					.as("a bare SKU must find its product")
					.contains("Special Order Item");
		}

		@Test
		@DisplayName("returns nothing for a term that matches nothing")
		void noMatch() {
			assertThat(search("zzzznotathing")).isEmpty();
		}
	}

	@Nested
	@DisplayName("typo tolerance")
	class Typos {

		@Test
		@DisplayName("finds paracetamol when typed 'paracetmol'")
		void transposedLetters() {
			assertThat(names(search("paracetmol")))
					.as("a plausible misspelling must still find the product")
					.contains("Paracetamol 500mg");
		}

		@Test
		@DisplayName("finds paracetamol when typed 'paracetamoll'")
		void doubledLetter() {
			assertThat(names(search("paracetamoll"))).contains("Paracetamol 500mg");
		}

		@Test
		@DisplayName("finds paracetamol when typed 'paracetmol' with a missing character")
		void missingCharacter() {
			assertThat(names(search("pracetamol"))).contains("Paracetamol 500mg");
		}

		@Test
		@DisplayName("finds thermometer when typed 'thermometr'")
		void singleProductTypo() {
			assertThat(names(search("thermometr"))).contains("Digital Thermometer");
		}
	}

	@Nested
	@DisplayName("ranking")
	class Ranking {

		@Test
		@DisplayName("an exact match outranks a fuzzy one")
		void exactBeatsFuzzy() {
			// "Rice" exact should not sit below something that merely resembles it. This is the
			// ordering an owner expects when they type a real product name.
			fixtures.liveProduct(shop, "Rice", ProductCategory.MEDICINE, 10_000L, 10);
			fixtures.liveProduct(shop, "Rilee", ProductCategory.MEDICINE, 10_000L, 10);

			Page<Product> results = search("rice");

			assertThat(results.getContent()).isNotEmpty();
			assertThat(results.getContent().get(0).getName())
					.as("the exact name comes first")
					.isEqualTo("Rice");
		}

		@Test
		@DisplayName("a name match outranks a description match")
		void nameBeatsDescription() {
			// Weight A on name, B on description. Without the weights these tie and the owner
			// sees the wrong product first for no visible reason.
			com.storefront.store.catalog.Product described =
					fixtures.createProduct(shop, "Unrelated Item",
							ProductCategory.MEDICINE, 10_000L);
			setDescription(described, "Also good for a headache, see paracetamol");
			fixtures.liveProduct(shop, "Paracetamol", ProductCategory.MEDICINE, 10_000L, 10);

			assertThat(names(search("paracetamol")).get(0)).isEqualTo("Paracetamol");
		}

		@Test
		@DisplayName("best-selling comes first among equals")
		void salesBreakTies() {
			// Identical length and shape, so ts_rank and similarity tie and sold_count decides.
			// Any difference in the names themselves would change the rank and prove nothing.
			Product a = fixtures.liveProduct(shop, "Aspirin One", ProductCategory.MEDICINE, 10_000L, 10);
			Product b = fixtures.liveProduct(shop, "Aspirin Two", ProductCategory.MEDICINE, 10_000L, 10);
			products.recordSale(b, 50);
			products.recordSale(a, 1);

			Page<Product> results = search("aspirin");

			assertThat(results.getContent())
					.extracting(Product::getName)
					.as("among equally good matches, the one that sells is more likely to be wanted")
					.containsExactly("Aspirin Two", "Aspirin One");
		}
	}

	@Nested
	@DisplayName("scope and edge cases")
	class Scope {

		@Test
		@DisplayName("never returns another shop's products")
		void scopedToShop() {
			Shop other = fixtures.createVerifiedShop(
					"Other Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.PHARMACY);
			fixtures.liveProduct(other, "Secret Medicine", ProductCategory.MEDICINE, 10_000L, 10);

			assertThat(names(search("secret")))
					.as("search is scoped to the caller's own shop")
					.isEmpty();
		}

		@Test
		@DisplayName("the owner's own search includes drafts and archived products")
		void ownerSearchIncludesEverything() {
			// This is the console's search box, and an owner looking for their own draft must find
			// it. Drafts are hidden from CUSTOMERS, not from their owner. The storefront search is a
			// different query and is asserted separately.
			fixtures.createProduct(shop, "Draft Medicine", ProductCategory.MEDICINE, 10_000L);
			Product archived = fixtures.liveProduct(shop, "Retired Medicine",
					ProductCategory.MEDICINE, 10_000L, 10);
			products.archive(shop, archived);

			assertThat(names(search("draft medicine"))).contains("Draft Medicine");
			assertThat(names(search("retired"))).contains("Retired Medicine");
		}

		@Test
		@DisplayName("the storefront search hides drafts and archived products")
		void storefrontSearchHidesThem() {
			fixtures.createProduct(shop, "Draft Medicine", ProductCategory.MEDICINE, 10_000L);
			Product archived = fixtures.liveProduct(shop, "Retired Medicine",
					ProductCategory.MEDICINE, 10_000L, 10);
			products.archive(shop, archived);

			assertThat(names(products.searchInCategory(shop, "medicine",
					ProductCategory.MEDICINE, Pageable.ofSize(20))))
					.as("a draft must be as invisible to a customer's search as it is to a listing")
					.doesNotContain("Draft Medicine", "Retired Medicine");
		}

		@Test
		@DisplayName("an empty term returns the catalogue rather than nothing")
		void emptyTermListsEverything() {
			assertThat(names(search("")))
					.as("a filter box that empties the moment you clear it looks broken")
					.hasSize(3);
		}

		@Test
		@DisplayName("search within a category is scoped to it")
		void searchWithinCategory() {
			Page<Product> results = products.searchInCategory(shop, "a", ProductCategory.SUPPLEMENT,
					Pageable.ofSize(20));

			assertThat(results.getContent()).extracting(Product::getProductCategory)
					.containsOnly(ProductCategory.SUPPLEMENT);
		}

		@Test
		@DisplayName("a quoted phrase is treated as a phrase")
		void phraseSearch() {
			// websearch_to_tsquery supports "quoted phrases" and -exclusions, which plainto_tsquery
			// does not. An owner searching for an exact multi-word name expects a phrase.
			assertThat(names(search("\"vitamin c\"")))
					.contains("Vitamin C 250mg");
		}
	}

	@Nested
	@DisplayName("the SKU index stays current")
	class SkuIndex {

		@Test
		@DisplayName("a SKU set after creation is findable")
		void refreshesOnVariantWrite() {
			// The index is denormalised for speed; if a write path forgets to refresh it, the
			// product becomes unfindable by the code printed on its own box.
			com.storefront.store.catalog.Product product = fixtures.createProduct(shop,
					"Later Sku Item", ProductCategory.MEDICINE, 10_000L);
			products.addVariant(shop, product, "Boxed", 10_000L, "LATER-42",
					VariantOptions.empty(), null, null);

			assertThat(names(search("LATER-42"))).contains("Later Sku Item");
		}

		@Test
		@DisplayName("changing a SKU makes the old one stop matching")
		void oldSkuStopsMatching() {
			com.storefront.store.catalog.Product product = fixtures.createProduct(shop,
					"Renamed Sku Item", ProductCategory.MEDICINE, 10_000L);
			ProductVariant variant = products.addVariant(shop, product, "Boxed", 10_000L, "OLD-1",
					VariantOptions.empty(), null, null);

			products.updateVariant(shop, variant, null, null, "NEW-1", null, null, null, null, null);

			assertThat(names(search("NEW-1"))).contains("Renamed Sku Item");
			assertThat(names(search("OLD-1")))
					.as("a stale index would keep matching the superseded code")
					.isEmpty();
		}
	}
}
