package com.storefront.store.media;

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
}
