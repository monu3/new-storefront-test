package com.storefront.store.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stock for one variant, keyed by product and variant.
 *
 * <p><b>On hand and reserved are separate columns, not one number.</b> Reserved is stock committed
 * to an order that has been placed but not yet accepted. Keeping them apart is what makes "can I
 * sell one more of this?" answerable correctly: the answer is {@code onHand − reserved}, not
 * {@code onHand}.
 *
 * <p>The database enforces {@code quantity_reserved <= quantity_on_hand}, so the invariant holds
 * even if a bug here tries to over-reserve. That constraint is the real guarantee; the checks in
 * this class exist to produce a usable error message before it is reached.
 *
 * <p>Every quantity change is also written to {@code inventory_movement} by
 * {@link InventoryService}. This row is the fast answer; the movement log settles disputes.
 *
 * <p><b>Does not extend {@code BaseEntity}</b>, unlike almost every other entity here, and that is
 * deliberate. A stock row is a current level, not something with a lifecycle: it is created at zero
 * the first time a variant is stocked and then mutated in place forever, so a {@code created_at}
 * would record when the shop first opened the row and mean nothing. The table carries only
 * {@code updated_at}, which is the timestamp that actually answers "when did this last change".
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

	@EmbeddedId
	private InventoryId id;

	@Column(name = "quantity_on_hand", nullable = false)
	private int quantityOnHand = 0;

	/** Committed to orders that have been placed but not yet accepted or cancelled. */
	@Column(name = "quantity_reserved", nullable = false)
	private int quantityReserved = 0;

	/** At or below this the shop is warned. Not auto-depleting: the shop knows its stock. */
	@Column(name = "low_stock_threshold", nullable = false)
	private int lowStockThreshold = 5;

	/** Field initialiser rather than {@code @PrePersist}: see the note on BaseEntity. */
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	public Inventory(UUID productId, UUID variantId) {
		if (productId == null || variantId == null) {
			throw new IllegalArgumentException("Stock belongs to a product and a variant");
		}
		this.id = new InventoryId(productId, variantId);
		this.quantityOnHand = 0;
		this.quantityReserved = 0;
		this.lowStockThreshold = 5;
	}

	public UUID getProductId() {
		return id.getProductId();
	}

	public UUID getVariantId() {
		return id.getVariantId();
	}

	/** Stock that could be sold right now, honouring existing reservations. */
	public int available() {
		return quantityOnHand - quantityReserved;
	}

	public boolean isOutOfStock() {
		return available() <= 0;
	}

	public boolean isLowStock() {
		return available() <= lowStockThreshold;
	}

	/**
	 * Restocks, or corrects a count downwards.
	 *
	 * <p>Takes a delta rather than an absolute quantity, because the movement log records a delta
	 * and a log of "+30" is what makes a discrepancy answerable a month later.
	 */
	public void adjustOnHand(int delta) {
		if (quantityOnHand + delta < 0) {
			throw new IllegalStateException(
					"Cannot remove %d units: only %d are on hand".formatted(-delta, quantityOnHand));
		}
		this.quantityOnHand += delta;
		this.updatedAt = Instant.now();
	}

	/** Sets the count outright, for an opening balance or a stocktake. */
	public void setOnHand(int quantity) {
		if (quantity < 0) {
			throw new IllegalArgumentException("Stock cannot be negative: " + quantity);
		}
		if (quantity < quantityReserved) {
			throw new IllegalStateException(
					("Cannot set stock to %d: %d units are already reserved by placed orders. "
							+ "Cancel those orders first.")
							.formatted(quantity, quantityReserved));
		}
		this.quantityOnHand = quantity;
		this.updatedAt = Instant.now();
	}

	/**
	 * Commits stock to a placed order.
	 *
	 * <p>Refuses to over-reserve. This is the overselling guard, and overselling is the single most
	 * damaging thing a marketplace can do to a customer's trust — worse than an outage, because
	 * the customer has already paid.
	 */
	public void reserve(int quantity) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("Reserve quantity must be positive: " + quantity);
		}
		if (available() < quantity) {
			throw new InsufficientStockException(quantity, available());
		}
		this.quantityReserved += quantity;
		this.updatedAt = Instant.now();
	}

	/**
	 * Releases a reservation, for a cancelled or expired order.
	 *
	 * <p>Clamped at zero rather than throwing: a release that would go negative means the
	 * reservation was already released, and making that a no-op keeps a retried cancellation from
	 * failing an otherwise valid request.
	 */
	public void release(int quantity) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("Release quantity must be positive: " + quantity);
		}
		this.quantityReserved = Math.max(0, quantityReserved - quantity);
		this.updatedAt = Instant.now();
	}

	/**
	 * Converts a reservation into a sale.
	 *
	 * <p>Subtracts <b>only what was reserved</b>, and returns how much it attributed. This is what
	 * makes a retried accept harmless: the second call finds nothing reserved, attributes zero,
	 * changes nothing, and the caller logs nothing.
	 *
	 * <p>An earlier version subtracted the full requested quantity from on-hand while only logging
	 * the reserved part. That looked idempotent in the movement log and was not: a retried accept
	 * silently destroyed stock, 10 units became 2 in a test that only checked the log. Subtle, and
	 * exactly the kind of bug an inventory test has to assert on the shelf rather than the log.
	 *
	 * <p>A backordered order attributes nothing, because its goods were never on the shelf. The
	 * shop's shortfall is resolved by restocking, and that restock is its own logged movement —
	 * inventing a deduction here would only produce a negative count to reconcile.
	 *
	 * @return the units actually taken off the shelf
	 */
	public int commitReservation(int quantity) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("Commit quantity must be positive: " + quantity);
		}
		int attributed = Math.min(quantity, quantityReserved);
		if (attributed == 0) {
			return 0;
		}
		this.quantityReserved -= attributed;
		this.quantityOnHand = Math.max(0, quantityOnHand - attributed);
		this.updatedAt = Instant.now();
		return attributed;
	}

	public void setLowStockThreshold(int threshold) {
		if (threshold < 0) {
			throw new IllegalArgumentException("Low-stock threshold cannot be negative: " + threshold);
		}
		this.lowStockThreshold = threshold;
		this.updatedAt = Instant.now();
	}

	/** Maps to HTTP 409: the request is well-formed, the stock level is not. */
	public static class InsufficientStockException extends RuntimeException {

		private final int requested;
		private final int available;

		public InsufficientStockException(int requested, int available) {
			super("Only %d in stock, but %d were requested".formatted(available, requested));
			this.requested = requested;
			this.available = available;
		}

		public int getRequested() {
			return requested;
		}

		public int getAvailable() {
			return available;
		}
	}
}
