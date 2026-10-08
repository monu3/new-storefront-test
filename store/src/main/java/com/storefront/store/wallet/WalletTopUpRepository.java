package com.storefront.store.wallet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Top-up request queries.
 *
 * <p>Paged throughout. The pending queue is the admin's working screen and grows without bound, so
 * it pages; so does a shop's own history.
 */
public interface WalletTopUpRepository extends JpaRepository<WalletTopUpRequest, UUID> {

	Page<WalletTopUpRequest> findByShopIdOrderByCreatedAtDesc(UUID shopId, Pageable pageable);

	Page<WalletTopUpRequest> findByStatusOrderByCreatedAtAsc(
			WalletTopUpRequest.Status status, Pageable pageable);

	Page<WalletTopUpRequest> findByStatusAndMethodOrderByCreatedAtAsc(
			WalletTopUpRequest.Status status, WalletTopUpRequest.Method method, Pageable pageable);

	List<WalletTopUpRequest> findByShopIdAndStatusOrderByCreatedAtAsc(
			UUID shopId, WalletTopUpRequest.Status status);

	long countByStatus(WalletTopUpRequest.Status status);

	long countByShopIdAndStatus(UUID shopId, WalletTopUpRequest.Status status);

	/**
	 * The request that pays for a subscription, if there is one.
	 *
	 * <p>Exists so a test -- and the admin's queue -- can find a subscription's payment by the
	 * subscription rather than only by a top-up id the shop has to have kept. It also makes the
	 * "was refused at submit time" assertion possible: nothing was created, so nothing is found.
	 */
	Optional<WalletTopUpRequest> findBySubscriptionId(UUID subscriptionId);
}
