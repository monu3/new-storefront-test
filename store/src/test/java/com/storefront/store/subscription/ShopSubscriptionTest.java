package com.storefront.store.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The subscription state machine, on its own.
 *
 * <p>Pure unit tests. The properties worth protecting here are the ones a reader of the entity
 * would assume rather than check:
 *
 * <ul>
 *   <li><b>cancelling does not revoke anything before the period ends</b> — the shop paid for it;</li>
 *   <li><b>a lapsed subscription grants nothing even before the sweep runs</b> — enforcement is on
 *       read, not on a scheduler;</li>
 *   <li><b>EXPIRED and CANCELLED mean different things</b> — churn is not lapse.</li>
 * </ul>
 */
@DisplayName("ShopSubscription")
class ShopSubscriptionTest {

	private static final UUID SHOP = UUID.randomUUID();

	private static SubscriptionPlan plan(int days) {
		return new SubscriptionPlan("PRO_MONTHLY", "Pro", SubscriptionPlan.BillingPeriod.MONTHLY,
				200_000, days);
	}

	/** A pending request, as a shop would create it. */
	private static ShopSubscription pending() {
		return new ShopSubscription(SHOP, plan(31));
	}

	// ------------------------------------------------------------------ activation

	@Nested
	@DisplayName("activating")
	class Activating {

		@Test
		@DisplayName("a pending subscription starts and sets its end date")
		void activates() {
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();

			subscription.activate(start, UUID.randomUUID());

			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.ACTIVE);
			assertThat(subscription.getStartedAt()).isEqualTo(start);
			assertThat(subscription.getExpiresAt()).isEqualTo(start.plus(31, ChronoUnit.DAYS));
			assertThat(subscription.grants(start)).isTrue();
		}

		@Test
		@DisplayName("it refuses to start twice")
		void cannotActivateTwice() {
			// Re-activating would extend a period that has already been paid for, which is how paid
			// time gets invented for free.
			ShopSubscription subscription = pending();
			subscription.activate(Instant.now(), UUID.randomUUID());

			assertThatThrownBy(() -> subscription.activate(Instant.now(), UUID.randomUUID()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Only a pending subscription");
		}

		@Test
		@DisplayName("the free plan cannot be a subscription")
		void freeIsNotASubscription() {
			// Materialising FREE as an ACTIVE row would put one on every shop in the marketplace,
			// and the one-active-per-shop index would then stop any of them subscribing.
			ShopSubscription free = new ShopSubscription(SHOP,
					new SubscriptionPlan("FREE", "Free",
							SubscriptionPlan.BillingPeriod.NONE, 0, null));

			assertThatThrownBy(() -> free.activate(Instant.now(), UUID.randomUUID()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("absence of a subscription");
		}
	}

	// ------------------------------------------------------------------ cancelling

	@Nested
	@DisplayName("cancelling")
	class Cancelling {

		@Test
		@DisplayName("keeps granting until the paid period ends")
		void cancellationDefers() {
			// The single most important property in this file. A shop that paid for 31 days and
			// cancels on day 3 has bought 28 more days of commission-free trading. Revoking on the
			// spot would make cancellation a silent refund, and would make "try it for a month" a
			// trap.
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());

			subscription.cancel();

			Instant threeDaysIn = start.plus(3, ChronoUnit.DAYS);
			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.ACTIVE);
			assertThat(subscription.grants(threeDaysIn)).isTrue();
			assertThat(subscription.willNotRenew()).isTrue();
			assertThat(subscription.getExpiresAt()).isEqualTo(start.plus(31, ChronoUnit.DAYS));
		}

		@Test
		@DisplayName("and then the sweep records it as CANCELLED, not EXPIRED")
		void becomesCancelledNotExpired() {
			// Churn and lapse are different business questions. Collapsing them answers "how many
			// shops left" with "how many subscriptions ran out", which are not the same number.
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());
			subscription.cancel();

			assertThat(subscription.expire(start.plus(32, ChronoUnit.DAYS))).isTrue();

			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.CANCELLED);
		}

