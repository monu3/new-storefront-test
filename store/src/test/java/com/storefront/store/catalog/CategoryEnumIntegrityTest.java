package com.storefront.store.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Structural integrity of the hardcoded enums (plan decisions D17 / D18).
 *
 * <p>These tests are the reason the enums are safe to treat as a contract. If they are green,
 * the frontend can rely on every category being claimed by at least one shop type, and no
 * shop type offering a category that does not exist.
 */
class CategoryEnumIntegrityTest {

	/**
	 * Every (category, shopType) pair the type does not claim.
	 *
	 * <p>This is the exhaustive cross-product: it proves the validator rejects a mismatched
	 * pairing for all 19 shop types against all 120 categories, not just a hand-picked sample.
	 * A hand-picked sample would let a newly added category slip through untested.
	 *
	 * <p>Must live on the outer class: JUnit's {@code @MethodSource} factory is resolved on the
	 * declaring class, so a static method inside a {@code @Nested} class is not found.
	 */
	static List<Object[]> everyCategoryAndNonOwningType() {
		List<Object[]> rows = new ArrayList<>();
		for (ShopType type : ShopType.values()) {
			for (ProductCategory category : ProductCategory.values()) {
				if (!type.allows(category)) {
					rows.add(new Object[]{category, type});
				}
			}
		}
		return rows;
	}

	@Nested
	@DisplayName("every category belongs to at least one shop type")
	class Claiming {

		@Test
		@DisplayName("no category is orphaned")
		void noOrphanedCategories() {
			Set<ProductCategory> unclaimed = ProductCategory.unclaimed();

			assertThat(unclaimed)
					.as("these categories are declared but no ShopType allows them, so they could "
							+ "never be used. Either wire them into a ShopType or delete them")
					.isEmpty();
		}

		@Test
		@DisplayName("the full enum is claimed")
		void fullCoverage() {
			assertThat(ProductCategory.allClaimed())
					.containsExactlyInAnyOrder(ProductCategory.values());
		}

		@Test
		@DisplayName("each shop type offers at least one category")
		void everyShopTypeHasCategories() {
			for (ShopType type : ShopType.values()) {
				assertThat(type.categories())
						.as("%s must offer at least one product category", type)
						.isNotEmpty();
			}
		}
	}

	@Nested
	@DisplayName("a category is rarely valid in more than one vertical")
	class Overlap {

		/**
		 * A handful of categories legitimately span two verticals (BABY_CARE is valid in both a
		 * pharmacy and a baby shop). This pins the list so an accidental broadening is visible
		 * in review rather than discovered when a pharmacy starts selling kurtas.
		 */
		/**
		 * Categories valid in more than one vertical.
		 *
		 * <p>Only {@code BABY_CARE} qualifies: it is offered by both PHARMACY and BABY_SHOP.
		 * {@code PERSONAL_CARE} looks like it should bridge too, but it appears only in
		 * PHARMACY, so a pharmacy selling personal care cannot move to a baby shop.
		 */
		private static final Set<ProductCategory> INTENTIONALLY_SHARED = Set.of(
				ProductCategory.BABY_CARE);

		@Test
		@DisplayName("only the expected categories are shared across shop types")
		void onlyExpectedOverlap() {
			Map<ProductCategory, List<ShopType>> owners = ownersByCategory();

			Set<ProductCategory> shared = owners.entrySet().stream()
					.filter(e -> e.getValue().size() > 1)
					.map(Map.Entry::getKey)
					.collect(Collectors.toSet());

			// Exact equality, not a subset check. A subset assertion would keep passing if the
			// shared set silently shrank, which is exactly the drift worth catching: a category
			// that quietly stops bridging makes type changes unreachable without anyone noticing.
			assertThat(shared)
					.as("categories valid in more than one shop type")
					.containsExactlyInAnyOrderElementsOf(INTENTIONALLY_SHARED);
		}

		@Test
		@DisplayName("the shared category is offered by exactly the two expected shop types")
		void sharedCategoryHasExpectedOwners() {
			Map<ProductCategory, List<ShopType>> owners = ownersByCategory();

			assertThat(owners.get(ProductCategory.BABY_CARE))
					.containsExactlyInAnyOrder(ShopType.PHARMACY, ShopType.BABY_SHOP);
			assertThat(owners.get(ProductCategory.PERSONAL_CARE))
					.as("PERSONAL_CARE is pharmacy-only despite both being care verticals")
					.containsExactly(ShopType.PHARMACY);
		}

		private Map<ProductCategory, List<ShopType>> ownersByCategory() {
			Map<ProductCategory, List<ShopType>> owners = new java.util.LinkedHashMap<>();
			for (ProductCategory c : ProductCategory.values()) {
				owners.put(c, new ArrayList<>());
			}
			for (ShopType type : ShopType.values()) {
				for (ProductCategory c : type.categories()) {
					owners.get(c).add(type);
				}
			}
			return owners;
		}
	}

	@Nested
	@DisplayName("cross-type validation")
	class Validation {

		private final CategoryValidator validator = new CategoryValidator();

		@Test
		@DisplayName("a pharmacy may list MEDICINE")
		void pharmacyAllowsMedicine() {
			validator.validate(ShopType.PHARMACY, ProductCategory.MEDICINE);
			assertThat(validator.isValid(ShopType.PHARMACY, ProductCategory.MEDICINE)).isTrue();
		}

