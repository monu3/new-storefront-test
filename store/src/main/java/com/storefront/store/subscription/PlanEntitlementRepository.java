package com.storefront.store.subscription;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * What each plan grants — the template {@code shop_entitlement} rows are built from.
 *
 * <p>Editing a row here changes what the <em>next</em> activation grants, not what current
 * subscribers hold. That is the reason {@link SubscriptionService} refuses to edit a plan an ACTIVE
 * subscription holds, rather than pushing the change through: a subscriber who paid for a plan with
 * 500 products must keep 500 products because an admin edited the plan last week.
 */
public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, PlanEntitlement.Key> {

	List<PlanEntitlement> findByPlanId(UUID planId);

	long deleteByPlanId(UUID planId);
}
