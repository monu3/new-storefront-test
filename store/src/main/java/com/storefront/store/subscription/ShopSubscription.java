package com.storefront.store.subscription;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One shop's subscription (D4, D7).
 *
 * <p><b>What cancelling does, precisely.</b> {@link #cancel()} records the <em>intent</em> not to
 * renew and leaves {@link Status#ACTIVE} alone. The subscription then keeps granting entitlements
 * until {@code expiresAt}, and only becomes {@link Status#CANCELLED} once that moment passes.
 *
 * <p>The reasoning: the shop has already paid for the period. Cancelling on day 3 of a 31-day plan
 * and losing commission-free trading the same afternoon would mean a cancellation is a refund the
 * system silently performs, and a shop that upgraded to try it out would be charged in full and
 * then lose what it bought. If cancelling ought to stop access immediately, the honest version is a
 * pro-rata refund, which D7's manual-top-up model has no way to express.
 *
 * <p>So: {@link Status#EXPIRED} means the window closed with no cancellation; {@link Status#CANCELLED}
 * means it closed because the shop said so. Both stop granting. The distinction is kept because
 * churn is a business number -- "how many shops left" is not the same question as "how many lapsed"
 * -- and collapsing them would answer it wrongly.
 *
 * <p><b>{@code autoRenew} is always false in v1</b> (D7: no recurring charging). The column exists
 * so that a shop which chose not to renew is recorded as having chosen, rather than inferred from
 * the absence of a subscription.
 */
@Entity
@Table(name = "shop_subscription")
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopSubscription extends BaseEntity
		implements com.storefront.store.wallet.WalletTopUpService.SubscriptionLink {

	/**
	 * Whether this subscription grants anything.
	 *
	 * <p>{@link #PENDING_PAYMENT} is a queued intent, not a subscription: nothing is granted, nothing
	 * is charged, and the shop is still on commission.
	 */
	public enum Status {
		/** Requested, awaiting a top-up and an admin's approval. */
		PENDING_PAYMENT,
		/** Paid, running, and granting entitlements. */
		ACTIVE,
		/** The window closed on its own. */
		EXPIRED,
		/** The window closed because the shop cancelled. */
		CANCELLED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "plan_id", nullable = false)
	private SubscriptionPlan plan;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.PENDING_PAYMENT;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	/** Always false in v1. See the class note. */
	@Column(name = "auto_renew", nullable = false)
	private boolean autoRenew;

	/** The top-up that paid for this, once there is one. */
	@Column(name = "topup_request_id")
	private UUID topupRequestId;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	public ShopSubscription(UUID shopId, SubscriptionPlan plan) {
		if (shopId == null) {
			throw new IllegalArgumentException("A subscription needs a shop");
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.plan = java.util.Objects.requireNonNull(plan, "plan");
		this.status = Status.PENDING_PAYMENT;
		this.autoRenew = false;
	}

	// ------------------------------------------------------------------ lifecycle

	/**
	 * Starts the subscription.
	 *
	 * <p><b>Only from {@link Status#PENDING_PAYMENT}.</b> Re-activating an already-running
	 * subscription would extend a period that has already been paid for, which is a way to invent
	 * paid time for free.
	 *
	 * @param startedAt when the clock starts, supplied rather than read so a test can activate a
	 *        subscription that started an hour ago and assert on a deterministic window
	 * @throws IllegalStateException when this subscription is not pending
	 */
	public void activate(Instant startedAt, UUID topupRequestId) {
		if (status != Status.PENDING_PAYMENT) {
			throw new IllegalStateException(
					"Only a pending subscription can be activated; this one is " + status);
		}
		if (!plan.isPaid()) {
			// The free tier is not a subscription. Materialising it as one would put an ACTIVE row
			// with no window on every shop in the marketplace, and the unique index would then stop
			// any of them from ever subscribing.
			throw new IllegalStateException(
					"The free plan is the absence of a subscription, not a subscription to it");
		}
		if (startedAt == null) {
			throw new IllegalArgumentException("A subscription needs a start");
		}

		this.status = Status.ACTIVE;
		this.startedAt = startedAt;
		this.expiresAt = plan.expiresAtFrom(startedAt);
		this.topupRequestId = topupRequestId;
	}

	/**
	 * Records that the shop will not renew.
	 *
	 * <p>Does <b>not</b> change {@link #status}. See the class note: entitlements run to
	 * {@link #expiresAt} either way.
	 */
	public void cancel() {
		if (status != Status.ACTIVE && status != Status.PENDING_PAYMENT) {
			// Cancelling something already closed. Silently accepted would mean a double-click on
			// "cancel" moved cancelled_at on a dead subscription, which then reads as a recent
			// churn event in a report.
			return;
		}
		this.cancelledAt = Instant.now();
		this.autoRenew = false;

		// A pending one is cancelled outright: there is nothing paid-for to honour.
		if (status == Status.PENDING_PAYMENT) {
			this.status = Status.CANCELLED;
		}
	}

	/**
	 * Closes a lapsed subscription.
	 *
	 * <p>Called by the expiry sweep. Refuses to close one that has not actually lapsed, because
	 * doing so would revoke entitlements a shop has paid for -- and the sweep runs over
	 * {@code expires_at < now()}, so a clock skew or a bad {@code expires_at} must not silently
	 * become somebody's lost subscription.
	 *
	 * @param now the sweep's clock, passed in so the caller and this method agree
	 * @return whether anything changed
	 */
	public boolean expire(Instant now) {
		if (status != Status.ACTIVE) {
			return false;
		}
		if (expiresAt == null || !now.isAfter(expiresAt)) {
			// Not actually due. Said rather than ignored: the sweep would otherwise re-select this
			// row on every run, forever.
			return false;
		}

		// CANCELLED rather than EXPIRED when the shop chose it, so churn and lapse stay separable.
		this.status = cancelledAt != null ? Status.CANCELLED : Status.EXPIRED;
		return true;
	}

	/**
	 * Closes this subscription because the shop bought a different plan.
	 *
	 * <p><b>Distinct from {@link #expire}, and it has to be.</b> {@code expire} deliberately refuses
	 * to close a subscription whose window has not closed, because that is what stops a clock skew in
	 * the sweep from revoking a period somebody paid for. A plan change is not that situation: the
	 * window is wide open and the shop has <em>chosen</em> to move, so the one-active-per-shop
	 * partial unique index refuses the upgrade until the old row is closed.
	 *
	 * <p>Using {@code expire} here made every mid-term upgrade fail with a constraint violation on
	 * an index that names neither the cause nor the fix. Recorded as CANCELLED rather than EXPIRED:
	 * the shop did not lapse, it moved.
	 *
	 * @return whether this changed anything
	 */
	public boolean closeForPlanChange(Instant now) {
		if (status != Status.ACTIVE) {
			return false;
		}
		if (now == null || now.isBefore(startedAt == null ? now : startedAt)) {
			// The new subscription cannot start before the old one did; that would be a clock
			// problem rather than a plan change.
			return false;
		}
		this.status = Status.CANCELLED;
		this.cancelledAt = now;
		return true;
	}

	// ------------------------------------------------------------------ queries

	/**
	 * Whether this subscription grants entitlements right now.
	 *
	 * <p><b>This, not {@code status == ACTIVE}, is the gate every read uses.</b> An ACTIVE
	 * subscription whose window has closed still reads ACTIVE until the sweep runs, and a scheduler
	 * being down or slow must not extend a customer's paid access -- nor, read the other way, must
	 * it cut access off early.
	 */
	public boolean grants(Instant now) {
		return status == Status.ACTIVE
				&& startedAt != null
				&& expiresAt != null
				&& now.isBefore(expiresAt);
	}

	/** True once the paid period has run out, whether or not the sweep has noticed. */
	public boolean hasLapsed(Instant now) {
		return expiresAt != null && !now.isBefore(expiresAt);
	}

	/** True when the shop asked not to renew. Distinct from having lapsed. */
	public boolean willNotRenew() {
		return cancelledAt != null;
	}

	/**
	 * Satisfies the wallet package's {@code SubscriptionLink}, so a top-up can be recorded against
	 * this subscription without the wallet depending on this package.
	 *
	 * <p>Without this the price would have to be passed as three loose arguments, and pairing one
	 * subscription's id with another's plan is a mistake no type system at the call site would catch.
	 */
	@Override
	public java.util.UUID subscriptionId() {
		return id;
	}

	@Override
	public java.util.UUID planId() {
		return plan.getId();
	}

	@Override
	public long priceMinor() {
		return plan.getPriceMinor();
	}

	/** Whole days left, rounded up, floored at zero. For "30 days remaining" in the UI. */
	public long daysRemaining(Instant now) {
		if (expiresAt == null || !now.isBefore(expiresAt)) {
			return 0;
		}
		return (long) Math.ceil(Duration.between(now, expiresAt).toMillis() / 86_400_000.0);
	}
}
