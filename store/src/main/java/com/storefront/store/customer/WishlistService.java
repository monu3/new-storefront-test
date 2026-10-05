package com.storefront.store.customer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.media.MediaAssetRepository;
import com.storefront.store.security.CurrentUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The customer's wishlist (Phase 5).
 *
 * <p><b>Saving is idempotent.</b> A second tap on a wishlist heart returns the existing row instead
 * of failing on the unique constraint, because "already saved" is not a mistake the customer made —
 * it is what they asked for by tapping again.
 *
 * <p><b>Thumbnails are resolved in bulk.</b> {@code product} has no image column; images are media
 * rows. Resolving them one at a time would make a twenty-item wishlist twenty queries. They are
 * fetched with one query per <em>shop</em> on the page, which is bounded by how many shops a
 * customer's wishlist spans rather than by how many items it holds.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WishlistService {

	/** Bounded so a crafted {@code size} cannot ask for the whole table. */
	private static final int MAX_PAGE = 100;

	private final WishlistRepository wishlist;
	private final ProductRepository products;
	private final MediaAssetRepository media;
	private final CurrentUser currentUser;

	/** A page of the wishlist, with each product's primary image. */
	@Transactional(readOnly = true)
	public Page<WishlistRow> list(int page, int size) {
		UUID userId = currentUser.require().getId();
		Page<WishlistEntry> found = wishlist.browse(userId,
				PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE)));

		return found.map(entry -> withImage(entry, imagesFor(found.getContent())));
	}

	/** Whether these products are already saved — one query for a whole page of cards. */
	@Transactional(readOnly = true)
	public java.util.Set<UUID> savedProductIds(Collection<UUID> productIds) {
		UUID userId = currentUser.require().getId();
		if (productIds == null || productIds.isEmpty()) {
			return java.util.Set.of();
		}
		return wishlist.browse(userId, PageRequest.of(0, MAX_PAGE)).getContent().stream()
				.map(WishlistEntry::productId)
				.filter(productIds::contains)
				.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Saves a product.
	 *
	 * @return whether it was newly saved; false means it was already there
	 */
	@Transactional
	public boolean add(UUID productId) {
		UUID userId = currentUser.require().getId();

		if (!products.existsById(productId)) {
			// Not found rather than silently saved: a wishlist row pointing at a product nothing
			// can render is a row the storefront has to hide anyway.
			throw new IllegalArgumentException("No such product");
		}

		if (wishlist.existsByUserIdAndProductId(userId, productId)) {
			return false;
		}

		wishlist.save(new WishlistItem(userId, productId));
		return true;
	}

	/** Removes a product. Absent is not an error — the end state is the same either way. */
	@Transactional
	public void remove(UUID productId) {
		wishlist.deleteByUserIdAndProductId(currentUser.require().getId(), productId);
	}

	@Transactional(readOnly = true)
	public long count() {
		return wishlist.countByUserId(currentUser.require().getId());
	}

	// ------------------------------------------------------------------ images

	/**
	 * The primary image for each product on the page.
	 *
	 * <p>One media query per distinct shop. A customer's wishlist can span several shops, and media
	 * is scoped to one, so grouping is unavoidable — but it is bounded by the number of shops
	 * rather than the number of items, which is the difference between one query and twenty.
	 */
	private Map<UUID, String> imagesFor(List<WishlistEntry> entries) {
		Map<UUID, String> byProduct = new HashMap<>();
		if (entries.isEmpty()) {
			return byProduct;
		}

		Map<UUID, List<UUID>> productsByShop = new HashMap<>();
		for (WishlistEntry entry : entries) {
			products.findById(entry.productId())
					.ifPresent(product -> productsByShop
							.computeIfAbsent(product.getShopId(), ignored -> new ArrayList<>())
							.add(entry.productId()));
		}

		for (Map.Entry<UUID, List<UUID>> group : productsByShop.entrySet()) {
			media.findByShopIdAndProductIdInOrderBySortOrderAsc(group.getKey(), group.getValue())
					.stream()
					// First row per product wins: the finder is ordered by sort order, so the
					// earliest one is the gallery's primary image.
					.collect(Collectors.toMap(
							asset -> asset.getProductId(),
							asset -> asset.displayUrl(),
							(a, b) -> a))
					.forEach(byProduct::put);
		}
		return byProduct;
	}

	private WishlistRow withImage(WishlistEntry entry, Map<UUID, String> images) {
		return new WishlistRow(
				entry.id(), entry.savedAt(), entry.productId(), entry.productName(),
				entry.productSlug(), entry.priceMinor(),
				entry.shortDescription(), images.get(entry.productId()));
	}
}