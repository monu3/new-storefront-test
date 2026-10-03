package com.storefront.store.wallet;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A shop asking the platform to credit its wallet.
 *
 * <p><b>Manual by design (D7).</b> A shop transfers money, then an admin approves it. There is no
 * automatic gateway confirmation because the two methods available in Nepal — eSewa and bank
 * transfer — either have no server-to-server verification that can be trusted here, or are
 * confirmed by a human reading a bank statement. Automating that would mean either marking money
 * as received that was not, or building a reconciliation nobody has asked for.
 *
 * <p>The request is a <em>claim</em>, not a credit. The wallet changes only when an admin approves,
 * and the ledger entry that results carries {@link #ledgerEntryId} so the claim and the money it
 * produced can always be tied together.
 *
 * <p>Approving twice is impossible: the status is only ever moved by {@link #approve} and
 * {@link #reject}, both of which require PENDING, and the ledger entry's idempotency key is
 * derived from the request id. A double-clicked Approve therefore credits once.
 */
@Entity
@Table(name = "wallet_topup_request")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTopUpRequest extends BaseEntity {

	public enum Status {
		PENDING,
		APPROVED,
		REJECTED
	}

	/** How the shop says it paid. ESAWA or BANK_TRANSFER only — there is no cash top-up. */
	public enum Method {
		ESAWA,
		BANK_TRANSFER
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@Column(name = "amount_minor", nullable = false)
	private long amountMinor;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false)
	private Method method;

	/** The shop's own reference for the transfer: a transaction id or a deposit slip number. */
	@Column(name = "reference")
	private String reference;

	/** A photo of the transfer receipt, as a media row (D19). */
	@Column(name = "proof_media_id")
	private UUID proofMediaId;

	@Column(name = "note")
	private String note;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.PENDING;

	@Column(name = "reviewed_by")
	private UUID reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	/** Why it was refused. Shown to the shop so they can fix and resubmit. */
	@Column(name = "review_note")
	private String reviewNote;

	/** The ledger entry approval produced. Null until approved. */
	@Column(name = "ledger_entry_id")
	private UUID ledgerEntryId;

	public WalletTopUpRequest(UUID shopId, long amountMinor, Method method, String reference,
			UUID proofMediaId, String note) {

		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (amountMinor <= 0) {
			// The column requires > 0 and a zero "top-up" is a mistake in the console, not a
			// request to credit nothing.
			throw new IllegalArgumentException("Top-up amount must be positive: " + amountMinor);
		}
		if (method == null) {
			throw new IllegalArgumentException("A top-up must say how it was paid");
		}

		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.amountMinor = amountMinor;
		this.method = method;
		this.reference = reference;
		this.proofMediaId = proofMediaId;
		this.note = note;
		this.status = Status.PENDING;
	}

	/**
	 * Approves the request, returning the ledger entry that must now exist.
	 *
	 * <p>Refuses a request that is not PENDING, so a second approval fails loudly rather than
	 * creating a second credit.
	 */
	public void approve(UUID adminUserId, String reviewNote, UUID ledgerEntryId) {
		if (status != Status.PENDING) {
			throw new IllegalStateException(
					"This top-up has already been " + status.name().toLowerCase(java.util.Locale.ROOT));
		}
		this.status = Status.APPROVED;
		this.reviewedBy = adminUserId;
		this.reviewedAt = Instant.now();
		this.reviewNote = reviewNote;
		this.ledgerEntryId = ledgerEntryId;
	}

	/**
	 * Rejects the request.
	 *
	 * <p>A reason is required. "Rejected" with no explanation gives a shop owner nothing to act
	 * on, and the most common cause is a missing or unreadable transfer reference.
	 */
	public void reject(UUID adminUserId, String reason) {
		if (status != Status.PENDING) {
			throw new IllegalStateException(
					"This top-up has already been " + status.name().toLowerCase(java.util.Locale.ROOT));
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("Give a reason so the shop knows what to fix");
		}
		this.status = Status.REJECTED;
		this.reviewedBy = adminUserId;
		this.reviewedAt = Instant.now();
		this.reviewNote = reason.trim();
	}

	public boolean isPending() {
		return status == Status.PENDING;
	}

	/** The ledger idempotency key for this request's credit. */
	public String ledgerKey() {
		return "topup:" + id;
	}

	/** What the shop sees on its own screen. */
	public record TopUpView(
			UUID id,
			long amount,
			String amountDisplay,
			Method method,
			String reference,
			String note,
			Status status,
			String reviewNote,
			Instant reviewedAt,
			Instant createdAt) {

		public static TopUpView from(WalletTopUpRequest r) {
			return new TopUpView(
					r.getId(),
					r.getAmountMinor(),
					com.storefront.store.common.Money.ofMinor(r.getAmountMinor()).toDisplay(),
					r.getMethod(),
					r.getReference(),
					r.getNote(),
					r.getStatus(),
					r.getReviewNote(),
					r.getReviewedAt(),
					r.getCreatedAt());
		}
	}
}
