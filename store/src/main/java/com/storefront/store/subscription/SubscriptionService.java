package com.storefront.store.subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The subscription lifecycle (D4, D7).
 *
 * <p><b>Deliberately knows nothing about money.</b> Activation is the step where a wallet debit
 * belongs, but the wallet's top-up approval needs to trigger activation — so a dependency from here
 * to {@code WalletService} would close a cycle with
 * {@code WalletTopUpService}. {@link SubscriptionPaymentService} owns the D7 flow and depends on
 * both; this class owns the subscription and the entitlements and stays free of the wallet, which
 * also makes it testable without one.
 *
 * <p><b>Activation is one transaction</b>, and the order inside it is chosen so the only observable
 * intermediate state is one a crash could not leave behind:
 *
 * <ol>
 *   <li>close any existing active subscription (a plan change, and the partial unique index demands
 *       there be at most one);</li>
 *   <li>materialise the plan's entitlements with the new end date;</li>
 *   <li>flip {@code shop.commission_plan} to SUBSCRIBED;</li>
 *   <li>mark the subscription ACTIVE.</li>
 * </ol>
 *
 * <p>Entitlements are written <em>before</em> the status flips, so there is no instant at which a
 * shop is SUBSCRIBED (paying nothing) without its paid features granted. The reverse order would
 * grant features while still charging commission, which is the direction that loses goodwill rather
 * than money.
 *
 * <p><b>Lapsing is the mirror image</b>, and equally ordered: entitlements dropped, then commission
 * restored, then the status closed. A shop that is briefly over its product cap is recoverable; a
 * shop that is briefly charged 5% on an order it had paid not to be is a refund conversation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService {

	private final SubscriptionPlanRepository plans;
	private final ShopSubscriptionRepository subscriptions;
	private final ShopEntitlementRepository entitlements;
	private final PlanEntitlementRepository planEntitlements;
	private final EntitlementService entitlementService;
	private final ShopRepository shops;

	// ------------------------------------------------------------------ plans

	/** The pricing screen: what a shop can buy right now. */
	@Transactional(readOnly = true)
	public List<SubscriptionPlan> onSale() {
		return plans.findByActiveTrueOrderBySortOrderAscNameAsc();
	}

	@Transactional(readOnly = true)
	public SubscriptionPlan requirePlan(UUID planId) {
		return plans.findById(planId)
				.orElseThrow(() -> new NoSuchPlanException(planId));
	}

	/**
	 * What a plan grants, for the pricing screen's feature list.
	 *
	 * <p>Empty rather than an exception when the plan grants nothing, and an unknown feature key is
	 * reported by its raw string rather than dropped — a plan that grants a feature this build does
	 * not understand must still render as "grants something", not as "grants nothing".
	 */
	@Transactional(readOnly = true)
	public List<PlanEntitlement> entitlementsOf(UUID planId) {
		return planEntitlements.findByPlanId(planId);
	}

	// ------------------------------------------------------------------ shop side

	/**
	 * Queues a subscription request (D7).
	 *
	 * <p>Creates nothing that grants anything. The shop stays on commission and keeps its free
	 * limits until money has actually been approved.
	 *
	 * <p><b>An upgrade may be requested while a subscription runs; a downgrade may not.</b>
	 *
	 * <p>Splitting it that way is the difference between a usable upgrade button and an unusable
	 * one. Blocking everything while a subscription runs means a monthly subscriber who decides to
	 * go annual must wait out the month, and would say so in a support message. Blocking downgrades
	 * is the opposite case: the shop has paid for what it has, and switching early would hand it a
	 * cheaper plan's entitlements immediately — a refund the system performs by accident.
	 *
	 * <p>Compared on price rather than on a plan hierarchy, because the plans on sale are admin's
	 * to invent and a shop may be offered an annual plan at the same total price. What "downgrade"
	 * means is "costs the same or less for at least as long", which is the thing that would actually
	 * be lost.
	 *
	 * @throws IllegalStateException when the shop has this plan queued already, or when the request
	 *         would cost it money it has already paid for
	 */
	@Transactional
	public ShopSubscription request(Shop shop, SubscriptionPlan plan) {
		Instant now = Instant.now();

		if (!plan.isActive()) {
			throw new IllegalStateException("That plan is no longer available.");
		}

		if (subscriptions.findByShopIdAndPlanIdAndStatus(shop.getId(), plan.getId(),
				ShopSubscription.Status.PENDING_PAYMENT).isPresent()) {
			// A double-clicked button must not accumulate requests and be charged twice.
			throw new IllegalStateException(
					"You have already requested this plan. Pay the request you have rather than "
							+ "queuing another.");
		}

		subscriptions.findRunningByShopId(shop.getId(), now).ifPresent(running -> {
			if (plan.getPriceMinor() <= running.getPlan().getPriceMinor()) {
				throw new IllegalStateException(
						"You are already on " + running.getPlan().getName() + ". A plan that costs "
								+ "the same or less starts when your current one ends — "
								+ "cancel, and request this one then. Switching now would take away "
								+ "features you have already paid for.");
			}
		});

		ShopSubscription queued = subscriptions.saveAndFlush(new ShopSubscription(shop.getId(), plan));
		log.info("Shop {} requested plan {} ({})",
				shop.getId(), plan.getCode(), plan.priceDisplay());
		return queued;
	}

	/** The shop's running subscription, if it has one. */
	@Transactional(readOnly = true)
	public Optional<ShopSubscription> current(UUID shopId) {
		return subscriptions.findRunningByShopId(shopId, Instant.now());
	}

	/** Everything this shop has ever subscribed to, newest first. */
	@Transactional(readOnly = true)
	public List<ShopSubscription> history(UUID shopId) {
		return subscriptions.findByShopIdOrderByCreatedAtDesc(shopId);
	}

	/**
	 * Records that the shop will not renew.
	 *
	 * <p>Does not take effect until the paid period ends — see {@link ShopSubscription#cancel()}.
	 */
	@Transactional
	public ShopSubscription cancel(UUID shopId, UUID subscriptionId) {
		ShopSubscription subscription = requireOwned(subscriptionId, shopId);
		subscription.cancel();
		log.info("Shop {} will not renew plan {} (ends {})",
				shopId, subscription.getPlan().getCode(), subscription.getExpiresAt());
		return subscriptions.saveAndFlush(subscription);
	}

	// ------------------------------------------------------------------ activation

	/**
	 * Starts a subscription and grants what it was paid for.
	 *
	 * <p><b>The caller must already have taken the money.</b> This method takes no payment argument
	 * and charges nothing, which is deliberate: {@link SubscriptionPaymentService} debits the wallet
	 * first and calls this only if the debit succeeded, so there is no path that grants a paid
	 * feature without a matching ledger entry — and no path that takes money and then fails to
	 * grant, because both are in one transaction.
	 *
	 * @param startedAt when the clock starts, passed in rather than read so the window is
	 *        deterministic under test
	 */
	@Transactional
	public ShopSubscription activate(ShopSubscription subscription, Instant startedAt,
			UUID topupRequestId) {

		Shop shop = shops.findById(subscription.getShopId())
				.orElseThrow(() -> new IllegalArgumentException(
						"Subscription " + subscription.getId() + " names a shop that does not exist"));

		// A plan change: the old subscription is closed in the same transaction as the new one
		// starts. Done first because the partial unique index permits only one ACTIVE row per shop,
		// and doing it afterwards would fail on a constraint rather than on intent.
		subscriptions.findRunningByShopId(shop.getId(), startedAt).ifPresent(previous -> {
			if (!previous.getId().equals(subscription.getId())) {
				// closeForPlanChange, not expire: the window is still open here, and expire refuses
				// to close a subscription that has not lapsed.
				previous.closeForPlanChange(startedAt);
				// Its entitlements go with it, so the new plan's are the only ones that apply.
				entitlements.deleteByShopId(shop.getId());
				subscriptions.save(previous);
				log.info("Shop {} moved from {} to {}; earlier subscription closed",
						shop.getId(), previous.getPlan().getCode(),
						subscription.getPlan().getCode());
			}
		});

		subscription.activate(startedAt, topupRequestId);

		// Entitlements before the status flip: there must be no window in which the shop is
		// commission-free but still on the free tier's product cap.
		materialise(shop.getId(), subscription);

		if (entitlementService.commissionsWaived(shop.getId())) {
			shop.grantCommissionWaiver();
			shops.save(shop);
		}

		log.info("Shop {} subscribed to {} until {}",
				shop.getId(), subscription.getPlan().getCode(), subscription.getExpiresAt());
		return subscriptions.saveAndFlush(subscription);
	}

	/**
	 * Copies a plan's entitlements onto the shop, stamped with the subscription's end date.
	 *
	 * <p>Every entitlement the plan declares is written, including the ones it disables. Writing a
	 * disabled row rather than omitting it makes the materialised set a faithful copy of the plan,
	 * so a later read cannot fall through to the free tier for a feature this plan deliberately
	 * turned off.
	 */
	private void materialise(UUID shopId, ShopSubscription subscription) {
		for (PlanEntitlement granted : planEntitlements.findByPlanId(subscription.getPlan().getId())) {
			FeatureKey feature = granted.feature();

			// A plan written by a newer build may name a feature this one does not know. Skipped
			// with a warning rather than refused: a plan that cannot be applied at all would leave
			// the shop charged with nothing granted, which is the worst of both.
			if (feature == null) {
				log.warn("Plan {} grants unknown feature '{}'; skipping it",
						subscription.getPlan().getCode(), granted.getFeatureKey());
				continue;
			}

			entitlements.save(new ShopEntitlement(
					shopId, feature, granted.isEnabled(), granted.getLimitValue(),
					subscription.getExpiresAt()));
		}
	}

	// ------------------------------------------------------------------ expiry

	/**
	 * Closes everything a lapsed shop had, and puts it back on the free tier.
	 *
	 * <p>Called by the sweep, and safe to call for a shop that is already on the free tier — which is
	 * what makes the sweep re-runnable. Idempotence matters here because a scheduler that retries
	 * after a partial failure must not be able to do damage.
	 *
	 * @return whether this shop actually changed
	 */
	@Transactional
	public boolean lapse(ShopSubscription subscription, Instant now) {
		if (!subscription.expire(now)) {
			return false;
		}

		UUID shopId = subscription.getShopId();
		entitlements.deleteByShopId(shopId);

		shops.findById(shopId).ifPresent(shop -> {
			// The free tier charges commission again. Not conditional on what the plan granted:
			// the shop is no longer subscribed, so the free tier's rules apply whatever they are,
			// and reading them from the feature would mean this method had to know them.
			shop.revokeCommissionWaiver();
			shops.save(shop);
		});

		subscriptions.saveAndFlush(subscription);
		log.info("Subscription {} lapsed ({}) and shop {} returned to the free tier",
				subscription.getId(), subscription.getStatus(), shopId);
		return true;
	}

	/**
	 * Closes a pending request without paying for it.
	 *
	 * <p>Used when an admin rejects the top-up, and by {@code cancel} for a pending row. No
	 * entitlements were ever granted, so nothing needs undoing — the assertion is that, rather than
	 * a cleanup that would hide a bug.
	 */
	@Transactional
	public ShopSubscription abandonPending(UUID subscriptionId, UUID shopId) {
		ShopSubscription subscription = requireOwned(subscriptionId, shopId);
		if (subscription.getStatus() != ShopSubscription.Status.PENDING_PAYMENT) {
			throw new IllegalStateException(
					"That subscription is not waiting for payment; it is " + subscription.getStatus());
		}
		subscription.cancel();
		return subscriptions.saveAndFlush(subscription);
	}

	// ------------------------------------------------------------------ helpers

	/** Fetches a subscription and proves it is this shop's. 404, never 403 — see the plan's rule. */
	private ShopSubscription requireOwned(UUID subscriptionId, UUID shopId) {
		return subscriptions.findById(subscriptionId)
				.filter(found -> found.getShopId().equals(shopId))
				.orElseThrow(() -> new NoSuchSubscriptionException(subscriptionId));
	}

	/** Maps to HTTP 404. */
	public static class NoSuchPlanException extends RuntimeException {
		public NoSuchPlanException(UUID planId) {
			super("No plan " + planId);
		}
	}

	/** Maps to HTTP 404. */
	public static class NoSuchSubscriptionException extends RuntimeException {
		public NoSuchSubscriptionException(UUID subscriptionId) {
			super("No subscription " + subscriptionId);
		}
	}
}
