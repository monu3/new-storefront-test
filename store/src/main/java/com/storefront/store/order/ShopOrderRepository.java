package com.storefront.store.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Order queries.
 *
 * <p>Every list query is paged with a cursor rather than an offset. Order tables are the fastest
 * growing in the system, and {@code OFFSET 100000} makes the database walk a hundred thousand
 * rows to return twenty.
 */
public interface ShopOrderRepository extends JpaRepository<ShopOrder, UUID> {

	Optional<ShopOrder> findByShopIdAndOrderNumber(UUID shopId, String orderNumber);

	boolean existsByShopIdAndOrderNumber(UUID shopId, String orderNumber);

	Page<ShopOrder> findByShopIdOrderByPlacedAtDesc(UUID shopId, Pageable pageable);

	Page<ShopOrder> findByCustomerIdOrderByPlacedAtDesc(UUID customerId, Pageable pageable);

	Page<ShopOrder> findByShopIdAndStatusOrderByPlacedAtDesc(UUID shopId, ShopOrder.Status status,
			Pageable pageable);

	List<ShopOrder> findByShopIdAndStatusIn(UUID shopId, List<ShopOrder.Status> statuses);

	/**
	 * The shop's open orders: placed, accepted, processing or shipped.
	 *
	 * <p>Explicit JPQL rather than a derived name because Spring Data cannot express
	 * {@code IN} through a method name — it reads the List as a scalar equality and the context
	 * fails to start. This is what the order board's default view uses.
	 */
	@Query("""
			SELECT o FROM ShopOrder o
			WHERE o.shopId = :shopId
			  AND o.status IN :statuses
			""")
	Page<ShopOrder> findOpenForShop(@Param("shopId") UUID shopId,
			@Param("statuses") List<ShopOrder.Status> statuses, Pageable pageable);


	long countByShopIdAndStatus(UUID shopId, ShopOrder.Status status);

	/**
	 * Orders still in play, newest first (Phase 4).
	 *
	 * <p>The board's default view. Delivered and terminal orders are excluded so the queue an
	 * owner works is the one that needs them.
	 */
	@Query("""
			SELECT o FROM ShopOrder o
			WHERE o.shopId = :shopId
			  AND o.status NOT IN (com.storefront.store.order.ShopOrder$Status.DELIVERED,
			                        com.storefront.store.order.ShopOrder$Status.CANCELLED,
			                        com.storefront.store.order.ShopOrder$Status.REFUNDED,
			                        com.storefront.store.order.ShopOrder$Status.RETURNED)
			""")
	Page<ShopOrder> findActiveForShop(@Param("shopId") UUID shopId, Pageable pageable);

	/**
	 * Search across order number, customer name, phone and email (Phase 4).
	 *
	 * <p>Native because the four fields live in two tables: contact details on the order, and a
	 * signed-in customer's own name on {@code app_user}. Four derived queries would need four
	 * round trips and a merge.
	 *
	 * <p>Only non-terminal orders are returned. An owner working a queue does not want last
	 * month's delivered orders crowding it, and the tabs they filter by are the open ones.
	 */
	@Query(value = """
			SELECT o.* FROM shop_order o
			LEFT JOIN app_user u ON u.id = o.customer_id
			WHERE o.shop_id = :shopId
			  AND o.status NOT IN ('DELIVERED', 'CANCELLED', 'REFUNDED', 'RETURNED')
			  AND (
			         lower(o.order_number) LIKE :term
			      OR lower(coalesce(u.full_name, ''))   LIKE :term
			      OR lower(coalesce(o.guest_email, ''))  LIKE :term
			      OR lower(coalesce(o.guest_phone, ''))   LIKE :term
			      OR lower(coalesce(o.shipping_address, '')) LIKE :term
			      )
			ORDER BY o.placed_at DESC
			""", nativeQuery = true)
	Page<ShopOrder> searchInShop(@Param("shopId") UUID shopId, @Param("term") String term,
			Pageable pageable);

	/**
	 * Order counts per status, for the filter tabs.
	 *
	 * <p>One grouped query for every tab rather than one count each — seven round trips to render
	 * one row of buttons is the kind of cost nobody notices until the page is slow.
	 */
	@Query(value = """
			SELECT status, count(*) FROM shop_order
			WHERE shop_id = :shopId
			GROUP BY status
			""", nativeQuery = true)
	List<Object[]> countByStatusForShop(@Param("shopId") UUID shopId);

	/**
	 * Every order belonging to a shop.
	 *
	 * <p>Exists for test cleanup and for the shop-deletion path, which has to remove orders
	 * explicitly: {@code shop_order.shop_id} is {@code ON DELETE RESTRICT} precisely so an order
	 * can never be orphaned from the shop it was placed at. Restricting means the deletion has to
	 * be deliberate in that order — orders first, then the shop.
	 */
	List<ShopOrder> findByShopId(UUID shopId);

	/**
	 * Accepted orders that carry a frozen commission, for reconciliation.
	 *
	 * <p>Written as an explicit JPQL query because the derived-name form cannot express an
	 * {@code IN} clause: Spring Data would read the List argument as a scalar equality and the
	 * repository would fail to start.
	 */
	@Query("""
			SELECT o FROM ShopOrder o
			WHERE o.status IN :statuses
			  AND o.commissionMinor IS NOT NULL
			ORDER BY o.acceptedAt DESC
			""")
	Page<ShopOrder> findWithCommission(@Param("statuses") List<ShopOrder.Status> statuses,
			Pageable pageable);
}
