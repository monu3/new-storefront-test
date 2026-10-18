package com.storefront.store.subscription;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletService;
import com.storefront.store.wallet.WalletTopUpRequest;
import com.storefront.store.wallet.WalletTopUpService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The money half of subscribing (D7).
 *
 * <p>D7 is manual by design: a shop transfers money to the platform, submits a top-up request, and
 * an admin approves it. Approval is the only moment anything happens, and at that moment three
 * things must be true together:
 *
 * <ol>
 *   <li>the shop's wallet is credited for the transfer it claims to have made;</li>
 *   <li>the subscription starts and its entitlements are granted;</li>
 *   <li>a {@code SUBSCRIPTION_CHARGE} debit records the price against the ledger.</li>
 * </ol>
 *
 * <p>All three in one transaction, in that order. Any other order has a window in which the shop is
 * charged for something it cannot use or given something it has not paid for.
 *
 * <p><b>The charge and the credit net to zero by design, and that is not a mistake.</b> The shop
 * sent a bank transfer to the platform; the platform credits the shop's wallet so it has a balance
 * to pay its subscription from, and the subscription charge then takes it back. The round trip looks
 * redundant in isolation, but it means every rupee the platform takes from a subscriber is a ledger
 * row with a reason, rather than a bank statement that has to be reconciled against a table of
 * subscriptions by hand. The ledger is the audit trail; the bank is the settlement.
 *
 * <p><b>Two guards, independently.</b> The ledger's idempotency key is derived from the top-up id,
 * so a replayed approval is refused by the ledger even if the status check were bypassed. And the
 * charge refuses to overdraw, so an admin who approves a transfer for less than the plan costs
 * gets an error naming the shortfall rather than a shop whose entitlements were granted for a
 * partial payment.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionPaymentService {

	private final WalletTopUpService topUps;
	private final WalletService wallet;
	private final SubscriptionService subscriptions;
	private final ShopSubscriptionRepository subscriptionRows;
	private final ShopRepository shops;
	private final com.storefront.store.audit.AuditTrail audit;

	/**
	 * Settles a subscription purchase: credit, charge, activate.
	 *
	 * @param requestId the top-up request the shop submitted
	 * @param adminUserId who approved it — recorded on both the top-up and the ledger entry
	 * @return the now-active subscription
	 * @throws TopUpNotForSubscriptionException when the request has no subscription attached, which
	 *         is a plain wallet top-up and belongs to {@code WalletTopUpService.approve} instead
	 * @throws InsufficientFundsException when the approved amount does not cover the plan
	 */
	@Transactional
	public ShopSubscription settle(UUID requestId, UUID adminUserId, String reviewNote) {

		WalletTopUpRequest request = topUps.require(requestId);
		UUID subscriptionId = request.getSubscriptionId();

		if (subscriptionId == null) {
			// Not an error the admin caused. A plain top-up has no subscription, and this method
			// must not quietly credit it without starting anything -- that would look like a
			// successful subscription purchase in the audit trail.
			throw new TopUpNotForSubscriptionException(requestId);
		}

		ShopSubscription subscription = subscriptionRows.findById(subscriptionId)
				.orElseThrow(() -> new SubscriptionService.NoSuchSubscriptionException(subscriptionId));

		Shop shop = shops.findById(subscription.getShopId())
				.orElseThrow(() -> new IllegalArgumentException(
						"Subscription " + subscriptionId + " names a shop that does not exist"));

		long priceMinor = subscription.getPlan().getPriceMinor();

		// Credit first: the shop's own money funds the charge, which is the point of the design.
		// Doing it in this order means a shortfall is a shortfall of the *approved* transfer rather
		// than a race against a balance that may or may not have been funded already.
		topUps.approve(requestId, adminUserId, reviewNote);

		Instant startedAt = Instant.now();

		// Then the charge. If the approved transfer does not cover the plan, this throws and the
		// whole transaction rolls back -- including the credit. The admin sees the shortfall and can
		// reject, having granted nothing.
		WalletLedgerEntry charge = tryDebit(shop, priceMinor, subscription, adminUserId);

		// `charge` needs no further linking: `debitSubscriptionCharge` set the subscription before
		// writing the row. Setting it here instead would mutate a detached entity -- the entry is
		// returned from its own transaction -- and the link would be silently discarded, leaving every
		// subscription charge in the ledger with a null subscription_id and no way to reconcile it
		// against MRR.
		//
		// <p>The top-up side is likewise already linked, from when the shop submitted it.
		log.debug("Subscription charge {} recorded for subscription {}",
				charge.getId(), subscription.getId());

		ShopSubscription active = subscriptions.activate(subscription, startedAt, request.getId());

		// Recorded here, in the one method that can start a subscription, rather than in
		// AdminWalletController. A caller that reached settle() over anything other than that
		// endpoint -- a scheduled retry, a future support tool -- would otherwise start a
		// subscription that appears in no log at all, which is the one thing an audit log exists to
		// prevent.
		audit.record(com.storefront.store.audit.AuditAction.SUBSCRIPTION_APPROVE,
				"WalletTopUpRequest",
				requestId,
				"Started " + subscription.getPlan().getName() + " for shop " + shop.getId()
						+ " (" + subscription.getPlan().priceDisplay() + "), until "
						+ active.getExpiresAt(),
				reviewNote,
				com.storefront.store.audit.AuditTrail.field("status", "PENDING_PAYMENT"),
				com.storefront.store.audit.AuditTrail.field("status", "ACTIVE"));

		log.info("Shop {} subscribed to {} for {} via top-up {}",
				shop.getId(), subscription.getPlan().getCode(), priceMinor, requestId);

		return active;
	}

	/**
	 * Charges the plan price, translating the wallet's shortfall into one that names the numbers.
	 *
	 * <p>{@code forSubscription} already refuses an amount that does not match the plan, so in
	 * practice this only fires when the shop had a pre-existing negative-or-too-small balance and
	 * the credit is consumed by it. That is a real case -- a shop behind on commission -- and it
	 * should read as "this shop cannot afford a subscription right now", not as a constraint
	 * violation from a uniqueness index.
	 */
	private WalletLedgerEntry tryDebit(Shop shop, long priceMinor, ShopSubscription subscription,
			UUID adminUserId) {
		try {
			return wallet.debitSubscriptionCharge(shop.getId(),
					priceMinor,
					chargeKey(subscription),
					"Subscription: " + subscription.getPlan().getName()
							+ " (" + subscription.getPlan().priceDisplay() + ")",
					subscription.getId(),
					adminUserId);
		} catch (WalletService.InsufficientWalletBalanceException insufficient) {
			throw new InsufficientFundsException(
					shop.getId(), wallet.balanceOf(shop.getId()), priceMinor);
		}
	}

	/**
	 * The ledger's idempotency key for a subscription charge.
	 *
	 * <p>Keyed on the subscription, not the top-up: one subscription is charged once. If an approval
	 * is retried after a partial failure the second debit is refused by the ledger's unique
	 * constraint, which is the guard that makes this safe to call twice.
	 */
	private String chargeKey(ShopSubscription subscription) {
		return "sub-charge:" + subscription.getId();
	}

	/**
	 * Rejects a subscription purchase.
	 *
	 * <p>In the payment service for the same reason {@link #settle} records there: the endpoint is
	 * not the only thing that can reject one, and the log has to see both.
	 *
	 * @param reason required; the shop is told it and can fix what to fix
	 */
	@Transactional
	public void reject(UUID requestId, UUID adminUserId, String reason) {
		WalletTopUpRequest request = topUps.require(requestId);

		com.storefront.store.audit.AuditTrail.requireReason(
				com.storefront.store.audit.AuditAction.SUBSCRIPTION_REJECT, reason);

		topUps.reject(requestId, adminUserId, reason);

		if (request.getSubscriptionId() != null) {
			subscriptions.abandonPending(request.getSubscriptionId(), request.getShopId());
		}

		audit.record(com.storefront.store.audit.AuditAction.SUBSCRIPTION_REJECT,
				"WalletTopUpRequest",
				requestId,
				"Rejected a subscription payment of "
						+ com.storefront.store.common.Money.ofMinor(request.getAmountMinor()).toDisplay()
						+ " for shop " + request.getShopId(),
				reason,
				com.storefront.store.audit.AuditTrail.field("status", request.getStatus()),
				com.storefront.store.audit.AuditTrail.field("status", "REJECTED"));

		log.info("Subscription payment {} rejected by {}: {}",
				requestId, adminUserId, reason);
	}

	/** Maps to HTTP 409. */
	public static class TopUpNotForSubscriptionException extends RuntimeException {
		public TopUpNotForSubscriptionException(UUID requestId) {
			super("Top-up " + requestId + " is not paying for a subscription. "
					+ "Approve it through the wallet queue instead.");
		}
	}

	/**
	 * Maps to HTTP 409.
	 *
	 * <p>Carries the numbers, because "insufficient funds" without them is the least useful message
	 * in a review queue: the admin is looking at a bank transfer and needs to know how much short
	 * it fell.
	 */
	public static class InsufficientFundsException extends RuntimeException {

		private final long availableMinor;
		private final long requiredMinor;

		public InsufficientFundsException(UUID shopId, long availableMinor, long requiredMinor) {
			super("Shop " + shopId + " was credited " + availableMinor
					+ " but the plan costs " + requiredMinor
					+ ". The approved transfer does not cover the subscription.");
			this.availableMinor = availableMinor;
			this.requiredMinor = requiredMinor;
		}

		public long availableMinor() {
			return availableMinor;
		}

		public long requiredMinor() {
			return requiredMinor;
		}

		public long shortfallMinor() {
			return requiredMinor - availableMinor;
		}
	}
}
