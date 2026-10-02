package com.storefront.store.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rules for a shop owner changing their own shop type.
 *
 * <p>The invariant: <b>a type change never strands a product.</b> Either every category the
 * shop already uses stays valid under the new type, or the change is refused and the owner is
 * told which products to fix. There is no third path, because a silently recategorised product
 * would list under a category its own vertical does not offer and would vanish from the
 * storefront's category navigation, which is derived from the shop type.
 */
class ShopTypeChangeTest {

    private final CategoryValidator validator = new CategoryValidator();

    /**
     * Every (category, targetType) pair the target does not permit — the full cross-product.
     *
     * <p>Declared on the outer class because JUnit resolves {@code @MethodSource} against the
     * class owning the test method, which is the nested one here; a factory declared on a
     * nested class is not found even when referenced by its simple name.
     */
    static List<Object[]> everyCategoryWithNonOwningType() {
        List<Object[]> rows = new ArrayList<>();
        for (ShopType target : ShopType.values()) {
            for (ProductCategory category : ProductCategory.values()) {
                if (!target.allows(category)) {
                    rows.add(new Object[] { category, target });
                }
            }
        }
        return rows;
    }

    private static Set<ProductCategory> categories(ProductCategory... values) {
        return EnumSet.copyOf(List.of(values));
    }

    @Nested
    @DisplayName("reachable types")
    class Reachable {

        @Test
        @DisplayName("a shop with no products can reach every other type")
        void emptyShopReachesEverything() {
            List<ShopType> reachable = validator.reachableTypes(ShopType.PHARMACY, Set.of());

            // Everything except the current type. The constraint exists only to protect
            // inventory, so a shop with no products is unconstrained.
            assertThat(reachable)
                    .hasSize(ShopType.values().length - 1)
                    .doesNotContain(ShopType.PHARMACY)
                    .contains(ShopType.FASHION, ShopType.GROCERY, ShopType.BEAUTY_SERVICES);
        }

        @Test
        @DisplayName("the current type is never offered as a change")
        void excludesCurrentType() {
            for (ShopType type : ShopType.values()) {
                assertThat(validator.reachableTypes(type, Set.of()))
                        .as("%s must not be offered as a change to itself", type)
                        .doesNotContain(type);
            }
        }

        @ParameterizedTest
        @CsvSource({
                "GROCERY, GROCERY_BEVERAGES",
                "PHARMACY, COSMETICS",
                "PLANT_SHOP, FLOWERS",
                "TECH, ELECTRONICS",
        })
        @DisplayName("a shop whose products all fit the target can move to it")
        void compatibleChangeAllowed(String fromCode, String toCode) {
            ShopType from = ShopType.valueOf(fromCode);
            ShopType to = ShopType.valueOf(toCode);

            // Built from the target type's own categories, so the test asserts the real
            // contract rather than a hand-picked example that could drift from the enums.
            Set<ProductCategory> products = categories(to.categories().toArray(new ProductCategory[0]));

            assertThat(validator.reachableTypes(from, products))
                    .as("%s should be able to become %s when it sells only those categories",
                            from, to)
                    .contains(to);
        }

        @Test
        @DisplayName("a pharmacy selling medicine cannot become a fashion shop")
        void incompatibleChangeBlocked() {
            Set<ProductCategory> products = categories(
                    ProductCategory.MEDICINE, ProductCategory.SUPPLEMENT);

            assertThat(validator.reachableTypes(ShopType.PHARMACY, products))
                    .doesNotContain(ShopType.FASHION, ShopType.FURNITURE, ShopType.TOYS);
        }

        @Test
        @DisplayName("a single mismatched product is enough to block the change")
        void oneStrayProductBlocksTheChange() {
            Set<ProductCategory> products = categories(
                    ProductCategory.MEDICINE, ProductCategory.PERSONAL_CARE);

            assertThat(validator.reachableTypes(ShopType.PHARMACY, products))
                    .doesNotContain(ShopType.FASHION);
        }

        @Test
        @DisplayName("shared categories keep a change reachable")
        void sharedCategoriesUnblockTheChange() {
            // BABY_CARE is valid in both PHARMACY and BABY_SHOP.
            assertThat(validator.reachableTypes(ShopType.PHARMACY, categories(ProductCategory.BABY_CARE)))
                    .contains(ShopType.BABY_SHOP);
        }

        @Test
        @DisplayName("no type reported reachable would strand a product")
        void everyReachableTypeIsActuallySafe() {
            // The structural check that makes the "reachable" label trustworthy rather than
            // merely plausible: for a spread of real product sets, nothing the validator offers
            // can strand a product.
            List<Set<ProductCategory>> scenarios = List.of(
                    Set.of(),
                    categories(ProductCategory.MEDICINE),
                    categories(ProductCategory.RICE, ProductCategory.LENTIL, ProductCategory.SPICES),
                    categories(ProductCategory.INDOOR_PLANT, ProductCategory.SEED),
                    categories(ProductCategory.SMARTPHONE, ProductCategory.SIM_CARD),
                    categories(ProductCategory.SKINCARE, ProductCategory.MAKEUP));

            for (ShopType current : ShopType.values()) {
                for (Set<ProductCategory> products : scenarios) {
                    for (ShopType target : validator.reachableTypes(current, products)) {
                        assertThat(validator.invalidAfterTypeChange(target, products))
                                .as("%s was reported reachable for a shop selling %s but strands products",
                                        target, products)
                                .isEmpty();
                    }
                }
            }
        }

