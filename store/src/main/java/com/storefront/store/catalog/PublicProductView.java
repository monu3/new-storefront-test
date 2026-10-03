package com.storefront.store.catalog;

import java.util.UUID;

/**
 * What the storefront is told about a product (public, unauthenticated).
 *
 * <p><b>This record exists because the entity does not belong on a public endpoint.</b> The
 * storefront controller originally returned {@code List<Product>}, letting Jackson serialise the
 * whole entity. Measured on the live API:
 *
 * <pre>
 *   costPriceMinor  <- the shop's cost. A margin leak, on an endpoint that needs no token.
 *   soldCount       <- how many the shop has sold.
 *   viewCount       <- how many people looked.
 *   trackInventory, allowBackorder, featured, purchasable, visible, deleted,
 *   deletedAt, shopCategoryId, seoTitle, seoDescription, createdAt, updatedAt
 * </pre>
 *
 * <p>{@code costPriceMinor} was {@code null} only because the seeded products had no cost price
 * set. A shop that sets one publishes it to everyone. Sales and view counts are commercially
 * sensitive in a way that compounds: a competitor can read a rival's throughput without paying
 * anything.
 *
 * <p>Every field here is something a customer is entitled to see. Anything else is either the
 * shop's business or internal plumbing, and belongs behind {@code @PreAuthorize}.
 *
 * <p>The names are also the contract with the storefront: {@code price}, not
 * {@code basePriceMinor}. The renderer reads {@code price}, and when the entity was serialised
 * directly every product card on the live site rendered "Rs. NaN".
 */
public record PublicProductView(
		UUID id,
		String slug,
		String name,
		String shortDescription,
		/** Product category label, e.g. "Rice". Rendered as the eyebrow above the name. */
		String categoryLabel,
		/** Minor units (paisa). Named `price` because that is what the storefront reads. */
		long price,
		Long compareAt,
		boolean hasDiscount,
		/** First product image, or null. The card renders a placeholder rather than a broken image. */
		String imageUrl,
		/** True when a customer can actually buy it. Never exposes the stock level itself. */
		boolean purchasable) {

	public static PublicProductView from(Product product, String imageUrl) {
		return new PublicProductView(
				product.getId(),
				product.getSlug(),
				product.getName(),
				product.getShortDescription(),
				product.getProductCategory().label(),
				product.getBasePriceMinor(),
				product.getCompareAtMinor(),
				product.hasDiscount(),
				imageUrl,
				product.isVisible());
	}
}
