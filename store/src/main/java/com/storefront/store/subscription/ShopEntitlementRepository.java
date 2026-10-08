package com.storefront.store.subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * What each shop may do right now (D4).
 *
 * <p>Every query here that asks "is this granted?" carries the expiry test. Reading
 * {@code enabled} alone would report a lapsed grant as held until the sweep ran, which is the
 * difference between a customer keeping access and not depending on whether a scheduled task is
 * healthy.
 */
public interface ShopEntitlementRepository extends JpaRepository<ShopEntitlement, ShopEntitlement.Key> {

	List<ShopEntitlement> findByShopId(UUID shopId);

	Optional<ShopEntitlement> findByShopIdAndFeatureKey(UUID shopId, String featureKey);

	/**
	 * Grants in force right now, for one shop.
	 *
	 * <p>The query every entitlement page and the pricing comparison both use.
	 */
	@Query("""
			select e from ShopEntitlement e
			where e.shopId = :shopId
			  and (e.expiresAt is null or e.expiresAt > :now)
			""")
	List<ShopEntitlement> findInForce(
			@Param("shopId") UUID shopId, @Param("now") Instant now);

	/** Drops every grant. Used when a subscription lapses and the shop falls back to FREE. */
	long deleteByShopId(UUID shopId);

	/**
	 * Grants past their expiry — the sweep's cleanup half.
	 *
	 * <p>Nothing depends on this running. Access is already denied by the read path's own expiry
	 * test, so the sweep exists to stop the table filling with rows for features nobody can use.
	 */
	@Query("""
			select e from ShopEntitlement e
			where e.expiresAt is not null and e.expiresAt <= :now
			""")
	List<ShopEntitlement> findLapsed(@Param("now") Instant now);

	/** How many shops hold a feature right now. Read with the expiry test, not a status. */
	@Query("""
			select count(e) from ShopEntitlement e
			where e.featureKey = :featureKey
			  and e.enabled = true
			  and (e.expiresAt is null or e.expiresAt > :now)
			""")
	long countGranting(@Param("featureKey") String featureKey, @Param("now") Instant now);

	/** For the admin's "who has this feature" view. */
	@Query("""
			select e.shopId from ShopEntitlement e
			where e.featureKey = :featureKey
			  and e.enabled = true
			  and (e.expiresAt is null or e.expiresAt > :now)
			order by e.shopId
			""")
	List<UUID> shopsGranting(
			@Param("featureKey") String featureKey, @Param("now") Instant now);
}