        @Test
        @DisplayName("a type is never hidden without a reason")
        void reachableAndBlockedAreDisjoint() {
            Set<ProductCategory> products = categories(
                    ProductCategory.RICE, ProductCategory.LENTIL, ProductCategory.SPICES,
                    ProductCategory.TEA);

            List<ShopType> reachable = validator.reachableTypes(ShopType.GROCERY, products);

            for (ShopType candidate : ShopType.values()) {
                if (candidate == ShopType.GROCERY || reachable.contains(candidate)) {
                    continue;
                }
                assertThat(validator.invalidAfterTypeChange(candidate, products))
                        .as("%s is neither reachable nor justified as blocked", candidate)
                        .isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("canChangeTo")
    class CanChangeTo {

        @Test
        @DisplayName("permits a compatible change")
        void permitsCompatible() {
            assertThat(validator.canChangeTo(ShopType.PHARMACY, categories(ProductCategory.MEDICINE)))
                    .isTrue();
        }

        @Test
        @DisplayName("refuses an incompatible change")
        void refusesIncompatible() {
            assertThat(validator.canChangeTo(ShopType.FASHION, categories(ProductCategory.MEDICINE)))
                    .isFalse();
        }

        @ParameterizedTest
        @MethodSource("com.storefront.store.catalog.ShopTypeChangeTest#everyCategoryWithNonOwningType")
        @DisplayName("any category the target does not permit blocks the change")
        void anyStrayCategoryBlocks(ProductCategory category, ShopType target) {
            assertThat(validator.canChangeTo(target, categories(category))).isFalse();
        }

        @Test
        @DisplayName("an empty product set always permits the change")
        void emptyAlwaysPermits() {
            for (ShopType target : ShopType.values()) {
                assertThat(validator.canChangeTo(target, Set.of())).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("conflict reporting")
    class ConflictReporting {

        @Test
        @DisplayName("the exception names the categories the owner must fix")
        void namesBlockingCategories() {
            var ex = new CategoryValidator.ShopTypeChangeConflictException(
                    ShopType.FASHION,
                    categories(ProductCategory.MEDICINE, ProductCategory.MAKEUP));

            assertThat(ex.getMessage())
                    .contains("FASHION")
                    .contains("Medicine");
        }

        @Test
        @DisplayName("a single blocking category reads grammatically")
        void singularGrammar() {
            var ex = new CategoryValidator.ShopTypeChangeConflictException(
                    ShopType.FASHION, categories(ProductCategory.MEDICINE));

            assertThat(ex.getMessage())
                    .as("must say 'category' not 'categories' for one item")
                    .contains("1 existing category would")
                    .doesNotContain("categories would");

            assertThat(ex.blockingCategories()).containsExactly(ProductCategory.MEDICINE);
            assertThat(ex.targetType()).isEqualTo(ShopType.FASHION);
        }

        @Test
        @DisplayName("blocking categories are exposed for the API response")
        void exposesBlockingCategories() {
            var ex = new CategoryValidator.ShopTypeChangeConflictException(
                    ShopType.TOYS,
                    categories(ProductCategory.MEDICINE, ProductCategory.SUPPLEMENT));

            assertThat(ex.blockingCategories())
                    .containsExactlyInAnyOrder(ProductCategory.MEDICINE, ProductCategory.SUPPLEMENT);
        }
    }

    @Nested
    @DisplayName("the storefront's category navigation follows the type")
    class Navigation {

        @Test
        @DisplayName("the new type's navigation includes every category still in use")
        void navigationMatchesType() {
            Set<ProductCategory> products = categories(ProductCategory.ROSE, ProductCategory.ORCHID);

            ShopType target = validator.reachableTypes(ShopType.PLANT_SHOP, products).stream()
                    .filter(t -> t.allows(ProductCategory.ROSE))
                    .findFirst()
                    .orElseThrow();

            assertThat(target.categories())
                    .as("the rebuilt navigation must cover every category still in use")
                    .containsAll(products);
        }

        @ParameterizedTest
        @EnumSource(ShopType.class)
        @DisplayName("no type offers a category it does not allow")
        void navigationExcludesForeignCategories(ShopType type) {
            for (ProductCategory offered : type.categories()) {
                assertThat(validator.isValid(type, offered))
                        .as("%s lists %s in its navigation but does not allow it", type, offered)
                        .isTrue();
            }
        }
    }
}