		@Test
		@DisplayName("a pending request is cancelled outright")
		void pendingIsCancelledImmediately() {
			// Nothing was paid for, so there is no paid-through period to honour.
			ShopSubscription subscription = pending();

			subscription.cancel();

			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.CANCELLED);
		}

		@Test
		@DisplayName("cancelling something already closed changes nothing")
		void cancelIsIdempotent() {
			// Otherwise a double-click would move cancelled_at on a dead subscription, which then
			// reads as a recent churn event in a report.
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());
			subscription.expire(start.plus(40, ChronoUnit.DAYS));

			subscription.cancel();

			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.EXPIRED);
			assertThat(subscription.getCancelledAt()).isNull();
		}
	}

	// ------------------------------------------------------------------ lapse

	@Nested
	@DisplayName("expiring")
	class Expiring {

		@Test
		@DisplayName("an un-cancelled subscription becomes EXPIRED")
		void expires() {
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());

			assertThat(subscription.expire(start.plus(32, ChronoUnit.DAYS))).isTrue();
			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.EXPIRED);
		}

		@Test
		@DisplayName("it refuses to close one that has not lapsed")
		void refusesEarly() {
			// The sweep selects on expires_at < now, so a clock skew or a bad expires_at must not be
			// able to revoke a period the shop has already paid for.
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());

			assertThat(subscription.expire(start.plus(5, ChronoUnit.DAYS))).isFalse();
			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.ACTIVE);
			assertThat(subscription.grants(start.plus(5, ChronoUnit.DAYS))).isTrue();
		}

		@Test
		@DisplayName("expiring twice does nothing the second time")
		void idempotent() {
			// The scheduler retries. A sweep that was not idempotent could damage a shop on retry.
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());
			Instant after = start.plus(32, ChronoUnit.DAYS);

			assertThat(subscription.expire(after)).isTrue();
			assertThat(subscription.expire(after)).isFalse();
		}

		@Test
		@DisplayName("a pending subscription is never 'expired' by the sweep")
		void pendingIsNotExpired() {
			ShopSubscription subscription = pending();

			assertThat(subscription.expire(Instant.now().plus(3650, ChronoUnit.DAYS))).isFalse();
			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.PENDING_PAYMENT);
		}
	}

	// ------------------------------------------------------------------ grants

	@Nested
	@DisplayName("what it grants")
	class Grants {

		@Test
		@DisplayName("a pending request grants nothing")
		void pendingGrantsNothing() {
			assertThat(pending().grants(Instant.now())).isFalse();
		}

		@Test
		@DisplayName("an active one grants until its window closes")
		void activeGrants() {
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());

			assertThat(subscription.grants(start.plus(30, ChronoUnit.DAYS))).isTrue();
		}

		@Test
		@DisplayName("it stops granting the instant the window closes, sweep or no sweep")
		void stopsAtExpiryWithoutTheSweep() {
			// THE property. If this were the sweep's job rather than the read's, a stopped scheduler
			// would silently extend every customer's paid access, and how long they kept working
			// would depend on a cron that nobody is watching.
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());

			// Status is still ACTIVE -- no sweep has run, and the entity has not been touched.
			Instant afterExpiry = start.plus(31, ChronoUnit.DAYS).plusSeconds(1);

			assertThat(subscription.getStatus()).isEqualTo(ShopSubscription.Status.ACTIVE);
			assertThat(subscription.grants(afterExpiry)).isFalse();
			assertThat(subscription.hasLapsed(afterExpiry)).isTrue();
		}

		@Test
		@DisplayName("it reports whole days left, rounded up")
		void daysRemaining() {
			Instant start = Instant.parse("2026-03-01T00:00:00Z");
			ShopSubscription subscription = pending();
			subscription.activate(start, UUID.randomUUID());

			// 30 days and 1 hour left is 31 whole days of access a customer can count on, so it is
			// reported as 31 rather than 30. Flooring would understate it and read as "expires today".
			assertThat(subscription.daysRemaining(start.plus(1, ChronoUnit.DAYS).plusSeconds(3600)))
					.isEqualTo(30L);
			assertThat(subscription.daysRemaining(start.plus(30, ChronoUnit.DAYS).plusSeconds(1)))
					.isEqualTo(1L);
			assertThat(subscription.daysRemaining(start.plus(400, ChronoUnit.DAYS))).isZero();
		}
	}

	// ------------------------------------------------------------------ validation

	@Test
	@DisplayName("a subscription needs a shop and a plan")
	void requiredFields() {
		assertThatThrownBy(() -> new ShopSubscription(null, plan(30)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShopSubscription(SHOP, null))
				.isInstanceOf(NullPointerException.class);
	}
}
