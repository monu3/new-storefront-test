package com.storefront.store.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules a subscription plan has, on its own.
 *
 * <p>Pure unit tests, no Spring and no database. Everything here is a rule that either makes money
 * or takes it away, and each is stated in the entity's own Javadoc — so a rule that lives only in
 * this test is a rule that can be changed without anyone noticing.
 */
@DisplayName("SubscriptionPlan")
class SubscriptionPlanTest {

	private static SubscriptionPlan monthly(long priceMinor, int days) {
		return new SubscriptionPlan("PRO_MONTHLY", "Pro", SubscriptionPlan.BillingPeriod.MONTHLY,
				priceMinor, days);
	}

	// ------------------------------------------------------------------ construction

	@Nested
	@DisplayName("validating its own terms")
	class Validation {

		@Test
		@DisplayName("a paid plan must say how long it lasts")
		void paidNeedsDuration() {
			// Without a duration there is no expires_at, so the expiry sweep has nothing to find and
			// a subscriber's commission waiver would last forever.
			assertThatThrownBy(() -> new SubscriptionPlan("PRO_X", "Pro",
					SubscriptionPlan.BillingPeriod.MONTHLY, 1000, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("how many days");
		}

		@Test
		@DisplayName("a paid plan must not have a zero or negative duration")
		void paidNeedsPositiveDuration() {
			assertThatThrownBy(() -> monthly(1000, 0))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> monthly(1000, -5))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("a free plan cannot have a price")
		void freeIsFree() {
			// MRR sums active plan prices. A priced free plan would be reported as revenue that was
			// never collected, and no one reviewing a dashboard would think to check it.
			assertThatThrownBy(() -> new SubscriptionPlan("FREE", "Free",
					SubscriptionPlan.BillingPeriod.NONE, 1000, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot have a price");
		}

		@Test
		@DisplayName("a negative price is refused")
		void noNegativePrice() {
			assertThatThrownBy(() -> monthly(-1, 30))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("negative");
		}

		@Test
		@DisplayName("the code is upper-cased and must be a safe token")
		void codeIsNormalised() {
			// Seed data and tests refer to the code, so it must not vary in case between the
			// migration's INSERT and an admin's CREATE.
			assertThat(new SubscriptionPlan("pro_annual", "Pro",
					SubscriptionPlan.BillingPeriod.ANNUAL, 100, 30).getCode())
					.isEqualTo("PRO_ANNUAL");
		}

		@Test
		@DisplayName("a code with a space or a quote in it is refused")
		void codeIsSafe() {
			assertThatThrownBy(() -> new SubscriptionPlan("PRO MONTHLY", "P",
					SubscriptionPlan.BillingPeriod.MONTHLY, 1, 30))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("A-Z");
			assertThatThrownBy(() -> new SubscriptionPlan("PRO'; DROP TABLE shop;--", "P",
					SubscriptionPlan.BillingPeriod.MONTHLY, 1, 30))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("a blank name is refused")
		void nameRequired() {
			assertThatThrownBy(() -> new SubscriptionPlan("PRO_X", "   ",
					SubscriptionPlan.BillingPeriod.MONTHLY, 1, 30))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// ------------------------------------------------------------------ MRR

	@Nested
	@DisplayName("monthly equivalent")
	class MonthlyEquivalent {

		@Test
		@DisplayName("a monthly plan contributes its full price")
		void monthly() {
			assertThat(SubscriptionPlanTest.monthly(200_000, 31).monthlyEquivalentMinor())
					.isEqualTo(200_000L);
		}

		@Test
		@DisplayName("a free plan contributes nothing")
		void free() {
			assertThat(new SubscriptionPlan("FREE", "Free",
					SubscriptionPlan.BillingPeriod.NONE, 0, null).monthlyEquivalentMinor())
					.isZero();
		}

		@Test
		@DisplayName("an annual plan contributes a twelfth, not the whole price")
		void annualIsDivided() {
			// The whole point of MRR being a monthly figure. Counting an annual plan's full price as
			// one month reports a twelvefold jump every year, and a dashboard that does that is a
			// dashboard nobody trusts by the second January.
			assertThat(new SubscriptionPlan("PRO_ANNUAL", "Pro",
					SubscriptionPlan.BillingPeriod.ANNUAL, 2_400_000, 366)
					.monthlyEquivalentMinor())
					.isEqualTo(200_000L);
		}

		@Test
		@DisplayName("a year that does not divide by twelve is floored, not rounded")
		void annualFloorsRemainder() {
			// Flooring understates MRW by a fraction on every annual subscriber; the sum of those
			// fractions is a real number, and rounding up would overstate it instead.
			assertThat(new SubscriptionPlan("PRO_ANNUAL", "Pro",
					SubscriptionPlan.BillingPeriod.ANNUAL, 1_000, 366)
					.monthlyEquivalentMinor())
					.isEqualTo(83L);
		}
	}

	// ------------------------------------------------------------------ expiry

	@Nested
	@DisplayName("computing its expiry")
	class Expiry {

		@Test
		@DisplayName("a subscription lasts exactly its duration")
		void lastsDuration() {
			Instant start = Instant.parse("2026-01-01T00:00:00Z");
			var plan = monthly(100_000, 31);

			assertThat(plan.expiresAtFrom(start)).isEqualTo(start.plus(31, ChronoUnit.DAYS));
		}

		@Test
		@DisplayName("the end is computed from the given start, not from now")
		void measuredFromGivenStart() {
			// A renewal preview must be quotable for a start that has not happened yet. Reading the
			// clock here would make a preview silently wrong by however long the preview took to run.
			Instant start = Instant.parse("2027-06-01T00:00:00Z");

			assertThat(monthly(1, 30).expiresAtFrom(start).toString())
					.isEqualTo("2027-07-01T00:00:00Z");
		}

		@Test
		@DisplayName("the free plan has no expiry, and says so")
		void freeNeverExpires() {
			var free = new SubscriptionPlan("FREE", "Free",
					SubscriptionPlan.BillingPeriod.NONE, 0, null);

			assertThatThrownBy(() -> free.expiresAtFrom(Instant.now()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("never expires");
		}
	}

	// ------------------------------------------------------------------ display

	@Test
	@DisplayName("the price display states the period, and the free plan states nothing")
	void priceDisplay() {
		assertThat(monthly(200_000, 31).priceDisplay()).endsWith("/ month");
		assertThat(new SubscriptionPlan("PRO_ANNUAL", "Pro",
				SubscriptionPlan.BillingPeriod.ANNUAL, 2_000_000, 366).priceDisplay())
				.endsWith("/ year");
		assertThat(new SubscriptionPlan("FREE", "Free",
				SubscriptionPlan.BillingPeriod.NONE, 0, null).priceDisplay())
				.doesNotContain("/");
	}

	@Test
	@DisplayName("a paid plan says it is paid and a free one does not")
	void isPaid() {
		assertThat(monthly(1, 30).isPaid()).isTrue();
		assertThat(new SubscriptionPlan("FREE", "Free",
				SubscriptionPlan.BillingPeriod.NONE, 0, null).isPaid()).isFalse();
	}
}
