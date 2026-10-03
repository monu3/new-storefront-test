package com.storefront.store.checkout;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;
import com.storefront.store.common.Money;

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
 * A discount a shop offers.
 *
 * <p>Three types, and they behave differently in ways that matter:
 *
 * <ul>
 *   <li><b>PERCENT</b> — {@link #value} is basis points, so 10% is 1000.</li>
 *   <li><b>FIXED</b> — {@link #value} is paisa.</li>
 *   <li><b>FREE_SHIPPING</b> — {@link #value} is ignored entirely; the discount is the shipping
 *       charge, which is why {@link CheckoutService} handles it separately from the other two.
 *       A "fixed Rs. 100" free-shipping discount would be a category error that only shows up as
 *       an unexplained total at the last step of checkout.</li>
 * </ul>
 *
 * <p>{@link #maxDiscountMinor} is the cap that stops a shop's own mistake costing real money:
 * 50% off a Rs. 10,000 order is Rs. 5,000, and a mistyped code should not be able to do that
 * without a cap in place.
 *
 * <p>{@link #code} is null for an automatic discount (a "10% off your first order" banner). The
 * partial unique index on {@code (shop_id, code)} only applies where a code exists.
 */
@Entity
@Table(name = "discount")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discount extends BaseEntity {

	public enum Type {
		PERCENT,
		FIXED,
		FREE_SHIPPING
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	/** citext, so SAVE10 and save10 are the same code. Null for an automatic discount. */
	@Column(name = "code", columnDefinition = "citext")
	private String code;

	@Column(name = "name", nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false)
	private Type type;

	/** Basis points for PERCENT, paisa for FIXED, unused for FREE_SHIPPING. */
	@Column(name = "value", nullable = false)
	private long value;

	@Column(name = "min_order_minor")
	private Long minOrderMinor;

	/** Caps a percentage discount. The difference between a promotion and a liability. */
	@Column(name = "max_discount_minor")
	private Long maxDiscountMinor;

	@Column(name = "usage_limit")
	private Integer usageLimit;

	@Column(name = "usage_used", nullable = false)
	private int usageUsed = 0;

	/** Times one customer may use it. Null means no limit. */
	@Column(name = "per_customer_limit")
	private Integer perCustomerLimit;

	@Column(name = "starts_at")
	private Instant startsAt;

	@Column(name = "ends_at")
	private Instant endsAt;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	public Discount(UUID shopId, String code, String name, Type type, long value) {
		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A discount needs a name");
		}
		if (type == null) {
			throw new IllegalArgumentException("A discount needs a type");
		}
		if (value < 0) {
			throw new IllegalArgumentException("Discount value cannot be negative: " + value);
		}
		if (type == Type.PERCENT && value > 10_000) {
			throw new IllegalArgumentException("A percentage discount cannot exceed 100%: " + value + "bp");
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.code = code == null || code.isBlank() ? null : code.trim();
		this.name = name.trim();
		this.type = type;
		this.value = value;
		this.active = true;
	}

	/**
	 * Whether the discount may be used right now, ignoring the cart.
	 *
	 * <p>Window and usage checks only. Whether the cart qualifies is {@link #appliesTo}, which
	 * needs the subtotal, so the two are separated: a shop's discount list shows every code, and
	 * only the ones that work on this basket are marked applicable.
	 */
	public boolean isUsableNow() {
		if (!active) {
			return false;
		}
		Instant now = Instant.now();
		if (startsAt != null && now.isBefore(startsAt)) {
			return false;
		}
		if (endsAt != null && !now.isBefore(endsAt)) {
			return false;
		}
		if (usageLimit != null && usageUsed >= usageLimit) {
			return false;
		}
		return true;
	}

	/** Whether this discount qualifies on a subtotal. */
	public boolean appliesTo(long subtotalMinor) {
		if (!isUsableNow()) {
			return false;
		}
		return minOrderMinor == null || subtotalMinor >= minOrderMinor;
	}

	/**
	 * How much this discount takes off a subtotal.
	 *
	 * <p>Never more than the subtotal. A fixed discount larger than the order would produce a
	 * negative total, and the arithmetic error would surface as a checkout that refuses to
	 * complete for no reason the customer can see.
	 *
	 * @return the discount in paisa, or 0
	 */
	public long amountOff(long subtotalMinor) {
		if (!appliesTo(subtotalMinor) || type == Type.FREE_SHIPPING) {
			return 0;
		}

		long discount = switch (type) {
			case PERCENT -> Money.ofMinor(subtotalMinor).applyBasisPoints((int) value).minor();
			case FIXED -> value;
			case FREE_SHIPPING -> 0;
		};

		if (maxDiscountMinor != null && discount > maxDiscountMinor) {
			discount = maxDiscountMinor;
		}
		return Math.min(discount, subtotalMinor);
	}

	/** Why a discount cannot be used, for a message the customer can act on. Null when it can. */
	public String unusableReason(long subtotalMinor) {
		if (!active) {
			return name + " is no longer available";
		}
		Instant now = Instant.now();
		if (startsAt != null && now.isBefore(startsAt)) {
			return name + " is not available yet";
		}
		if (endsAt != null && !now.isBefore(endsAt)) {
			return name + " has expired";
		}
		if (usageLimit != null && usageUsed >= usageLimit) {
			return name + " has been used up";
		}
		if (minOrderMinor != null && subtotalMinor < minOrderMinor) {
			return name + " needs an order of at least " + Money.ofMinor(minOrderMinor).toDisplay();
		}
		return null;
	}

	/** Records one use. Called when an order using this discount is placed, not when quoted. */
	public void recordUse() {
		this.usageUsed++;
	}

	/** Whether this customer has already used it enough times. */
	public boolean allowsCustomer(int timesUsed) {
		return perCustomerLimit == null || timesUsed < perCustomerLimit;
	}
}
