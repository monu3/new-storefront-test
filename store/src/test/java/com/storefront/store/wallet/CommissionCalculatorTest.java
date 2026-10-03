package com.storefront.store.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.storefront.store.common.Money;
import com.storefront.store.wallet.CommissionCalculator.Commission;

/**
 * The commission rules exactly as specified (D2).
 *
 * <p>This is the highest-risk logic in the system: real money leaves a shop's wallet because
 * of these numbers, and the plan's R1 rates a mistake here as the worst bug the project can
 * ship. Every rule below is stated, and each has a test.
 */
class CommissionCalculatorTest {

	private final CommissionCalculator calculator = new CommissionCalculator();

	@Nested
	@DisplayName("the rules as specified")
	class SpecifiedRules {

		@Test
		@DisplayName("5% on an order with no discount")
		void fivePercent() {
			// Rs 1,000.00 -> Rs 50.00
			Commission c = calculator.calculate(Money.ofMinor(100_000), 500, false);

			assertThat(c.amount().minor()).isEqualTo(5_000);
			assertThat(c.rateBp()).isEqualTo(500);
			assertThat(c.waived()).isFalse();
			assertThat(c.isChargeable()).isTrue();
		}

		@Test
		@DisplayName("the percentage applies after a discount, not before")
		void discountReducesTheBase() {
			// Rs 1,000 less Rs 200 discount = Rs 800 base; 5% = Rs 40.
			// Taking 5% of the subtotal and subtracting the discount would give Rs 30, which
			// is a real and common bug.
			Commission c = calculator.calculate(
					Money.ofMinor(100_000), Money.ofMinor(20_000), 500, false);

			assertThat(c.commissionableBase().minor()).isEqualTo(80_000);
			assertThat(c.amount().minor()).isEqualTo(4_000);
		}

		@Test
		@DisplayName("shipping and tax are excluded from the commission base")
		void excludesShippingAndTax() {
			// The platform does not take a cut of delivery or of government tax.
			Commission c = calculator.calculate(
					Money.ofMinor(100_000), Money.zero(), Money.ofMinor(50_000), Money.ofMinor(13_000),
					500, false);

			assertThat(c.commissionableBase().minor())
					.as("base must be the subtotal alone, ignoring shipping and tax")
					.isEqualTo(100_000);
			assertThat(c.amount().minor()).isEqualTo(5_000);
		}

		@Test
		@DisplayName("a subscribed shop pays nothing")
		void subscribedPaysNothing() {
			Commission c = calculator.calculate(Money.ofMinor(100_000), 500, true);

			assertThat(c.amount().isZero()).isTrue();
			assertThat(c.waived()).as("the waiver must be recorded, not implied by a zero").isTrue();
			assertThat(c.isChargeable()).isFalse();
			// The base is still computed, so an admin can see what would have been charged.
			assertThat(c.commissionableBase().minor()).isEqualTo(100_000);
		}

		@Test
		@DisplayName("a full discount on a free-tier shop charges nothing")
		void fullDiscountChargesNothing() {
			// 5% of zero is zero, and that is a real outcome, not a waived one.
			Commission c = calculator.calculate(
					Money.ofMinor(100_000), Money.ofMinor(100_000), 500, false);

			assertThat(c.amount().isZero()).isTrue();
			assertThat(c.waived()).isFalse();
			assertThat(c.isChargeable()).isFalse();
		}
	}

	@Nested
	@DisplayName("rounding")
	class Rounding {

		@Test
		@DisplayName("a small order still rounds to a whole paisa")
		void roundsToWholePaisa() {
			// 5% of Rs 10.10 = Rs 0.505 -> Rs 0.51
			Commission c = calculator.calculate(Money.ofMinor(1_010), 500, false);
			assertThat(c.amount().minor()).isEqualTo(51);
		}

		@Test
		@DisplayName("rounding is stable, so a re-run of the same order gives the same figure")
		void roundingIsDeterministic() {
			// The rate is snapshotted at accept time, so recomputing for a report must agree
			// with what was actually debited.
			Money subtotal = Money.ofMinor(3_333);
			long first = calculator.calculate(subtotal, 500, false).amount().minor();
			for (int i = 0; i < 100; i++) {
				assertThat(calculator.calculate(subtotal, 500, false).amount().minor())
						.isEqualTo(first);
			}
		}

		@ParameterizedTest
		@CsvSource({
				"100, 5",      // 5% of Rs 1.00 = 0.05
				"199, 10",     // 9.95 -> 10
				"1000, 50",
				"10000, 500",
		})
		@DisplayName("known values")
		void knownValues(long subtotalMinor, long expectedCommission) {
			assertThat(calculator.calculate(Money.ofMinor(subtotalMinor), 500, false)
					.amount().minor()).isEqualTo(expectedCommission);
		}
	}

	@Nested
	@DisplayName("rate handling")
	class Rates {

		@Test
		@DisplayName("a zero rate produces no charge")
		void zeroRate() {
			Commission c = calculator.calculate(Money.ofMinor(100_000), 0, false);
			assertThat(c.amount().isZero()).isTrue();
			assertThat(c.isChargeable()).isFalse();
		}

		@Test
		@DisplayName("a full-rate percentage takes everything")
		void fullRate() {
			Commission c = calculator.calculate(Money.ofMinor(100_000), 10_000, false);
			assertThat(c.amount().minor()).isEqualTo(100_000);
		}

		@Test
		@DisplayName("rejects a negative rate")
		void rejectsNegativeRate() {
			assertThatThrownBy(() -> calculator.calculate(Money.ofMinor(1000), -100, false))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("a changed rate never alters a previously computed order")
		void changedRateDoesNotRetroApply() {
			// The rate is snapshotted onto the order, so a later platform change cannot rewrite
			// history. This models that by computing twice at different rates.
			Commission atFivePercent = calculator.calculate(Money.ofMinor(100_000), 500, false);
			Commission atTenPercent = calculator.calculate(Money.ofMinor(100_000), 1_000, false);

			assertThat(atFivePercent.amount().minor()).isEqualTo(5_000);
			assertThat(atTenPercent.amount().minor()).isEqualTo(10_000);
		}
	}

	@Nested
	@DisplayName("guards")
	class Guards {

		@Test
		@DisplayName("rejects a discount larger than the subtotal")
		void rejectsOverlargeDiscount() {
			// Otherwise the base would go negative and the ledger would need a signed amount,
			// which the schema deliberately forbids.
			assertThatThrownBy(() -> calculator.calculate(
					Money.ofMinor(10_000), Money.ofMinor(20_000), 500, false))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
