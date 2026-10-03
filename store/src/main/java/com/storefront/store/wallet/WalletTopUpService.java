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
