package com.storefront.store.subscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Closes lapsed subscriptions and returns those shops to the free tier.
 *
 * <p><b>This sweep is a cleanup, not the enforcement point.</b> Every read in
 * {@link EntitlementService} tests the grant's own expiry, and
 * {@link ShopSubscription#findRunningByShopId} refuses to call a lapsed row current — so if this
 * job never runs at all, a subscriber still loses access the moment their window closes. What stops
 * running is the tidy-up: the rows, and {@code shop.commission_plan}, which is a derived cache the
 * order-accept path reads without querying.
 *
 * <p>The direction of that asymmetry is deliberate. If the sweep were the only thing revoking
 * access, a scheduler outage would silently hand customers free subscriptions. If the sweep is only
 * tidying, a scheduler outage leaves stale rows and a shop whose commission flag is briefly wrong —
 * visible, self-correcting on the next run, and not a silent grant of value.
 *
 * <p><b>Each subscription is swept in its own transaction.</b> One shop throwing must not stop the
 * other 400 from being closed, which a single transaction around the whole batch would guarantee.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionExpiryJob {

	private final ShopSubscriptionRepository subscriptions;
	private final ShopEntitlementRepository entitlements;
	private final SubscriptionService subscriptionService;

	/**
	 * Runs hourly.
	 *
	 * <p>Hourly rather than nightly because {@code shop.commission_plan} is read inside order
	 * acceptance, and a shop that lapses at 00:01 should not keep trading commission-free for
	 * twenty-three hours waiting for a midnight job. Hourly bounds that to an hour, and the real
	 * bound is still the read-time expiry test — this only keeps the cached flag roughly honest.
	 */
	// Six cron fields (Spring, not Unix: seconds first). Hourly at 7 minutes past, so this runs
	// before the entitlement cleanup at 23 -- closing subscriptions first, then tidying the rows
	// they left behind, in that order, every hour.
	@Scheduled(cron = "${store.subscription.expiry-cron:0 7 * * * *}")
	public void expireLapsed() {
		Instant now = Instant.now();

		List<ShopSubscription> due = subscriptions.findByStatusAndExpiresAtBefore(
				ShopSubscription.Status.ACTIVE, now);

		if (due.isEmpty()) {
			return;
		}

		int closed = 0;
		for (ShopSubscription subscription : due) {
			try {
				if (expireOne(subscription.getId(), now)) {
					closed++;
				}
			} catch (RuntimeException failure) {
				// Logged and skipped. A single shop that cannot be closed must not prevent the rest
				// from being closed, and it must not stop the job being scheduled again -- an
				// exception escaping here would cancel the remaining runs.
				log.error("Could not close lapsed subscription {} for shop {}: {}",
						subscription.getId(), subscription.getShopId(),
						failure.getMessage(), failure);
			}
		}

		log.info("Subscription sweep: {} of {} due subscriptions closed",
				closed, due.size());
	}

	/**
	 * Closes one subscription.
	 *
	 * <p>{@code REQUIRES_NEW} so that each shop is its own unit of work — see the class note. The
	 * caller holds no transaction of its own, so this is a fresh one either way; the propagation is
	 * stated because the call site is a loop over other people's rows and that should be visible.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean expireOne(UUID subscriptionId, Instant now) {
		return subscriptions.findById(subscriptionId)
				.map(subscription -> subscriptionService.lapse(subscription, now))
				.orElse(false);
	}

	/**
	 * Drops entitlement rows whose window has closed.
	 *
	 * <p>Separate from the subscription sweep, and safe to run more often: it touches only rows that
	 * grant nothing any more. If it fails, nothing breaks — the read path already denies those
	 * grants.
	 */
	@Scheduled(cron = "${store.subscription.entitlement-cleanup-cron:0 23 * * * *}")
	@Transactional
	public void purgeLapsedEntitlements() {
		List<ShopEntitlement> lapsed = entitlements.findLapsed(Instant.now());
		if (lapsed.isEmpty()) {
			return;
		}

		entitlements.deleteAll(lapsed);
		log.info("Purged {} lapsed entitlement rows", lapsed.size());
	}

	/** Runs the sweep now, for an admin's "run it" button and for tests. */
	public int expireNow() {
		Instant now = Instant.now();
		int closed = 0;
		for (ShopSubscription subscription : subscriptions.findByStatusAndExpiresAtBefore(
				ShopSubscription.Status.ACTIVE, now)) {
			if (expireOne(subscription.getId(), now)) {
				closed++;
			}
		}
		return closed;
	}
}
