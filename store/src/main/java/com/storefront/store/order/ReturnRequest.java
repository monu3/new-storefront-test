package com.storefront.store.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A request to return part or all of an order (Phase 8, D14).
 *
 * <p><b>A row here, not a status on the order.</b> A return is something a customer asks for and a
 * shop decides on, and it may cover one jumper out of four. The order's status can only hold one
 * fact — marking the order {@code RETURNED} while three of four items were still with the customer
 * would be a claim the order cannot make. So the request lives here, and the order moves to
 * {@code RETURNED} only once the entire order value has come back.
 *
 * <p><b>Requested and approved are separate amounts, and the difference is the point.</b> A shop
 * approves what it will refund, which is frequently less than what was asked for: one damaged item
 * out of four is a partial refund, not a declined request. Keeping one number would force a shop to
 * either over-refund or reject a request it should have partly honoured.
 *
 * <p><b>The commission reversed is frozen at refund time.</b> Commission is a platform setting, and
 * a settings change tomorrow must not retroactively alter what was credited back for a return
 * decided today.
 *
 * <p><b>Terminal states have no successors.</b> {@code REFUNDED} and {@code REJECTED} are decided;
 * only {@code REQUESTED} is open. A second approval must be refused by the state machine rather
 * than allowed to move the money twice.
 */
@Entity
@Table(name = "return_request")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ReturnRequest extends BaseEntity {

	public enum Status {
		/** The customer asked. Nobody has decided yet. */
		REQUESTED,
		/** The shop agreed to refund this much. The refund still has to happen. */
		APPROVED,
		/** The shop declined. Terminal. */
		REJECTED,
		/** The money went back. Terminal. */
		REFUNDED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.REQUESTED;

	/** In the customer's words. Required: a return the shop cannot understand is one it guesses at. */
	@Column(name = "reason", nullable = false, columnDefinition = "text")
	private String reason;

	/** The shop's answer. Null while the request is open. */
	@Column(name = "resolution_note", columnDefinition = "text")
	private String resolutionNote;

	@Column(name = "requested_minor", nullable = false)
	private long requestedMinor;

	@Column(name = "approved_minor", nullable = false)
	private long approvedMinor = 0;

	/**
	 * Commission credited back to the platform, frozen when the refund ran.
	 *
	 * <p>A column rather than something derived from the ledger: the question "how much commission
	 * did this return actually give back" is asked during a reconciliation, and re-deriving it
	 * means re-reading commission history and re-running a proportional calculation that may no
	 * longer reproduce, because the rate is a setting and settings change.
	 */
	@Column(name = "commission_reversed_minor", nullable = false)
	private long commissionReversedMinor = 0;

	@Column(name = "requested_by", nullable = false, updatable = false)
	private UUID requestedBy;

	@Column(name = "decided_by")
	private UUID decidedBy;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt = Instant.now();

	@Column(name = "decided_at")
	private Instant decidedAt;

	@OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ReturnItem> items = new ArrayList<>();

	// ------------------------------------------------------------------ lifecycle

	/**
	 * The shop agrees to refund this much.
	 *
	 * <p>Refuses more than was asked for. A request for 500 approving 900 would credit a customer
	 * money nobody asked for, and the shop's wallet would pay for the mistake.
	 */
	public void approve(long amountMinor, UUID decidedBy, String note) {
		require(Status.REQUESTED);

		if (amountMinor < 0) {
			throw new IllegalArgumentException(
					"An approval cannot be negative, not " + amountMinor + ".");
		}
		if (amountMinor > requestedMinor) {
			throw new IllegalArgumentException(
					"Cannot approve " + amountMinor + " against a request for " + requestedMinor
							+ ". The difference would have to come from the shop's wallet and nobody "
							+ "asked for it.");
		}

		this.status = Status.APPROVED;
		this.approvedMinor = amountMinor;
		this.decidedBy = decidedBy;
		this.decidedAt = Instant.now();
		this.resolutionNote = note;
	}

	/** The shop declines. Terminal: a declined return cannot be revived by a later approval. */
	public void reject(UUID decidedBy, String note) {
		require(Status.REQUESTED);

		this.status = Status.REJECTED;
		this.approvedMinor = 0;
		this.decidedBy = decidedBy;
		this.decidedAt = Instant.now();
		this.resolutionNote = note;
	}

	/**
	 * Records that the money actually went back.
	 *
	 * <p>Separate from {@link #approve} because approving and refunding are different moments: the
	 * shop decides first, and the wallet moves when the refund is actually executed. Collapsing them
	 * would credit a wallet for a refund that never ran because the payment provider timed out.
	 */
	public void markRefunded(long commissionReversedMinor) {
		require(Status.APPROVED);

		this.status = Status.REFUNDED;
		this.commissionReversedMinor = commissionReversedMinor;
	}

	/** True while nobody has decided. The shop's queue is exactly these. */
	public boolean isOpen() {
		return status == Status.REQUESTED;
	}

	/** True when the whole order came back, which is the only case that moves the order itself. */
	public boolean isFullReturn() {
		return status == Status.REFUNDED && approvedMinor >= requestedMinor && fullyApproved();
	}

	/** Whether every unit asked for was approved — checked against the lines, not the total. */
	public boolean fullyApproved() {
		return items.stream().allMatch(ReturnItem::isFullyApproved);
	}

	/**
	 * Moves to a status only from where that makes sense.
	 *
	 * <p>Checked in the entity rather than only in the service, because an entity is the one thing
	 * every caller passes through — the same reasoning as the audit trail recording in services
	 * rather than controllers.
	 */
	private void require(Status expected) {
		if (status != expected) {
			throw new IllegalStateException(
					"This return is " + status + ", not " + expected
							+ ". Deciding it twice would move money twice.");
		}
	}

	// ------------------------------------------------------------------ construction

	/**
	 * A new request from a customer.
	 *
	 * <p>{@code requestedMinor} is validated as positive here rather than in the controller: a
	 * request for nothing is not a small request, it is a malformed one, and it should not exist
	 * to be rejected later.
	 */
	public static ReturnRequest requested(UUID orderId, UUID shopId, UUID customerId, String reason,
			long requestedMinor) {

		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"A return needs a reason. Without one the shop is guessing, and the guess costs "
							+ "real money.");
		}
		if (requestedMinor <= 0) {
			throw new IllegalArgumentException(
					"A return request must be for something, not " + requestedMinor + ".");
		}

		ReturnRequest request = new ReturnRequest();
		request.id = UUID.randomUUID();
		request.orderId = orderId;
		request.shopId = shopId;
		request.requestedBy = customerId;
		request.reason = reason.trim();
		request.requestedMinor = requestedMinor;
		request.status = Status.REQUESTED;
		return request;
	}

	public void addItem(ReturnItem item) {
		item.attachTo(this);
		items.add(item);
	}

	/** Sum of what the customer asked for, across the lines. */
	public long requestedTotal() {
		return items.stream().mapToLong(item -> item.getUnitPriceMinor() * item.getQuantity()).sum();
	}

	/** Sum of what the shop approved, across the lines. */
	public long approvedTotal() {
		return items.stream().mapToLong(item -> item.getUnitPriceMinor() * item.getQuantityApproved())
				.sum();
	}
}