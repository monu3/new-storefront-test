package com.storefront.store.order;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Order-line queries.
 *
 * <p>Every method takes an {@code orderId}. Lines are meaningless on their own, and naming the
 * order at the call site makes a missing tenant or owner check visible rather than implicit.
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

	List<OrderItem> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

	List<OrderItem> findByOrderId(UUID orderId);

	long countByOrderId(UUID orderId);

	/** What customers who bought this also bought. Phase 5. */
	List<OrderItem> findByProductId(UUID productId);

	/**
	 * Line counts for a page of orders, in one query.
	 *
	 * <p>The order board shows an item count on every row, and counting per order would be an N+1
	 * on the screen a shop owner looks at all day. One grouped query for the page, joined in
	 * memory.
	 *
	 * <p>Orders with no lines are simply absent from the result, so callers must treat a missing
	 * key as zero rather than assuming every order appears.
	 *
	 * @return rows of {@code [orderId, lineCount]}
	 */
	@org.springframework.data.jpa.repository.Query("""
			SELECT i.orderId, COUNT(i) FROM OrderItem i
			WHERE i.orderId IN :orderIds
			GROUP BY i.orderId
			""")
	List<Object[]> countLinesForOrders(
			@org.springframework.data.repository.query.Param("orderIds") List<UUID> orderIds);

	/** How many units of a variant a shop has sold. Used when re-deriving a bestseller ranking. */
	@org.springframework.data.jpa.repository.Query("""
			SELECT COALESCE(SUM(i.quantity), 0)
			FROM OrderItem i
			WHERE i.variantId = :variantId
			""")
	long totalUnitsSoldForVariant(@org.springframework.data.repository.query.Param("variantId")
			UUID variantId);
}
