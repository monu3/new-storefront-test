package com.storefront.store.inventory;

import java.time.Instant;
import java.util.UUID;

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
 * An append-only record of one stock movement.
 *
 * <p>Never updated, never deleted. The {@code inventory} row says how much there is now; this
 * says how it got that way. "You say I ordered 40 and you say I only got 30" is unanswerable
 * without this table, months later, when nobody remembers.
 *
 * <p>{@link #delta} is signed: positive added stock, negative removed it. The reason enum and
 * the reference columns then say why, so the delta alone is never the whole story.
 */
@Entity
@Table(name = "inventory_movement")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
/**
 * <p><b>Does not extend {@code BaseEntity}</b>, unlike most entities here. Its table carries
 * {@code created_at} but no {@code updated_at}, and that is correct rather than an oversight:
 * this row is written once and never edited. A {@code created_at} that answers "when did this
 * happen" is the whole story; an {@code updated_at} would always equal it and invite a reader to
 * think the row had been changed since.
 */
public class InventoryMovement {

	/** Field initialiser rather than {@code @PrePersist}: see the note on BaseEntity. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/** Why stock moved. Kept in step with the database CHECK constraint. */
	public enum Reason {
		/** An order was accepted and the goods left. */
		SALE,
		/** The shop added stock. */
		RESTOCK,
		/** A stocktake correction, in either direction. */
		ADJUSTMENT,
		/** A return came back (Phase 8). */
		RETURN,
		/** An order was cancelled or expired, releasing a reservation. */
		CANCEL,
		/** A bulk import (Phase 2). */
		IMPORT
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "variant_id", nullable = false, updatable = false)
	private UUID variantId;

	/** Signed: positive added, negative removed. */
	@Column(name = "delta", nullable = false, updatable = false)
	private int delta;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", nullable = false, updatable = false)
	private Reason reason;

	/** What caused it: an ORDER is the common case. */
	@Column(name = "reference_type")
	private String referenceType;

	@Column(name = "reference_id")
	private UUID referenceId;

	@Column(name = "note")
	private String note;

	@Column(name = "created_by")
	private UUID createdBy;

	public InventoryMovement(UUID productId, UUID variantId, int delta, Reason reason,
			String referenceType, UUID referenceId, String note, UUID createdBy) {

		if (productId == null || variantId == null) {
			throw new IllegalArgumentException("A movement belongs to a product and a variant");
		}
		if (delta == 0 && !isOrderEvent(reason)) {
			// A zero-delta manual movement is noise that later has to be explained away during a
			// reconciliation. Order-driven events are the exception, and for a real reason: the
			// delta column records *on-hand* changes, and two order events do not change on-hand
			// at all. Releasing a reservation (CANCEL) and honouring a backorder (SALE) both belong
			// in the log precisely because they move no stock — an auditor asking "why was this
			// never deducted?" or "why was this promised when nothing was there?" needs the row.
			throw new IllegalArgumentException("A movement must change stock by a non-zero amount");
		}
		if (reason == null) {
			throw new IllegalArgumentException("A movement needs a reason");
		}

		this.id = UUID.randomUUID();
		this.productId = productId;
		this.variantId = variantId;
		this.delta = delta;
		this.reason = reason;
		this.referenceType = referenceType;
		this.referenceId = referenceId;
		this.note = note;
		this.createdBy = createdBy;
	}

	/**
	 * Whether this reason is driven by an order rather than by a manual action.
	 *
	 * <p>Only order events may carry a zero delta, because only they can record something
	 * meaningful without changing the quantity on the shelf.
	 */
	private static boolean isOrderEvent(Reason reason) {
		return reason == Reason.SALE || reason == Reason.CANCEL;
	}

	public static InventoryMovement restock(UUID productId, UUID variantId, int units, UUID actor) {
		return new InventoryMovement(productId, variantId, Math.abs(units), Reason.RESTOCK,
				"MANUAL", null, null, actor);
	}

	public static InventoryMovement sale(UUID productId, UUID variantId, int units, UUID orderId) {
		return new InventoryMovement(productId, variantId, -Math.abs(units), Reason.SALE,
				"ORDER", orderId, null, null);
	}

	public static InventoryMovement released(UUID productId, UUID variantId, int units, UUID orderId) {
		return new InventoryMovement(productId, variantId, 0, Reason.CANCEL,
				"ORDER", orderId, null, null);
	}

	public static InventoryMovement adjustment(UUID productId, UUID variantId, int delta,
			String note, UUID actor) {
		return new InventoryMovement(productId, variantId, delta, Reason.ADJUSTMENT,
				"MANUAL", null, note, actor);
	}
}
