package com.storefront.store.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Payment-attempt queries.
 *
 * <p>Paged listings throughout, because this table grows with every attempt including the failed
 * ones, and a shop's "unverified transfers" queue is exactly where that shows up as a problem.
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

	Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

	List<PaymentTransaction> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

	long countByOrderId(UUID orderId);

	/** A shop's transfers awaiting a human. */
	Page<PaymentTransaction> findByShopIdAndGatewayStatusOrderByCreatedAtAsc(
			UUID shopId, PaymentTransaction.GatewayStatus status, Pageable pageable);

	Page<PaymentTransaction> findByShopIdOrderByCreatedAtDesc(UUID shopId, Pageable pageable);

	long countByShopIdAndGatewayStatus(UUID shopId, PaymentTransaction.GatewayStatus status);
}
