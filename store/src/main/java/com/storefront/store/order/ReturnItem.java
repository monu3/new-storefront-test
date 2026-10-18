package com.storefront.store.order;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One order line inside a {@link ReturnRequest} (Phase 8, D14).
 *
 * <p><b>A row per line, not a jsonb array on the request.</b> A partial return is defined by which
 * units came back, and "has this variant already been returned once?" has to be answerable with an
 * index rather than by parsing JSON on every decision. It is also why the same order line cannot
 * appear twice in one request — a unique index enforces that, and a request claiming more units
 * than were bought is not something to validate by hand.
 *
 * <p><b>{@code unitPriceMinor} is snapshotted from the order item.</b> The order line's price is
 * frozen at purchase (D32), and a return is priced against what the customer actually paid — not
 * against today's catalogue price, which will have moved.
 *
 * <p><b>Quantity asked for and quantity approved are separate</b> for the same reason they are on
 * the request: a shop approves three of four units, and the fourth was not returned.
 */
@Entity
@Table(name = "return_item")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ReturnItem extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/**
	 * Lazy on purpose: a return's items are only needed when rendering the request, and the shop's
	 * open-returns queue reads a column off every row without them.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "return_request_id", nullable = false)
	private ReturnRequest returnRequest;

	@Column(name = "order_item_id", nullable = false, updatable = false)
	private UUID orderItemId;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Column(name = "quantity_approved", nullable = false)
	private int quantityApproved = 0;

	/**
	 * The price the customer paid for one unit, copied from the order line.
	 *
	 * <p>Not a relation to the order item, because the order line's unit price is itself a snapshot
	 * and a return has to be priced against that same number. Reading it through would be one more
	 * query per line to obtain a value that cannot change.
	 */
	@Column(name = "unit_price_minor", nullable = false)
	private long unitPriceMinor;

	/**
	 * One line of a return request.
	 *
	 * @param quantity    how many units the customer says came back
	 * @param unitPriceMinor what one unit was sold for, from the order line
	 */
	public static ReturnItem of(UUID orderItemId, int quantity, long unitPriceMinor) {
		if (quantity <= 0) {
			throw new IllegalArgumentException(
					"A return line needs at least one unit, not " + quantity + ".");
		}

		ReturnItem item = new ReturnItem();
		item.id = UUID.randomUUID();
		item.orderItemId = orderItemId;
		item.quantity = quantity;
		item.unitPriceMinor = unitPriceMinor;
		return item;
	}

	/**
	 * How many of the asked-for units the shop agreed to refund.
	 *
	 * <p>Refuses more than was asked for, and refuses a negative — both would otherwise be caught
	 * only by a constraint violation at flush time, with a message about the database rather than
	 * about the decision.
	 */
	public void approveQuantity(int approved) {
		if (approved < 0) {
			throw new IllegalArgumentException(
					"An approved quantity cannot be negative, not " + approved + ".");
		}
		if (approved > quantity) {
			throw new IllegalArgumentException(
					"Cannot approve " + approved + " of " + quantity + " units. The difference is "
							+ "money nobody asked the shop to pay.");
		}

		this.quantityApproved = approved;
	}

	public boolean isFullyApproved() {
		return quantityApproved >= quantity;
	}

	/** What the customer asked for, in money. */
	public long requestedTotalMinor() {
		return unitPriceMinor * (long) quantity;
	}

	/** What the shop agreed to, in money. */
	public long approvedTotalMinor() {
		return unitPriceMinor * (long) quantityApproved;
	}

	/** Wiring-only; not part of the public shape. */
	void attachTo(ReturnRequest request) {
		this.returnRequest = request;
	}
}