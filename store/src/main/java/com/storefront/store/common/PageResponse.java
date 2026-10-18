package com.storefront.store.common;

import java.util.List;

/**
 * One page of results, for every list endpoint in this platform.
 *
 * <p><b>One shape, not one per controller.</b> Each controller declaring its own identical
 * {@code PageResponse} record is duplication that survives review and then drifts: a field is added
 * to one endpoint's response and the front end handles both shapes, forever.
 *
 * <p>{@code totalElements} rather than a {@code total} boolean, so an empty page can still say how
 * many there are — which is the difference between "no results" and "you are past the end".
 */
public record PageResponse<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	/** A single empty page, for a query that returned nothing to page. */
	public static <T> PageResponse<T> empty() {
		return new PageResponse<>(List.of(), 0, 0, 0, 0);
	}

	/** How many pages there would be if the limit changed — used by the UI's page-size selector. */
	public int pages(int newSize) {
		if (newSize < 1) {
			throw new IllegalArgumentException("Page size must be at least 1, not " + newSize);
		}
		return totalElements == 0 ? 1 : (int) Math.ceil((double) totalElements / newSize);
	}
}
