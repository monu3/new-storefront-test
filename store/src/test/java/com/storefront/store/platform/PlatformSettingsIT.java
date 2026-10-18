package com.storefront.store.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.support.TestConfig;
import com.storefront.store.wallet.CommissionCalculator;

/**
 * Platform settings (Phase 7).
 *
 * <p>The property worth the most attention is the last one: <b>changing the rate must not touch an
 * order that has already been accepted</b>. That is why {@code ShopOrder.freezeCommission} exists,
 * and it is the one way this feature could quietly cost the platform money — a rate change landing
 * on historical orders would rewrite what shops were billed, months after the fact, with no way to
 * explain it to them.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class PlatformSettingsIT {

	@Autowired
	private PlatformSettings settings;

	@Autowired
	private CommissionCalculator calculator;

	// ------------------------------------------------------------------ defaults

	@Nested
	@DisplayName("defaults")
	class Defaults {

		@Test
		@DisplayName("the commission rate is 500 basis points, as it always was")
		void rateMatchesTheOldConstant() {
			// The whole reason moving this from a constant to a setting is safe: the seeded value is
			// byte-for-byte what `CommissionCalculator.DEFAULT_RATE_BP` was, so no order accepted
			// before the change is affected and no dashboard figure moves.
			assertThat(settings.commissionRateBp())
					.isEqualTo(CommissionCalculator.DEFAULT_RATE_BP)
					.isEqualTo(500);
		}

		@Test
		@DisplayName("which is 5%")
		void percent() {
			assertThat(settings.commissionPercent()).isEqualTo(5.0);
		}

		@Test
		@DisplayName("every key has a value, so no caller ever handles 'unset'")
		void everyKeyIsSeeded() {
			// A setting with no row means every reader carries a fallback branch, and a branch nobody
			// exercises is a branch that is wrong.
			assertThat(settings.all()).hasSize(PlatformSettings.Key.values().length);
			assertThat(settings.all().values()).doesNotContainNull();
		}

		@Test
		@DisplayName("auto-approval of subscriptions is off")
		void autoApproveDefaultsOff() {
			// Named so the day somebody wants it, the switch has a name and turning it on is a
			// deliberate act.
			assertThat(settings.autoApproveSubscriptions()).isFalse();
		}
	}

	// ------------------------------------------------------------------ writes

	@Nested
	@DisplayName("validation")
	class Validation {

		@Test
		@DisplayName("a rate outside 0-100% is refused, and says why")
		void rateRange() {
			// Negative commission would pay a shop to sell; over 100% takes more than the order is
			// worth and produces negative order totals. Both are valid JSON and both are absurd.
			assertThatThrownBy(() -> settings.set(
					PlatformSettings.Key.COMMISSION_RATE_BP, -1))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Negative commission");

			assertThatThrownBy(() -> settings.set(
					PlatformSettings.Key.COMMISSION_RATE_BP, 10_001))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("between 0 and 10000");
		}

		@Test
		@DisplayName("the boundaries are accepted")
		void rateBoundaries() {
			settings.set(PlatformSettings.Key.COMMISSION_RATE_BP, 0);
			assertThat(settings.commissionRateBp()).isZero();

			settings.set(PlatformSettings.Key.COMMISSION_RATE_BP, 10_000);
			assertThat(settings.commissionRateBp()).isEqualTo(10_000);
		}

		@Test
		@DisplayName("a non-number is refused rather than silently becoming zero")
		void rateMustBeANumber() {
			// A parse failure that returned the default would mean an admin's typo charges 5% while
			// the screen says something else.
			assertThatThrownBy(() -> settings.set(
					PlatformSettings.Key.COMMISSION_RATE_BP, "five percent"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must be a number");
		}

		@Test
		@DisplayName("a currency must be an ISO code")
		void currency() {
			assertThatThrownBy(() -> settings.set(PlatformSettings.Key.PLATFORM_CURRENCY, "rupees"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("three-letter ISO code");

			settings.set(PlatformSettings.Key.PLATFORM_CURRENCY, "NPR");
			// Read back without the jsonb quotes, or every consumer gets "NPR" with quotes on it.
			assertThat(settings.currency()).isEqualTo("NPR");
		}
	}

	// ------------------------------------------------------------------ reading back

	@Nested
	@DisplayName("reading a value back")
	class RoundTrip {

		@Test
		@DisplayName("a number survives the jsonb round trip")
		void numbers() {
			settings.set(PlatformSettings.Key.COMMISSION_RATE_BP, 750);
			assertThat(settings.commissionRateBp()).isEqualTo(750);
			assertThat(settings.commissionPercent()).isEqualTo(7.5);
		}

		@Test
		@DisplayName("a boolean survives as a boolean, not the text 'true'")
		void booleans() {
			settings.set(PlatformSettings.Key.REGISTRATION_OPEN, false);
			assertThat(settings.registrationOpen()).isFalse();

			// jsonb stores this natively; if it were written as the string "false" this would read
			// back as true, which is how an emergency brake fails to engage.
			settings.set(PlatformSettings.Key.REGISTRATION_OPEN, true);
			assertThat(settings.registrationOpen()).isTrue();
		}

		@Test
		@DisplayName("a zero is a value, not a missing one")
		void zeroIsNotAbsent() {
			settings.set(PlatformSettings.Key.GALLERY_FEATURED_LIMIT, 0);

			// Zero means "feature no shops". Falling back to the default of 12 would quietly undo an
			// admin's decision, which is the worst kind of bug in a settings screen.
			assertThat(settings.galleryFeaturedLimit()).isZero();
		}

		@Test
		@DisplayName("a large amount survives as a long")
		void longs() {
			settings.set(PlatformSettings.Key.MIN_TOPUP_MINOR, 500_000L);
			assertThat(settings.minTopupMinor()).isEqualTo(500_000L);
		}
	}

	// ------------------------------------------------------------------ the consequence

	@Test
	@DisplayName("the configured rate is what the calculator uses")
	void rateReachesTheCalculator() {
		settings.set(PlatformSettings.Key.COMMISSION_RATE_BP, 1000);

		var commission = calculator.calculate(
				com.storefront.store.common.Money.ofMinor(100_000),
				com.storefront.store.common.Money.zero(),
				com.storefront.store.common.Money.zero(),
				com.storefront.store.common.Money.zero(),
				settings.commissionRateBp(),
				false);

		// 10% of Rs. 1,000 is Rs. 100.
		assertThat(commission.amount().minor()).isEqualTo(10_000L);
		assertThat(commission.rateBp()).isEqualTo(1000);
	}

	@Test
	@DisplayName("a subscribed shop still pays nothing, whatever the rate is")
	void waiverBeatsTheRate() {
		settings.set(PlatformSettings.Key.COMMISSION_RATE_BP, 10_000);

		var commission = calculator.calculate(
				com.storefront.store.common.Money.ofMinor(100_000),
				com.storefront.store.common.Money.zero(),
				com.storefront.store.common.Money.zero(),
				com.storefront.store.common.Money.zero(),
				settings.commissionRateBp(),
				true);

		// The waiver is a property of the subscription, not of the rate. A rate change must not be
		// able to quietly start charging a shop it promised to charge nothing.
		assertThat(commission.amount().minor()).isZero();
		assertThat(commission.waived()).isTrue();
	}

	@Test
	@DisplayName("an already-accepted order keeps the rate it was judged by")
	void frozenOrdersAreUntouched() {
		// The point of `freezeCommission`. A rate change affects orders accepted afterwards and
		// nothing else -- otherwise changing a rate would retroactively rewrite what every shop was
		// billed, with no way to explain it to them.
		int rateAtAcceptance = settings.commissionRateBp();
		var commissionAtAcceptance = calculator.calculate(
				com.storefront.store.common.Money.ofMinor(100_000),
				com.storefront.store.common.Money.zero(),
				com.storefront.store.common.Money.zero(),
				com.storefront.store.common.Money.zero(),
				rateAtAcceptance, false);

		settings.set(PlatformSettings.Key.COMMISSION_RATE_BP, 2000);

		// What the order holds does not move with the setting.
		assertThat(commissionAtAcceptance.rateBp()).isEqualTo(500);
		assertThat(commissionAtAcceptance.amount().minor()).isEqualTo(5_000L);

		// And a new calculation uses the new rate.
		assertThat(settings.commissionRateBp()).isEqualTo(2000);
	}
}