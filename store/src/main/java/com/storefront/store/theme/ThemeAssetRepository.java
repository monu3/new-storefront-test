package com.storefront.store.theme;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A shop's theme assets.
 *
 * <p>Lookups are by handle rather than id, because that is how a section refers to an asset.
 */
public interface ThemeAssetRepository extends JpaRepository<ThemeAsset, UUID> {

	Optional<ThemeAsset> findByShopIdAndKindAndHandle(UUID shopId, ThemeAsset.Kind kind, String handle);

	List<ThemeAsset> findByShopIdOrderByKindAscHandleAsc(UUID shopId);

	List<ThemeAsset> findByShopIdAndKindOrderByHandleAsc(UUID shopId, ThemeAsset.Kind kind);

	Optional<ThemeAsset> findByIdAndShopId(UUID id, UUID shopId);

	long countByShopId(UUID shopId);

	/**
	 * Every handle a shop owns, across kinds.
	 *
	 * <p>Used by the theme validator to report which section references a handle that does not
	 * exist, which is a warning rather than an error — a shop can lay out a page before uploading
	 * its photograph.
	 */
	List<ThemeAsset> findByShopId(UUID shopId);
}
