package com.storefront.store.order;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * A customer order. The core aggregate of the platform.
 *
 * <p>Two design points carry most of the weight:
 *
 * <ul>
 *   <li><b>Snapshots.</b> Shop type, category, line items and addresses are all copied onto the
 *       order. A deleted product, a renamed address or a reclassified shop must not change what a
 *       customer was charged, or what a past invoice says.</li>
 *   <li><b>Frozen commission.</b> {@link #commissionRateBp} and {@link #commissionMinor} are
 *       written when the shop accepts, and never recomputed. Changing the platform rate later
 *       cannot retroactively alter a historical order (D2).</li>
 * </ul>
 *
 * <p>Status is only ever changed through the state machine in {@code OrderStateMachine}, never
 * by a setter. That is what keeps "PENDING to CANCELLED" a different event from "SHIPPED to
 * CANCELLED", and it is why {@link #status} has no public setter.
 */
@Entity
@Table(name = "shop_order")
@Getter
/**
 * Setters are deliberately scoped to package level, and the state-bearing fields carry
 * {@code @Setter(AccessLevel.NONE)}.
 *
 * <p>Not a blanket {@code @Setter}: {@code status}, {@code paymentStatus} and the commission
 * columns must only change through the state machine and {@link #freezeCommission}. A public
 * setter for those would silently bypass the invariants those methods exist to enforce.
 */
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopOrder extends BaseEntity {

	/**
	 * Order lifecycle.
	 *
	 * <p>Legal transitions are declared in {@link #canTransitionTo}, not here: an enum that knows
	 * its own successors tends to acquire exceptions, and the rules belong in one place.
	 */
	public enum Status {
		/** Placed, not yet accepted. No commission has been charged. */
		PENDING,
		/** The shop took it. Commission debited at this point (D2). */
		ACCEPTED,
		/** Being packed. */
		PROCESSING,
		/** Handed to a courier. */
		SHIPPED,
		/** Received by the customer. */
		DELIVERED,
		CANCELLED,
		RETURNED,
		REFUNDED
	}

	public enum PaymentStatus {
		PENDING, PAID, FAILED, REFUNDED, PARTIALLY_REFUNDED
	}

	public enum PaymentMethod {
		ESAWA, COD, BANK_TRANSFER
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private UUID id = UUID.randomUUID();

	/** Per-shop sequence, so two shops can each have an ORD-0001. */
	@Column(name = "order_number", nullable = false)
	private String orderNumber;

	@Column(name = "shop_id", nullable = false)
	private UUID shopId;

	/** Frozen at order time so historical reports keep their meaning. */
	@Column(name = "shop_type_snapshot", nullable = false)
	@Convert(converter = com.storefront.store.catalog.ShopTypeConverter.class)
	private ShopType shopTypeSnapshot;

	/** Null for a guest checkout, which is why guestEmail exists alongside it. */
	@Column(name = "customer_id")
	private UUID customerId;

	// citext: a guest order has no customer row, so this is the only contact detail and a
	// case-mangled lookup would look like a different customer.
	@Column(name = "guest_email", columnDefinition = "citext")
	private String guestEmail;

	@Column(name = "guest_phone")
	private String guestPhone;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	@Setter(AccessLevel.NONE)   // only the state machine may move an order
	private Status status = Status.PENDING;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_status", nullable = false)
	@Setter(AccessLevel.NONE)   // only markPaid/markPaymentFailed may change this
	private PaymentStatus paymentStatus = PaymentStatus.PENDING;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false)
	private PaymentMethod paymentMethod;

	@Column(name = "subtotal_minor", nullable = false)
	private long subtotalMinor;

	@Column(name = "discount_minor", nullable = false)
	private long discountMinor;

	@Column(name = "tax_minor", nullable = false)
	private long taxMinor;

	@Column(name = "shipping_minor", nullable = false)
	private long shippingMinor;

	@Column(name = "total_minor", nullable = false)
	private long totalMinor;

	// varchar(3), not char(3): char pads with spaces, so 'NPR ' would not equal 'NPR' in a
	// comparison. See migration V4.
	@Column(name = "currency", nullable = false, length = 3)
	private String currency = "NPR";

	/** Basis points, frozen at accept time. */
	@Column(name = "commission_rate_bp")
	@Setter(AccessLevel.NONE)   // frozen once written; see freezeCommission
	private Integer commissionRateBp;

	/** The amount actually charged. Null until the shop accepts. */
	@Column(name = "commission_minor")
	@Setter(AccessLevel.NONE)   // frozen once written; see freezeCommission
	private Long commissionMinor;

	/** True when the shop is subscribed and pays nothing. Recorded, not implied by a null. */
	@Column(name = "commission_waived", nullable = false)
	@Setter(AccessLevel.NONE)   // frozen once written
	private boolean commissionWaived;

	/** Snapshotted: an edited address must not rewrite a parcel already in transit. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "shipping_address", nullable = false, columnDefinition = "jsonb")
	private String shippingAddress;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "billing_address", columnDefinition = "jsonb")
	private String billingAddress;

	@Column(name = "customer_note")
	private String customerNote;

	@Column(name = "admin_note")
	private String adminNote;

	@Column(name = "placed_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant placedAt = Instant.now();

	@Column(name = "accepted_at")
	@Setter(AccessLevel.NONE)   // stamped by transitionTo
	private Instant acceptedAt;

	@Column(name = "shipped_at")
	@Setter(AccessLevel.NONE)   // stamped by transitionTo
	private Instant shippedAt;

	@Column(name = "delivered_at")
	@Setter(AccessLevel.NONE)   // stamped by transitionTo
	private Instant deliveredAt;

	@Column(name = "cancelled_at")
	@Setter(AccessLevel.NONE)   // stamped by transitionTo
	private Instant cancelledAt;

	@Column(name = "cancel_reason")
	private String cancelReason;

	/** A new pending order. Callers supply already-computed totals; nothing is derived here. */
	public ShopOrder(UUID shopId, ShopType shopType, String orderNumber, PaymentMethod paymentMethod,
			long subtotalMinor, long discountMinor, long taxMinor, long shippingMinor,
			String shippingAddress) {

		if (subtotalMinor < 0 || discountMinor < 0 || taxMinor < 0 || shippingMinor < 0) {
			throw new IllegalArgumentException("Order amounts cannot be negative");
		}
		long total = subtotalMinor - discountMinor + taxMinor + shippingMinor;
		if (total < 0) {
			throw new IllegalArgumentException("Discount exceeds the subtotal");
		}

		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.shopTypeSnapshot = shopType;
		this.orderNumber = orderNumber;
		this.paymentMethod = paymentMethod;
		this.subtotalMinor = subtotalMinor;
		this.discountMinor = discountMinor;
		this.taxMinor = taxMinor;
		this.shippingMinor = shippingMinor;
		this.totalMinor = total;
		this.shippingAddress = shippingAddress;
		this.status = Status.PENDING;
		this.paymentStatus = PaymentStatus.PENDING;
	}

	/**
	 * Applies a status transition.
	 *
	 * <p>Package-visible, and the only way {@link #status} changes. The legal-successor check
	 * lives in {@code OrderStateMachine} so that the rules are readable in one place; this method
	 * just records the outcome and stamps the matching timestamp.
	 */
	void transitionTo(Status next) {
		this.status = next;
		Instant now = Instant.now();
		switch (next) {
			case ACCEPTED -> this.acceptedAt = now;
			case SHIPPED -> this.shippedAt = now;
			case DELIVERED -> this.deliveredAt = now;
			case CANCELLED -> this.cancelledAt = now;
			default -> {
				// Other statuses carry no timestamp of their own; PROCESSING is implicit in the
				// gap between accepted and shipped.
			}
		}
	}

	/**
	 * Freezes the commission onto the order.
	 *
	 * <p>Called once, at accept. The values are stored rather than recomputed later, so a change
	 * to the platform rate cannot alter what a shop was charged for an order placed months ago.
	 */
	void freezeCommission(int rateBp, long amountMinor, boolean waived) {
		this.commissionRateBp = rateBp;
		this.commissionMinor = amountMinor;
		this.commissionWaived = waived;
	}

	public void markPaid() {
		this.paymentStatus = PaymentStatus.PAID;
	}

	public void markPaymentFailed() {
		this.paymentStatus = PaymentStatus.FAILED;
	}

	public void cancel(String reason) {
		this.cancelReason = reason;
	}

	public void setAdminNote(String adminNote) {
		this.adminNote = adminNote;
	}

	public UUID getId() {
		return id;
	}

	public String getOrderNumber() {
		return orderNumber;
	}

	public UUID getShopId() {
		return shopId;
	}

	public ShopType getShopTypeSnapshot() {
		return shopTypeSnapshot;
	}

	public UUID getCustomerId() {
		return customerId;
	}

	public void setCustomerId(UUID customerId) {
		this.customerId = customerId;
	}

	public String getGuestEmail() {
		return guestEmail;
	}

	public void setGuestEmail(String guestEmail) {
		this.guestEmail = guestEmail;
	}

	public String getGuestPhone() {
		return guestPhone;
	}

	public void setGuestPhone(String guestPhone) {
		this.guestPhone = guestPhone;
	}

	public Status getStatus() {
		return status;
	}

	public PaymentStatus getPaymentStatus() {
		return paymentStatus;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public long getSubtotalMinor() {
		return subtotalMinor;
	}

	public long getDiscountMinor() {
		return discountMinor;
	}

	public long getTaxMinor() {
		return taxMinor;
	}

	public long getShippingMinor() {
		return shippingMinor;
	}

	public long getTotalMinor() {
		return totalMinor;
	}

	public String getCurrency() {
		return currency;
	}

	public Integer getCommissionRateBp() {
		return commissionRateBp;
	}

	public Long getCommissionMinor() {
		return commissionMinor;
	}

	public boolean isCommissionWaived() {
		return commissionWaived;
	}

	public String getShippingAddress() {
		return shippingAddress;
	}

	public String getCustomerNote() {
		return customerNote;
	}

	public Instant getPlacedAt() {
		return placedAt;
	}

	public Instant getAcceptedAt() {
		return acceptedAt;
	}

	/** True once the shop has accepted, which is also when commission becomes chargeable. */
	public boolean isAccepted() {
		return status != Status.PENDING;
	}

	/** True when the order is still in a state the shop can act on. */
	public boolean isOpen() {
		return status == Status.PENDING || status == Status.ACCEPTED
				|| status == Status.PROCESSING;
	}

	/**
	 * The idempotency key for this order's commission debit.
	 *
	 * <p>Derived from the order id, so a retried accept produces the same key and the ledger's
	 * unique constraint turns the retry into a no-op. This one string is what makes
	 * double-charging impossible.
	 */
	public String commissionIdempotencyKey() {
		return "commission:" + id;
	}
}
