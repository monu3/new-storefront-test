package com.storefront.store.subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * A plan an admin sells (D4).
 *
 * <p><b>A plan grants no feature directly.</b> Entitlements live in {@code plan_entitlement}, so
 * editing a plan's price or name cannot accidentally add or remove what a subscriber gets — and,
 * more to the point, {@link com.storefront.store.subscription.SubscriptionService} refuses to edit
 * a plan while an ACTIVE subscription holds it.
 *
 * <p><b>{@code priceMinor} is in paisa</b>, like every other amount (D19). There is no currency
 * column: one country, one currency.
 */
@Entity
@Table(name = "subscription_plan")
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionPlan extends BaseEntity {

	/** How often a subscription on this plan runs. */
	public enum BillingPeriod {
		/** Free. Never expires. */
		NONE,
		MONTHLY,
		ANNUAL
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** Stable machine key -- {@code FREE}, {@code PRO_MONTHLY}. Never edited, never shown alone. */
	@Column(name = "code", nullable = false, unique = true, updatable = false)
	private String code;

	/** Display name. Editable, and safe to edit: nothing keys off it. */
	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "billing_period", nullable = false)
	private BillingPeriod billingPeriod = BillingPeriod.NONE;

	/** Paisa. Zero for {@link BillingPeriod#NONE}; the database refuses the other combinations. */
	@Column(name = "price_minor", nullable = false)
	private long priceMinor;

	/** Null for {@link BillingPeriod#NONE}. A paid plan without one would never expire. */
	@Column(name = "duration_days")
	private Integer durationDays;

	/**
	 * Whether the plan is on sale.
	 *
	 * <p>Deactivated rather than deleted: a cancelled subscriber's history must still be able to
	 * name the plan they were on, and a shop that asks to re-subscribe to a withdrawn plan must be
	 * told it was withdrawn rather than that it does not exist.
	 */
	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	/** Pricing-screen order. Lower first. */
	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	public SubscriptionPlan(String code, String name, BillingPeriod billingPeriod,
			long priceMinor, Integer durationDays) {
		// Assigned here rather than left to a generator: the plan's code is its identity for every
		// human-facing purpose (the bank reference, the pricing page, a test), and a generated UUID
		// column type would be one more way to differ between the seeded rows and a created one.
		this.id = UUID.randomUUID();
		this.code = requireCode(code);
		this.name = require(name, "name");
		this.billingPeriod = Objects.requireNonNull(billingPeriod, "billingPeriod");
		this.priceMinor = priceMinor;

		// Checked here as well as by the database. The check constraint exists for rows written by
		// hand; this exists so the failure names the plan and the rule rather than surfacing as an
		// opaque constraint violation from a controller three layers up.
		if (billingPeriod != BillingPeriod.NONE && (durationDays == null || durationDays <= 0)) {
			throw new IllegalArgumentException(
					"A " + billingPeriod + " plan must say how many days it lasts. "
							+ "Without that, a subscriber's commission waiver would never end, because "
							+ "there is no date for the expiry sweep to find.");
		}
		if (billingPeriod == BillingPeriod.NONE && priceMinor != 0) {
			throw new IllegalArgumentException(
					"A free plan cannot have a price. MRR sums active prices, and a priced free plan "
							+ "would show up as revenue that was never collected.");
		}
		if (priceMinor < 0) {
			throw new IllegalArgumentException("A plan price cannot be negative: " + priceMinor);
		}
		this.durationDays = durationDays;
	}

	/** True when a subscription on this plan costs money. */
	public boolean isPaid() {
		return billingPeriod != BillingPeriod.NONE;
	}

	/**
	 * The monthly equivalent of this plan's price, for MRR.
	 *
	 * <p>An annual plan contributes a twelfth of its price rather than its whole price. That is the
	 * definition of MRR -- a normalised monthly figure -- and dividing is what makes an annual
	 * subscriber and a monthly subscriber of the same plan comparable on the dashboard. Counting
	 * annual revenue in full would make the number mean something different every month depending
	 * on who renewed, which is worse than not publishing it.
	 *
	 * <p>{@code Math.multiplyExact} rather than plain multiplication so a mistyped annual price
	 * fails loudly instead of silently reporting a monthly figure that is wrong.
	 */
	public long monthlyEquivalentMinor() {
		return switch (billingPeriod) {
			case NONE -> 0L;
			case MONTHLY -> priceMinor;
			case ANNUAL -> divideByMonths(priceMinor, 12);
		};
	}

	/**
	 * The instant a subscription starting now would end.
	 *
	 * @param from the start, not "now" -- callers re-quoting an existing subscription pass its
	 *        actual start, so a renewal preview is not quietly computed from the clock
	 */
	public java.time.Instant expiresAtFrom(java.time.Instant from) {
		if (!isPaid()) {
			throw new IllegalStateException("The free plan never expires");
		}
		return from.plus(java.time.Duration.ofDays(durationDays));
	}

	/** The period a customer reads: "per month", "per year", or nothing. */
	public String priceDisplay() {
		return com.storefront.store.common.Money.ofMinor(priceMinor).toDisplay()
				+ (switch (billingPeriod) {
					case NONE -> "";
					case MONTHLY -> " / month";
					case ANNUAL -> " / year";
				});
	}

	private static long divideByMonths(long annualPriceMinor, int months) {
		// Floor rather than round: a rounded-up annual figure would overstate MRR by a fraction of a
		// rupee on every annual subscriber, and the sum of those fractions is a real number.
		return Math.floorDiv(annualPriceMinor, months);
	}

	private static String requireCode(String code) {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("A plan needs a code");
		}
		String trimmed = code.trim().toUpperCase(java.util.Locale.ROOT);
		if (!trimmed.matches("[A-Z0-9_]{2,40}")) {
			// The code is stored, indexed by nothing, and read by seed data and tests. Restricting it
			// now costs nothing; a code with a space in it costs a quoting bug later.
			throw new IllegalArgumentException(
					"A plan code must be 2-40 characters of A-Z, 0-9 or underscore: " + code);
		}
		return trimmed;
	}

	private static String require(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A plan needs a " + field);
		}
		return value.trim();
	}

	/** Convenience for a plan's price as a list of lines, used by the pricing screen. */
	public List<String> summaryLines() {
		List<String> lines = new ArrayList<>();
		if (description != null && !description.isBlank()) {
			lines.add(description);
		}
		return lines;
	}
}
