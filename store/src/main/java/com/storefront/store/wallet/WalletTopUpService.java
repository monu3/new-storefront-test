package com.storefront.store.wallet;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The manual top-up flow (D7).
 *
 * <p>Request → admin review → ledger credit. Only the third step moves money, and it happens in
 * the same transaction as the status change, so a request can never be APPROVED without the
 * matching ledger entry or vice versa.
 *
 * <p><b>Approval is idempotent by construction.</b> The ledger entry's idempotency key is derived
 * from the request id, so even if the status check were bypassed the second credit would be
 * rejected by the ledger's unique constraint rather than paid out. Two independent guards, because
 * this endpoint moves real money and a shop's balance going up by accident is the kind of error
 * nobody notices until reconciliation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalletTopUpService {

	private final WalletTopUpRepository requests;
	private final WalletService wallet;

	// ------------------------------------------------------------------ shop side

	/**
	 * Records a claim that money has been transferred.
	 *
	 * <p>Creates nothing in the wallet. The shop's balance is unchanged until an admin approves,
	 * which is the entire point of the manual design.
	 */
	@Transactional
	public WalletTopUpRequest request(UUID shopId, long amountMinor, WalletTopUpRequest.Method method,
			String reference, UUID proofMediaId, String note) {
		WalletTopUpRequest request = requests.saveAndFlush(new WalletTopUpRequest(
				shopId, amountMinor, method, reference, proofMediaId, note));
		log.info("Top-up of {} requested by shop {} via {}", amountMinor, shopId, method);
		return request;
	}

	/**
	 * Records a claim that money was transferred, for a queued subscription request.
	 *
	 * <p>The amount comes from the <em>plan</em>, not from the caller. A shop cannot ask to be
	 * charged one plan's price and be granted another, and it cannot understate the transfer to make
	 * approval cheaper — {@link WalletTopUpRequest#forSubscription} re-checks the amount against the
	 * plan before the row is written, so a mismatch never reaches the admin's queue.
	 *
	 * <p>One method rather than {@code request(...)} followed by a link, because the link has to
	 * happen before the insert: {@code request} commits on return, so setting the subscription
	 * afterwards would mutate a detached entity and be silently discarded. That failure would look
	 * exactly like a working flow until an admin approved a request that started nothing.
	 *
	 * @param subscription the shop's own PENDING_PAYMENT request; its plan sets the amount
	 */
	@Transactional
	public WalletTopUpRequest requestForSubscription(UUID shopId, Object subscription,
			WalletTopUpRequest.Method method, String reference, UUID proofMediaId, String note) {

		// Typed loosely to keep the wallet package free of a dependency on the subscription package.
		// The four values needed are read through this small interface instead.
		SubscriptionLink link = (SubscriptionLink) subscription;

		WalletTopUpRequest request = new WalletTopUpRequest(
				shopId, link.priceMinor(), method, reference, proofMediaId, note);
		request.forSubscription(link.subscriptionId(), link.planId(), link.priceMinor());

		WalletTopUpRequest saved = requests.saveAndFlush(request);
		log.info("Subscription top-up of {} requested by shop {} for subscription {} via {}",
				link.priceMinor(), shopId, link.subscriptionId(), method);
		return saved;
	}

	/**
	 * What {@link #requestForSubscription} needs from a subscription, without depending on it.
	 *
	 * <p>The subscription package implements this on {@code ShopSubscription}. A narrow interface is
	 * used rather than a concrete type so {@code wallet} stays independent of {@code subscription};
	 * the alternative -- passing four loose arguments - is how a caller ends up pairing one
	 * subscription's id with another's plan.
	 */
	public interface SubscriptionLink {
		UUID subscriptionId();

		UUID planId();

		long priceMinor();
	}

	@Transactional(readOnly = true)
	public Page<WalletTopUpRequest> forShop(UUID shopId, Pageable pageable) {
		return requests.findByShopIdOrderByCreatedAtDesc(shopId, pageable);
	}

	// ------------------------------------------------------------------ admin side

	@Transactional(readOnly = true)
	public Page<WalletTopUpRequest> queue(WalletTopUpRequest.Status status, Pageable pageable) {
		return status == null
				? requests.findByStatusOrderByCreatedAtAsc(
						WalletTopUpRequest.Status.PENDING, pageable)
				: requests.findByStatusOrderByCreatedAtAsc(status, pageable);
	}

	/**
	 * Approves a request and credits the wallet.
	 *
	 * <p>The status change and the ledger entry are one transaction. If the credit fails, the
	 * request stays PENDING and the admin can retry — never APPROVED with no money, which is the
	 * failure mode that makes a shop's balance and its ledger disagree.
	 */
	@Transactional
	public WalletTopUpRequest approve(UUID requestId, UUID adminUserId, String reviewNote) {
		WalletTopUpRequest request = load(requestId);

		WalletLedgerEntry entry = wallet.credit(request.getShopId(),
				WalletLedgerEntry.EntryType.TOPUP_APPROVED,
				request.getAmountMinor(),
				request.ledgerKey(),
				"Top-up approved via " + request.getMethod()
						+ (request.getReference() == null ? "" : " (ref " + request.getReference() + ")"),
				null,
				adminUserId,
				null);

		request.approve(adminUserId, reviewNote, entry.getId());
		log.info("Top-up {} approved: {} credited to shop {}",
				request.getId(), request.getAmountMinor(), request.getShopId());
		return requests.saveAndFlush(request);
	}

	/** Rejects a request. No money moves. A reason is required. */
	@Transactional
	public WalletTopUpRequest reject(UUID requestId, UUID adminUserId, String reason) {
		WalletTopUpRequest request = load(requestId);
		request.reject(adminUserId, reason);
		log.info("Top-up {} rejected: {}", requestId, reason);
		return requests.saveAndFlush(request);
	}

	@Transactional(readOnly = true)
	public long pendingCount() {
		return requests.countByStatus(WalletTopUpRequest.Status.PENDING);
	}

	/**
	 * A request, or 404.
	 *
	 * <p>Public because the subscription flow has to read the request before approving it — it needs
	 * to know which subscription is being paid for, and approval itself must stay a single
	 * all-or-nothing step. Exposing the loader rather than a second read of the repository keeps one
	 * definition of what "not found" means for top-ups.
	 */
	@Transactional(readOnly = true)
	public WalletTopUpRequest require(UUID requestId) {
		return requests.findById(requestId)
				.orElseThrow(() -> new TopUpNotFoundException(requestId));
	}

	private WalletTopUpRequest load(UUID requestId) {
		return requests.findById(requestId)
				.orElseThrow(() -> new TopUpNotFoundException(requestId));
	}

	/** Maps to HTTP 404. */
	public static class TopUpNotFoundException extends RuntimeException {
		public TopUpNotFoundException(UUID requestId) {
			super("No top-up request " + requestId);
		}
	}
}
