package com.storefront.store.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The single enforcement point for the (shopType, category) rule (plan decision D18).
 *
 * <p>Called from the product service on write, from the shop service when a shop type changes,
 * and from the meta controller. Keeping the rule in one class is what stops the shop-type
 * dropdown and the product-category dropdown from drifting apart.
 */
@Component
public class CategoryValidator {

	/**
	 * Throws when the category is not valid for the shop type.
	 *
	 * @throws InvalidCategoryException if the pair is not permitted
	 */
	public void validate(ShopType shopType, ProductCategory category) {
		if (shopType == null) {
			throw new InvalidCategoryException("A shop type is required");
		}
		if (category == null) {
			throw new InvalidCategoryException("A product category is required");
		}
		if (!shopType.allows(category)) {
			throw new InvalidCategoryException(
					"Category %s is not valid for shop type %s".formatted(category, shopType));
		}
	}

	/** Non-throwing form, for use in filters and query predicates. */
	public boolean isValid(ShopType shopType, ProductCategory category) {
		return shopType != null && shopType.allows(category);
	}

	/** The categories a shop of the given type may use, in declaration order. */
	public Set<ProductCategory> allowedFor(ShopType shopType) {
		return shopType == null ? Set.of() : shopType.categories();
	}

	/**
	 * Used when a shop changes type: any existing product whose category is not valid under
	 * the new type would be orphaned, so the change is rejected rather than silently accepted.
	 *
	 * @return the categories that would become invalid
	 */
	public Set<ProductCategory> invalidAfterTypeChange(ShopType newType, Set<ProductCategory> existing) {
		return existing.stream()
				.filter(c -> !isValid(newType, c))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/**
	 * The shop types this shop can move to right now, given the categories its existing
	 * products use.
	 *
	 * <p>This is what makes a self-serve type change safe. Rather than blocking every change
	 * and forcing a support ticket, the UI offers only the types that are actually reachable:
	 * a grocery shop selling nothing but RICE and LENTIL can move to a narrower grocery type,
	 * while one also selling SNACKS cannot. Nothing is ever silently recategorised and no
	 * product is ever stranded in a vertical that no longer permits its category.
	 *
	 * <p>A shop with no products can reach any type, which is correct: the constraint only
	 * exists to protect inventory.
	 *
	 * <p>The current type is excluded — a shop is not "changing" to what it already is.
	 */
	public List<ShopType> reachableTypes(ShopType currentType, Set<ProductCategory> existingCategories) {
		return Arrays.stream(ShopType.values())
				.filter(candidate -> candidate != currentType)
				.filter(candidate -> invalidAfterTypeChange(candidate, existingCategories).isEmpty())
				.toList();
	}

	/**
	 * Whether a shop with these categories may switch to the given type.
	 *
	 * <p>Used by the change endpoint. An admin may bypass this via a separate force path so a
	 * shop stuck with a mistaken signup can be corrected, at the cost of products needing
	 * manual recategorising.
	 */
	public boolean canChangeTo(ShopType target, Set<ProductCategory> existingCategories) {
		return invalidAfterTypeChange(target, existingCategories).isEmpty();
	}

	/** Signals a category that does not belong to the shop's type. Maps to HTTP 400. */
	public static class InvalidCategoryException extends RuntimeException {
		public InvalidCategoryException(String message) {
			super(message);
		}
	}

	/**
	 * Signals a shop type change that would strand existing products. Maps to HTTP 409, not
	 * 400: the request is well-formed, it just conflicts with the shop's current data, and the
	 * response tells the owner exactly what to fix.
	 */
	public static class ShopTypeChangeConflictException extends RuntimeException {

		private final ShopType targetType;
		private final Set<ProductCategory> blockingCategories;

		public ShopTypeChangeConflictException(ShopType targetType, Set<ProductCategory> blockingCategories) {
			super("Cannot change shop type to %s: %d existing categor%s would no longer be valid (%s)"
					.formatted(
							targetType,
							blockingCategories.size(),
							blockingCategories.size() == 1 ? "y" : "ies",
							blockingCategories.stream().map(ProductCategory::label).sorted().toList()));
			this.targetType = targetType;
			this.blockingCategories = Set.copyOf(blockingCategories);
		}

		public ShopType targetType() {
			return targetType;
		}

		/** The categories the owner must recategorise or remove first. */
		public Set<ProductCategory> blockingCategories() {
			return blockingCategories;
		}
	}
}
