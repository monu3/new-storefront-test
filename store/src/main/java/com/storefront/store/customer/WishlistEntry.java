package com.storefront.store.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * A wishlist row, flattened for the storefront.
 *
 * <p><b>A top-level type, not a nested record.</b> JPQL's {@code select new ...} resolves the
 * target class by <em>binary</em> name, so a nested {@code WishlistRepository.Entry} would have to
 * be written {@code WishlistRepository$Entry} in the query string. That is a detail of the class
 * file format leaking into SQL text, and it fails at runtime rather than at compile time — which is
 * exactly the sort of thing a record nested in a repository seems to invite.
 *
 */
public record WishlistEntry(
		UUID id,
		Instant savedAt,
		UUID productId,
		String productName,
		String productSlug,
		long priceMinor,
		String shortDescription) {
}