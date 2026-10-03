package com.storefront.store.social;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Follower queries.
 *
 * <p>{@link #existsByShopIdAndUserId} is the check that makes following idempotent: it runs
 * before the insert and again under the unique constraint, and only an insert that actually
 * created a row increments the counter.
 */
public interface ShopFollowerRepository extends JpaRepository<ShopFollower, ShopFollower.Key> {

	boolean existsByShopIdAndUserId(UUID shopId, UUID userId);

	long countByShopId(UUID shopId);

	long countByUserId(UUID userId);

	/** A customer's own list of shops. Phase 5 renders this as a follow button's state. */
	Page<ShopFollower> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

	List<ShopFollower> findByUserIdAndShopId(UUID userId, UUID shopId);
}