		@Test
		@DisplayName("a pharmacy may NOT list KIDS_CLOTHING")
		void pharmacyRejectsClothing() {
			assertThat(validator.isValid(ShopType.PHARMACY, ProductCategory.KIDS_CLOTHING)).isFalse();

			assertThatThrownBy(() -> validator.validate(ShopType.PHARMACY, ProductCategory.KIDS_CLOTHING))
					.isInstanceOf(CategoryValidator.InvalidCategoryException.class)
					.hasMessageContaining("KIDS_CLOTHING")
					.hasMessageContaining("PHARMACY");
		}

		@ParameterizedTest
		@EnumSource(ShopType.class)
		@DisplayName("every category a shop type offers passes validation")
		void selfConsistent(ShopType type) {
			for (ProductCategory category : type.categories()) {
				validator.validate(type, category);
			}
		}

		@ParameterizedTest
		@MethodSource("com.storefront.store.catalog.CategoryEnumIntegrityTest#everyCategoryAndNonOwningType")
		@DisplayName("a category is rejected for any shop type that does not offer it")
		void rejectsNonOwningType(ProductCategory category, ShopType type) {
			assertThat(validator.isValid(type, category)).isFalse();
		}

		@Test
		@DisplayName("null inputs are rejected rather than NPE")
		void nullSafe() {
			assertThatThrownBy(() -> validator.validate(null, ProductCategory.MEDICINE))
					.isInstanceOf(CategoryValidator.InvalidCategoryException.class);
			assertThatThrownBy(() -> validator.validate(ShopType.PHARMACY, null))
					.isInstanceOf(CategoryValidator.InvalidCategoryException.class);
			assertThat(validator.isValid(null, null)).isFalse();
		}
	}

	@Nested
	@DisplayName("changing a shop type")
	class TypeChange {

		private final CategoryValidator validator = new CategoryValidator();

		@Test
		@DisplayName("a FASHION shop cannot become PHARMACY while it sells clothing")
		void blocksOrphaningChange() {
			Set<ProductCategory> existing = EnumSet.of(
					ProductCategory.KIDS_CLOTHING, ProductCategory.FOOTWEAR);

			Set<ProductCategory> invalid = validator.invalidAfterTypeChange(ShopType.PHARMACY, existing);

			assertThat(invalid)
					.as("this change would orphan every product, so it must be reported")
					.containsExactlyInAnyOrder(ProductCategory.KIDS_CLOTHING, ProductCategory.FOOTWEAR);
		}

		@Test
		@DisplayName("a GROCERY shop may become GROCERY_BEVERAGES when it only sells beverages")
		void allowsCompatibleChange() {
			Set<ProductCategory> existing = EnumSet.of(ProductCategory.JUICE, ProductCategory.WATER);

			assertThat(validator.invalidAfterTypeChange(ShopType.GROCERY_BEVERAGES, existing)).isEmpty();
		}

		@Test
		@DisplayName("shared categories survive a type change")
		void sharedCategoriesSurvive() {
			Set<ProductCategory> existing = EnumSet.of(ProductCategory.BABY_CARE);
			assertThat(validator.invalidAfterTypeChange(ShopType.BABY_SHOP, existing)).isEmpty();
		}
	}

	@Nested
	@DisplayName("code and slug parsing")
	class Parsing {

		@ParameterizedTest
		@EnumSource(ShopType.class)
		@DisplayName("every shop type round-trips through its code, case-insensitively")
		void shopTypeRoundTrip(ShopType type) {
			assertThat(ShopType.fromCode(type.name())).isEqualTo(type);
			assertThat(ShopType.fromCode(type.name().toLowerCase())).isEqualTo(type);
			assertThat(ShopType.fromCode("  " + type.name() + " ")).isEqualTo(type);
		}

		@ParameterizedTest
		@EnumSource(ProductCategory.class)
		@DisplayName("every category round-trips and produces a URL-safe slug")
		void categoryRoundTrip(ProductCategory category) {
			assertThat(ProductCategory.fromCode(category.name())).isEqualTo(category);
			assertThat(category.slug())
					.isEqualTo(category.slug().toLowerCase())
					.matches("[a-z0-9-]+");
		}

		@Test
		@DisplayName("unknown, null and blank codes return null rather than throwing")
		void unknownIsNull() {
			assertThat(ShopType.fromCode("NOT_A_TYPE")).isNull();
			assertThat(ShopType.fromCode(null)).isNull();
			assertThat(ShopType.fromCode("  ")).isNull();
			assertThat(ProductCategory.fromCode("NOT_A_CATEGORY")).isNull();
		}
	}

	@Nested
	@DisplayName("presentation metadata")
	class Metadata {

		@ParameterizedTest
		@EnumSource(ShopType.class)
		@DisplayName("every shop type has a label and an icon")
		void shopTypesHaveLabels(ShopType type) {
			assertThat(type.label()).isNotBlank();
			assertThat(type.icon()).isNotBlank();
			assertThat(type.iconGroup()).isNotNull();
		}

		@ParameterizedTest
		@EnumSource(ProductCategory.class)
		@DisplayName("every category has a label")
		void categoriesHaveLabels(ProductCategory category) {
			assertThat(category.label()).isNotBlank();
		}

		@Test
		@DisplayName("no two shop types share a label")
		void labelsUnique() {
			List<String> labels = java.util.Arrays.stream(ShopType.values())
					.map(ShopType::label).toList();
			assertThat(labels).doesNotHaveDuplicates();
		}
	}
}
