package com.storefront.store.order;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Order-history queries.
 *
 * <p>Append-only: there is no update method here and there never should be. A method to "fix" a
 * history row would make the table untrustworthy, and an untrustworthy timeline is worse than no
 * timeline — it invites a dispute to be settled on the wrong evidence.
 */
public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

	/** The customer-facing tracker, oldest first. */
	List<OrderStatusHistory> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

	long countByOrderId(UUID orderId);

	long countByToStatus(ShopOrder.Status status);
}
