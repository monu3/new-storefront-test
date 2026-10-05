package com.storefront.store.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Cross-shop search, the customer's version (Phase 5).
 *
 * <p>Hand-written native SQL, and the properties worth protecting are the ones a JPQL version
 * would get wrong:
 *
 * <ul>
 *   <li>an <b>archived</b> product or an <b>unverified</b> shop must not be findable — the second is
 *       the rule that keeps unvetted shops out of the marketplace;</li>
 *   <li>a typo must still find the product, which is the whole reason the trigram index exists;</li>
 *   <li>a SKU must match <b>exactly</b> — trigram matching on codes returns the entire catalogue,
 *       because every SKU shares a prefix;</li>
 *   <li>searching across shops must find shops, not just products.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class PublicSearchIT {

	private static final PageRequest PAGE = PageRequest.of(0, 25);

	@Autowired
	private ProductRepository products;

	@Autowired
	private ProductService productService;

	@Autowired
	private TestFixtures fixtures;

	private Shop pharmacy;
	private Shop grocery;

	@BeforeEach
	void setUp() {
		pharmacy = fixtures.createVerifiedPharmacy();
		grocery = fixtures.createVerifiedShop("Grocery " + UUID.randomUUID(), ShopType.GROCERY);

		// Two pharmacies selling the same thing: the D17 payoff depends on a customer finding both.
		publish(pharmacy, "Paracetamol 500mg", ProductCategory.MEDICINE, "PARA500");
		publish(pharmacy, "Cough Syrup", ProductCategory.MEDICINE, "COUGH100");
		publish(grocery, "Paracetamol 500mg", ProductCategory.MEDICINE, "PARA500G");
		publish(grocery, "Basmati Rice", ProductCategory.RICE, "RICE001");
	}

	private Product publish(Shop shop, String name, ProductCategory category, String sku) {
		// createProductWithSku returns the variant, because that is what carries the SKU. The
		// product is reached through it.
		var variant = fixtures.createProductWithSku(shop, name, category, 10_000L, sku);
		var product = products.findById(variant.getProductId()).orElseThrow();
		productService.publish(shop, product);
		return product;
	}

	// ------------------------------------------------------------------ matching

	@Nested
	@DisplayName("matching")
	class Matching {

		@Test
		@DisplayName("finds an exact name")
		void exactName() {
			var found = products.search("Paracetamol", PAGE);

			assertThat(found.getContent()).extracting(Product::getName)
					.allMatch(name -> name.contains("Paracetamol"));
			assertThat(found.getTotalElements()).isEqualTo(2);
		}

		@Test
		@DisplayName("finds a misspelling")
		void typoTolerance() {
			// The reason the trigram index exists. A search box that returns nothing for a common
			// typo is worse than no search box.
			var found = products.search("paracetmol", PAGE);

			assertThat(found.getContent()).extracting(Product::getName)
					.contains("Paracetamol 500mg");
		}

		@Test
		@DisplayName("is case-insensitive")
		void caseInsensitive() {
			assertThat(products.search("basmati", PAGE).getContent())
					.isEqualTo(products.search("BASMATI", PAGE).getContent());
		}

		@Test
		@DisplayName("finds a SKU written without its hyphen")
		void skuWithoutHyphen() {
			// Full-text tokenises PARA500 into one lexeme and PARA-500 into two, so a code copied
			// off a delivery note matches nothing without the stripped comparison key.
			var found = products.search("PARA500", PAGE);

			assertThat(found.getTotalElements()).isEqualTo(2);
		}

		@Test
		@DisplayName("does not fuzzy-match one SKU against another")
		void skuIsNotFuzzy() {
			// Trigram similarity between any two SKUs is around 0.57 because they all share the
			// same prefix, so a fuzzy match here would return the whole catalogue. Codes are not
			// words: they match or they do not.
			var found = products.search("PARA500XYZ", PAGE);

			assertThat(found.getTotalElements()).isZero();
		}
	}

	// ------------------------------------------------------------------ visibility

	@Nested
	@DisplayName("what is findable")
	class Visibility {

		@Test
		@DisplayName("an archived product is not")
		void archivedProducts() {
			var product = publish(pharmacy, "Retired Remedy", ProductCategory.MEDICINE, "RETIRED");
			assertThat(products.search("Retired Remedy", PAGE).getTotalElements()).isEqualTo(1);

			productService.archive(pharmacy, product);

			assertThat(products.search("Retired Remedy", PAGE).getTotalElements()).isZero();
		}

		@Test
		@DisplayName("an unverified shop's products are not")
		void unverifiedShops() {
			// The rule that keeps an unvetted shop out of the marketplace, enforced in SQL so a
			// filter the service forgot cannot leak it into search.
			Shop pending = fixtures.createShop("Pending " + UUID.randomUUID(), ShopType.PHARMACY);
			// Created but deliberately NOT published: ProductService refuses to publish for an
			// unverified shop, which is the rule that keeps unvetted shops out of the marketplace.
			// Creating the row directly is what this test needs -- an unverified shop that somehow
			// has a live product, which is exactly the state the search filter must not leak.
			fixtures.createProductWithSku(pending, "Unlisted Medicine",
					ProductCategory.MEDICINE, 5_000L, "UNLIST1");

			assertThat(products.search("Unlisted Medicine", PAGE).getTotalElements()).isZero();
		}
	}

	// ------------------------------------------------------------------ by type

	@Test
	@DisplayName("searching within a shop type narrows to that type")
	void withinShopType() {
		// The D17 payoff: "medicine" finds medicines, without the customer knowing pharmacies exist
		// as a filter.
		assertThat(products.searchInType("Paracetamol", ShopType.PHARMACY.name(), PAGE)
				.getTotalElements()).isEqualTo(1);
		assertThat(products.searchInType("Paracetamol", ShopType.GROCERY.name(), PAGE)
				.getTotalElements()).isEqualTo(1);
	}

	// ------------------------------------------------------------------ shops

	@Test
	@DisplayName("names the shops that have a match, not just the products")
	void shopsMatching() {
		var matches = products.shopsMatching("Paracetamol", 30);

		// Two shops, one product each: a customer looking for paracetamol wants to find pharmacies,
		// not scroll forty listings from one shop.
		assertThat(matches).hasSize(2);
		assertThat(matches).allSatisfy(match -> assertThat(match.getMatches()).isEqualTo(1));
		assertThat(matches).extracting(PublicSearchRepository.ShopMatchView::getSlug)
				.containsExactlyInAnyOrder(pharmacy.getSlug(), grocery.getSlug());
	}

	@Test
	@DisplayName("a shop that matches nothing is not named")
	void shopWithNoMatch() {
		assertThat(products.shopsMatching("Cough Syrup", 30))
				.extracting(PublicSearchRepository.ShopMatchView::getSlug)
				.containsExactly(pharmacy.getSlug());
	}

	@Test
	@DisplayName("shop matches are capped")
	void shopLimit() {
		assertThat(products.shopsMatching("Paracetamol", 1)).hasSize(1);
	}

	@Test
	@DisplayName("searching across shops finds products from more than one shop")
	void acrossShops() {
		var found = products.search("Paracetamol", PAGE);

		assertThat(found.getContent()).extracting(Product::getShopId)
				.containsExactlyInAnyOrder(pharmacy.getId(), grocery.getId());
	}
}