package com.storefront.store.customer;

import java.util.UUID;

/**
 * The visible rating summary for a product.
 *
 * <p>A top-level type for the same reason as {@link WishlistEntry}: JPQL resolves a {@code select new}
 * target by binary name, so a nested record would need {@code ...$RatingSummary} written into the
 * query string.
 *
 * @param average the mean of the visible ratings, 0.0 when there are none — never null, because a
 *        card has to render something for a product nobody has reviewed
 * @param count how many visible reviews produced it; {@code average} is meaningless without it
 */
public record RatingSummary(UUID productId, double average, long count) {
}