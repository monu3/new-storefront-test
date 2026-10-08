package com.storefront.store.subscription;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The plans an admin sells (D4).
 */
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

	Optional<SubscriptionPlan> findByCode(String code);

	/** The pricing screen: on sale, in display order. */
	List<SubscriptionPlan> findByActiveTrueOrderBySortOrderAscNameAsc();

	/** Everything for the admin list, including withdrawn plans. */
	Page<SubscriptionPlan> findAllByOrderBySortOrderAscNameAsc(Pageable pageable);

	boolean existsByCode(String code);
}
