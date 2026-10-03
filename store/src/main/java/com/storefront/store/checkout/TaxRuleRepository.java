package com.storefront.store.checkout;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tax-rule queries.
 *
 * <p>Returns all active rules rather than one, because a shop can legitimately stack several and
 * the quote engine composes them. Choosing a single "the" tax would make the rule set invisible
 * and unmanageable.
 */
public interface TaxRuleRepository extends JpaRepository<TaxRule, UUID> {

	List<TaxRule> findByShopIdAndActiveTrueOrderByIdAsc(UUID shopId);

	long countByShopId(UUID shopId);
}
