package com.storefront.store.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Variant queries.
 *
 * <p>Variants are always reached through their product, so most methods take a {@code productId}
 * rather than a variant id alone. That makes the tenant boundary structural: a query cannot be
 * written that fetches a variant without also naming the product it belongs to, which is one
 * fewer place for a cross-tenant read to hide.
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

	List<ProductVariant> findByProductIdOrderBySortOrderAscNameAsc(UUID productId);

	/** Only the ones a customer can actually buy. */
	List<ProductVariant> findByProductIdAndActiveTrueOrderBySortOrderAscNameAsc(UUID productId);

	Optional<ProductVariant> findByIdAndProductId(UUID id, UUID productId);

	Optional<ProductVariant> findByProductIdAndSku(UUID productId, String sku);

	boolean existsByProductIdAndSku(UUID productId, String sku);

	/**
	 * The cheapest active variant.
	 *
	 * <p>Used to keep the product's mirrored {@code base_price_minor} honest after any price
	 * change. Null when a product has no active variant, which is a legitimate state for a draft
	 * but not for a published product.
	 */
	@Query("""
			SELECT v FROM ProductVariant v
			WHERE v.productId = :productId AND v.active = true
			ORDER BY v.priceMinor ASC, v.id ASC
			LIMIT 1
			""")
	java.util.Optional<ProductVariant> findCheapestActive(@Param("productId") UUID productId);

	/** Cheapest price across active variants, or null. Same purpose as {@link #findCheapestActive}. */
	@Query("""
			SELECT MIN(v.priceMinor) FROM ProductVariant v
			WHERE v.productId = :productId AND v.active = true
			""")
	Long findMinimumActivePrice(@Param("productId") UUID productId);

	List<ProductVariant> findByIdIn(List<UUID> ids);

	long countByProductIdAndActiveTrue(UUID productId);

	/** For a shop-type change check: variants that exist even on an archived product. */
	long countByProductId(UUID productId);
}
