package com.storefront.store.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * A wishlist row as the storefront renders it: the product plus its resolved thumbnail.
 *
 * <p>Separate from {@link WishlistEntry} because the two come from different places — the entry is
 * what the join projects, this is what the UI needs — and conflating them would put an
 * always-null {@code imageUrl} on the projection or, worse, quietly start joining media into the
 * wishlist query and multiply the rows for any product with more than one image.
 */
public record WishlistRow(
		UUID id,
		Instant savedAt,
		UUID productId,
		String productName,
		String productSlug,
		long priceMinor,
		String shortDescription,
		/** The product's primary image, or null. The card renders a placeholder, never a broken image. */
		String imageUrl) {
}