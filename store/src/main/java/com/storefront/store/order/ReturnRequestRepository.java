package com.storefront.store.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Return requests (Phase 8).
 *
 * <p><b>No update or delete method, by the same reasoning as the audit log.</b> A return is a
 * record of money moving; deleting one would leave the wallet and the return history disagreeing with
 * nothing to explain the gap.
 */
public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {

	/**
	 * The shop's queue: open requests only.
	 *
	 * <p><b>Filtered to {@code REQUESTED} in the query rather than in memory.</b> A shop's returns
	 * list is small today and grows forever, and "show me what is waiting for me" answered by
	 * loading every historical request to discard the decided ones stops working at exactly the
	 * wrong moment.
	 *
	 * @param shopId the shop, always — an owner never sees another shop's customers asking for
	 *               refunds. Derived from the token, never from the request.
	 */
	@Query("""
			select r from ReturnRequest r
			where r.shopId = :shopId
			  and (:status is null or r.status = :status)
			order by case when r.status = com.storefront.store.order.ReturnRequest$Status.REQUESTED
			              then 0 else 1 end,
			         r.requestedAt desc
			""")
	Page<ReturnRequest> forShop(@Param("shopId") UUID shopId,
			@Param("status") ReturnRequest.Status status,
			Pageable pageable);

	/** Everything that happened to one order, oldest first — the order's return history. */
	List<ReturnRequest> findByOrderIdOrderByRequestedAtDesc(UUID orderId);

	/** Every open request for one order, so a second return can be refused with a reason. */
	List<ReturnRequest> findByOrderIdAndStatus(UUID orderId, ReturnRequest.Status status);

	/**
	 * How many units of one order line have already been approved in a returned state.
	 *
	 * <p>This is the check a partial return depends on, and it is the reason returns are rows
	 * rather than a jsonb array on the order: "you already got these back" has to be answerable
	 * without parsing JSON during a money decision.
	 *
	 * <p>Only {@code REFUNDED} requests count. An approved-but-not-yet-refunded request has not
	 * moved money, and counting it would refuse a legitimate second return while the first is
	 * still being processed.
	 */
	@Query("""
			select coalesce(sum(i.quantityApproved), 0)
			from ReturnItem i
			where i.orderItemId = :orderItemId
			  and i.returnRequest.status =
			      com.storefront.store.order.ReturnRequest$Status.REFUNDED
			""")
	long approvedUnitsForLine(@Param("orderItemId") UUID orderItemId);

	/** The count for the shop's overview tile. */
	long countByShopIdAndStatus(UUID shopId, ReturnRequest.Status status);

	/** Requests decided in a window, for the admin's returns report. */
	@Query("""
			select r from ReturnRequest r
			where r.decidedAt >= :since
			order by r.decidedAt desc
			""")
	List<ReturnRequest> decidedSince(@Param("since") Instant since);

	Optional<ReturnRequest> findByOrderIdAndStatusAndIdNot(UUID orderId,
			ReturnRequest.Status status, UUID id);
}