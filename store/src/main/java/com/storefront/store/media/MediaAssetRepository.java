package com.storefront.store.media;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Media queries.
 *
 * <p>Every listing method takes a {@code shopId} explicitly. That is redundant with the column
 * and is deliberate: it means a query cannot be written that returns another shop's images
 * without the tenancy having been mentioned at the call site.
 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

	Optional<MediaAsset> findByPublicId(String publicId);

	boolean existsByPublicId(String publicId);

	List<MediaAsset> findByShopIdOrderByCreatedAtDesc(UUID shopId);

	List<MediaAsset> findByShopIdAndUsageOrderByCreatedAtDesc(UUID shopId, MediaAsset.Usage usage);

	List<MediaAsset> findByShopIdAndProductIdOrderBySortOrderAsc(UUID shopId, UUID productId);

	List<MediaAsset> findByShopIdAndProductIdAndUsageOrderBySortOrderAsc(
			UUID shopId, UUID productId, MediaAsset.Usage usage);

	long countByShopId(UUID shopId);

	long countByProductId(UUID productId);

	/**
	 * Every asset attached to a product, oldest first, so the first row per product is its
	 * primary image.
	 *
	 * <p>One query for a whole storefront page. Resolving each product's image separately is 24
	 * queries on a single page view, and the storefront is server-rendered on every request.
	 */
	List<MediaAsset> findByShopIdAndProductIdIsNotNullOrderByIdAsc(UUID shopId);

	/**
	 * The images for a whole page of products, in one query.
	 *
	 * <p>Exists so a list of product cards (a wishlist, a catalogue page) costs one media query
	 * rather than one per row. Without it, a twenty-item wishlist is twenty queries, which is the
	 * difference between a page that opens and one that does not.
	 *
	 * <p>Scoped by shop as well, because a product id alone would return another shop's images to a
	 * caller who has no business seeing them.
	 */
	List<MediaAsset> findByShopIdAndProductIdInOrderBySortOrderAsc(UUID shopId, Collection<UUID> productIds);
}
