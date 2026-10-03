package com.storefront.store.order;

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
 * An append-only record of one status change.
 *
 * <p>Never updated, never deleted. "When did this shop accept the order?" has to be answerable
 * months later, after the shop has been audited, after the customer has disputed delivery, and
 * after the order row itself has been archived. A single mutable {@code status} column on the
 * order cannot answer that; a timeline can.
 *
 * <p>The first row of every order has a null {@code fromStatus} and a {@code toStatus} of
 * PENDING, which is what makes the timeline complete rather than starting one step in.
 *
 * <p>Lombok for accessors only — the constructor is hand-written because it is the only way to
 * create a row, and that is deliberate.
 */
@Entity
@Table(name = "order_status_history")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	/** Null for the order's first entry, which records the placement itself. */
	@Column(name = "from_status")
	@Enumerated(EnumType.STRING)
	private ShopOrder.Status fromStatus;

	@Column(name = "to_status", nullable = false, updatable = false)
	@Enumerated(EnumType.STRING)
	private ShopOrder.Status toStatus;

	@Column(name = "changed_by")
	private UUID changedBy;

	/** Why, when the answer is not obvious: a cancellation reason, a hold note. */
	@Column(name = "note")
	private String note;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	public OrderStatusHistory(UUID orderId, ShopOrder.Status fromStatus, ShopOrder.Status toStatus,
			UUID changedBy, String note) {

		if (orderId == null) {
			throw new IllegalArgumentException("orderId is required");
		}
		if (toStatus == null) {
			throw new IllegalArgumentException("A history entry needs a resulting status");
		}
		this.id = UUID.randomUUID();
		this.orderId = orderId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.changedBy = changedBy;
		this.note = note;
	}

	public static OrderStatusHistory placed(UUID orderId, UUID actorUserId) {
		return new OrderStatusHistory(orderId, null, ShopOrder.Status.PENDING, actorUserId, null);
	}

	/** The customer-facing tracker. One entry per state the order has actually reached. */
	public record HistoryView(ShopOrder.Status status, String note, Instant at) {

		public static HistoryView from(OrderStatusHistory entry) {
			return new HistoryView(entry.getToStatus(), entry.getNote(), entry.getCreatedAt());
		}
	}
}
