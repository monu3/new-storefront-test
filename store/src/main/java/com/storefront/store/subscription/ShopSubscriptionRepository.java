package com.storefront.store.subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Shop subscriptions (D4, D7).
 *
 * <p>{@link #findRunningByShopId} is the hot query in the whole phase: the entitlement check, the
 * commission decision and the account screen all want "this shop's current subscription, if it is
 * still running".
 *
 * <p>It is written against the same grant semantics as {@link ShopSubscription#grants} — status
 * <em>and</em> a live window — rather than {@code status = 'ACTIVE'}, because an ACTIVE row whose
 * window has closed must not be treated as current. A scheduler that is stopped must not extend a
 * customer's paid access.
 */
public interface ShopSubscriptionRepository extends JpaRepository<ShopSubscription, UUID> {

	/** The shop's running subscription, if it has one. */
	@Query("""
			select s from ShopSubscription s
			where s.shopId = :shopId
			  and s.status = com.storefront.store.subscription.ShopSubscription$Status.ACTIVE
			  and s.startedAt is not null
			  and s.expiresAt is not null
			  and s.expiresAt > :now
			order by s.expiresAt desc
			""")
	Optional<ShopSubscription> findRunningByShopId(
			@Param("shopId") UUID shopId, @Param("now") Instant now);

	/** Every row for a shop, newest first. The subscription history on the account screen. */
	List<ShopSubscription> findByShopIdOrderByCreatedAtDesc(UUID shopId);

	Optional<ShopSubscription> findByShopIdAndPlanIdAndStatus(
			UUID shopId, UUID planId, ShopSubscription.Status status);

	/**
	 * Subscriptions due to expire.
	 *
	 * <p>A partial index backs exactly this predicate. The sweep is written to be idempotent:
	 * {@link ShopSubscription#expire} refuses to close a row that is not actually due, so one
	 * selected by a racing clock change is left alone rather than revoked.
	 */
	List<ShopSubscription> findByStatusAndExpiresAtBefore(ShopSubscription.Status status, Instant before);

	/**
	 * Shops with a running subscription — the MRR population.
	 *
	 * <p>Distinct shops, so a shop that somehow held two would be counted once. The partial unique
	 * index should make that impossible; {@code distinct} is what keeps a violated assumption from
	 * becoming a doubled revenue figure.
	 */
	@Query("""
			select distinct s.shopId from ShopSubscription s
			where s.status = com.storefront.store.subscription.ShopSubscription$Status.ACTIVE
			  and s.expiresAt > :now
			""")
	List<UUID> shopsWithRunningSubscription(@Param("now") Instant now);

	/** How many subscriptions hold a plan. Guards editing a plan a subscriber is on. */
	long countByPlanIdAndStatus(UUID planId, ShopSubscription.Status status);

	/** All active subscriptions, for MRR. */
	List<ShopSubscription> findByStatusAndExpiresAtAfter(ShopSubscription.Status status, Instant after);
}
