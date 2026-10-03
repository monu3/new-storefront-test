package com.storefront.store.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stock queries.
 *
 * <p>The reservation-sensitive reads are plain SELECTs, not {@code FOR UPDATE}. The overselling
 * guard is a compare-and-set in {@link InventoryService}: the UPDATE states the precondition in
 * its WHERE clause, so two concurrent orders for the last unit cannot both succeed. That closes
 * the race a read-then-write would leave open.
 */
public interface InventoryRepository extends JpaRepository<Inventory, InventoryId> {

	Optional<Inventory> findByIdProductIdAndIdVariantId(UUID productId, UUID variantId);

	List<Inventory> findByIdProductId(UUID productId);

	long countByQuantityOnHandLessThanEqual(int threshold);

	/**
	 * Variants at or below their low-stock threshold, for the shop's stock warning screen.
	 */
	@Query("""
			SELECT i FROM Inventory i
			WHERE i.quantityOnHand - i.quantityReserved <= i.lowStockThreshold
			ORDER BY (i.quantityOnHand - i.quantityReserved) ASC
			""")
	List<Inventory> findLowStock();

	/**
	 * Low-stock rows for one shop.
	 *
	 * <p>Scoped in SQL through the product's shop_id, so a shop's screen cannot surface another
	 * shop's variants. Filtering the global low-stock list in memory would mean loading every
	 * shop's rows to throw most of them away.
	 */
	@Query("""
			SELECT i FROM Inventory i
			WHERE (i.quantityOnHand - i.quantityReserved) <= i.lowStockThreshold
			  AND i.id.productId IN (
				SELECT p.id FROM com.storefront.store.catalog.Product p WHERE p.shopId = :shopId)
			ORDER BY (i.quantityOnHand - i.quantityReserved) ASC
			""")
	List<Inventory> findLowStockForShop(@Param("shopId") UUID shopId);

	/**
	 * Reserves stock with a compare-and-set.
	 *
	 * <p>The precondition is in the WHERE clause, so two concurrent orders for the last unit
	 * cannot both succeed — one gets a row count of 1, the other 0, and the second caller is told
	 * the stock is gone. A read-then-write would let both succeed and oversell.
	 *
	 * <p>Returning the affected row count is the whole point: it is what distinguishes "I
	 * reserved it" from "someone else got there first".
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE Inventory i
			SET i.quantityReserved = i.quantityReserved + :qty
			WHERE i.id.productId = :productId
			  AND i.id.variantId = :variantId
			  AND i.quantityOnHand - i.quantityReserved >= :qty
			""")
	int reserveAtomically(@Param("productId") UUID productId, @Param("variantId") UUID variantId,
			@Param("qty") int qty);

	/** Aggregate view for a shop's dashboard: total units and value at cost. */
	@Query("""
			SELECT COALESCE(SUM(i.quantityOnHand - i.quantityReserved), 0)
			FROM Inventory i
			WHERE i.id.productId IN (
				SELECT p.id FROM com.storefront.store.catalog.Product p WHERE p.shopId = :shopId)
			""")
	long totalAvailableUnits(@Param("shopId") UUID shopId);
}
