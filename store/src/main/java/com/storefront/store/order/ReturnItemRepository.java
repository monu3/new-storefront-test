package com.storefront.store.order;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The lines inside a return request (Phase 8).
 *
 * <p>No update or delete method: these describe money that moved, so the history is kept whole.
 */
public interface ReturnItemRepository extends JpaRepository<ReturnItem, UUID> {

	List<ReturnItem> findByReturnRequestId(UUID returnRequestId);

	/**
	 * The approved units per order line for a set of requests, in one query.
	 *
	 * <p>Used when reconciling a return against the order. Fetching line by line is an N+1 on a
	 * money calculation, and an N+1 inside a reconciliation is how a total quietly comes out wrong
	 * on a big order.
	 */
	@Query("""
			select i.orderItemId, sum(i.quantityApproved)
			from ReturnItem i
			where i.returnRequest.id in :requestIds
			group by i.orderItemId
			""")
	List<Object[]> approvedByLine(@Param("requestIds") List<UUID> requestIds);
}