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

	/**
	 * A variant of this shop, by SKU.
	 *
	 * <p>One query, and the reason it exists is a performance bug: CSV import originally resolved
	 * a SKU by loading every product in the shop and then querying each one's variants. That is
	 * O(products) per CSV row, so importing 1,000 SKUs issued roughly a million queries and did not
	 * finish. The Phase 2 exit criterion is a 500-SKU catalogue, which makes this the difference
	 * between a feature that works and one that hangs.
	 *
	 * <p>Case-insensitive because SKUs get typed by hand and {@code sku-0042} is the same code as
	 * {@code SKU-0042}.
	 *
	 * <p><b>Returns up to two matches, not one.</b> A SKU is unique per product, not per shop, so
	 * two products in one shop can carry the same code — and when they do, a CSV import that
	 * silently took the first would report the row as applied while quietly leaving the other
	 * untouched. The importer checks the size and refuses the row with a reason the owner can
	 * act on, which is honest; guessing is not. Found by the Phase 2 exit criterion against a
	 * catalogue that had picked up duplicate SKUs.
	 *
	 * @return zero, one or two matches; two means the SKU is ambiguous in this shop
	 */
	@org.springframework.data.jpa.repository.Query(value = """
			SELECT v.* FROM product_variant v
			JOIN product p ON p.id = v.product_id
			WHERE p.shop_id = :shopId
			  AND p.deleted_at IS NULL
			  AND lower(v.sku) = lower(:sku)
			LIMIT 2
			""", nativeQuery = true)
	List<ProductVariant> findBySkuInShop(@Param("shopId") UUID shopId, @Param("sku") String sku);

	/**
	 * A variant of this shop, by name.
	 *
	 * <p>Same reason as {@link #findBySkuInShop}: a supplier's sheet may carry no SKU, and the
	 * previous implementation loaded the whole catalogue to find one variant by name.
	 */
	@org.springframework.data.jpa.repository.Query(value = """
			SELECT v.* FROM product_variant v
			JOIN product p ON p.id = v.product_id
			WHERE p.shop_id = :shopId
			  AND p.deleted_at IS NULL
			  AND lower(v.name) = lower(:name)
			LIMIT 2
			""", nativeQuery = true)
	List<ProductVariant> findByNameInShop(@Param("shopId") UUID shopId, @Param("name") String name);

	long countByProductIdAndActiveTrue(UUID productId);

	/** For a shop-type change check: variants that exist even on an archived product. */
	long countByProductId(UUID productId);
}
