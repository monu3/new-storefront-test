package com.storefront.store.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Money arithmetic.
 *
 * <p>Focused on the properties that keep a ledger reconcilable: sums are exact, distribution
 * loses nothing, and rounding is the same every time (plan risk R1).
 */
class MoneyTest {

	@Nested
	@DisplayName("construction")
	class Construction {

		@Test
		@DisplayName("rejects a negative amount")
		void rejectsNegative() {
			assertThatThrownBy(() -> Money.ofMinor(-1))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("negative");
		}

		@Test
		@DisplayName("rejects a blank currency")
		void rejectsBlankCurrency() {
			assertThatThrownBy(() -> new Money(100, " "))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Currency");
		}

		@Test
		@DisplayName("accepts zero")
		void acceptsZero() {
			assertThat(Money.zero().isZero()).isTrue();
			assertThat(Money.zero().minor()).isZero();
		}
	}

	@Nested
	@DisplayName("parsing rupees")
	class Parsing {

		@ParameterizedTest
		@CsvSource({
				"0, 0",
				"1, 100",
				"1250.50, 125050",
				"1250.5, 125050",
				"0.01, 1",
				"999999.99, 99999999",
		})
		@DisplayName("converts rupees to paisa exactly")
		void parsesRupees(String rupees, long expectedMinor) {
			assertThat(Money.parseRupees(rupees).minor()).isEqualTo(expectedMinor);
		}

		@Test
		@DisplayName("rejects more than two decimal places")
		void rejectsSubPaisa() {
			// A price cannot be more precise than one paisa, so this must fail loudly rather
			// than silently round someone's money.
			assertThatThrownBy(() -> Money.parseRupees("10.005"))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@ParameterizedTest
		@ValueSource(strings = { "", "   ", "abc", "10.5.5", "1,000" })
		@DisplayName("rejects malformed input")
		void rejectsMalformed(String rupees) {
			assertThatThrownBy(() -> Money.parseRupees(rupees))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("rejects a negative amount")
		void rejectsNegativeAmount() {
			assertThatThrownBy(() -> Money.parseRupees("-5"))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("basis points")
	class BasisPoints {

		@ParameterizedTest
		@CsvSource({
				"10000, 1000, 1000",     // 100% of 1000
				"5000, 1000, 500",       // 50% of 1000
				"1000, 1000, 100",       // 10% of 1000
				"500, 100000, 5000",     // 5% of 100,000
				"0, 1000, 0",            // a zero rate is zero
		})
		@DisplayName("applies a rate")
		void appliesRate(long basisPoints, long amountMinor, long expected) {
			assertThat(Money.ofMinor(amountMinor).applyBasisPoints((int) basisPoints).minor())
					.isEqualTo(expected);
		}

		@Test
		@DisplayName("rounds half up, consistently")
		void roundsHalfUp() {
			// 5% of 99 paisa is 4.95, which rounds to 5. A different mode would give 4, and the
			// difference would accumulate invisibly across thousands of orders.
			assertThat(Money.ofMinor(99).applyBasisPoints(500).minor()).isEqualTo(5);
			assertThat(Money.ofMinor(101).applyBasisPoints(500).minor()).isEqualTo(5);
			assertThat(Money.ofMinor(99).applyBasisPoints(500, RoundingMode.FLOOR).minor()).isEqualTo(4);
		}

		@Test
		@DisplayName("is idempotent for a zero rate")
		void zeroRateIsZero() {
			assertThat(Money.ofMinor(12345).applyBasisPoints(0).isZero()).isTrue();
		}

		@Test
		@DisplayName("rejects a negative rate")
		void rejectsNegativeRate() {
			assertThatThrownBy(() -> Money.ofMinor(100).applyBasisPoints(-1))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("arithmetic")
	class Arithmetic {

		@Test
		@DisplayName("adds and subtracts")
		void addSubtract() {
			Money a = Money.ofMinor(1000);
			Money b = Money.ofMinor(250);
			assertThat(a.plus(b).minor()).isEqualTo(1250);
			assertThat(a.minus(b).minor()).isEqualTo(750);
		}

		@Test
		@DisplayName("refuses to produce a negative result")
		void refusesNegativeResult() {
			assertThatThrownBy(() -> Money.ofMinor(100).minus(Money.ofMinor(200)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("negative");
		}

		@Test
		@DisplayName("refuses to mix currencies")
		void refusesMixedCurrencies() {
			assertThatThrownBy(() -> Money.ofMinor(100, "NPR").plus(Money.ofMinor(100, "USD")))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Currency mismatch");
		}

		@Test
		@DisplayName("multiplies by a whole quantity")
		void multiplies() {
			assertThat(Money.ofMinor(1999).times(3).minor()).isEqualTo(5997);
		}

		@Test
		@DisplayName("orders by amount")
		void orders() {
			assertThat(Money.ofMinor(100)).isLessThan(Money.ofMinor(200));
			assertThat(Money.ofMinor(200)).isGreaterThan(Money.ofMinor(100));
			assertThat(Money.ofMinor(200)).isEqualByComparingTo(Money.ofMinor(200));
		}
	}

	@Nested
	@DisplayName("distribution")
	class Distribution {

		@Test
		@DisplayName("shares sum exactly to the total")
		void sumsExactly() {
			// 100 across 3 equal weights is 33.33..., so two shares get an extra paisa. This
			// is the property that keeps order line items adding up to the order total.
			long[] shares = Money.ofMinor(100).distribute(new long[] { 1, 1, 1 });
			assertThat(shares).containsExactly(34, 33, 33);
			assertThat(java.util.Arrays.stream(shares).sum()).isEqualTo(100);
		}

		@Test
		@DisplayName("respects unequal weights")
		void respectsWeights() {
			long[] shares = Money.ofMinor(1000).distribute(new long[] { 3, 1 });
			assertThat(shares).containsExactly(750, 250);
		}

		@Test
		@DisplayName("never loses a paisa across many random splits")
		void neverLosesAPaisa() {
			// Property test: for any total and any weights, the shares must sum to the total.
			// A single lost paisa is a reconciliation bug that surfaces months later.
			java.util.Random random = new java.util.Random(42);
			for (int trial = 0; trial < 1000; trial++) {
				int count = 1 + random.nextInt(8);
				long[] weights = new long[count];
				long totalWeight = 0;
				for (int i = 0; i < count; i++) {
					weights[i] = 1 + random.nextInt(100);
					totalWeight += weights[i];
				}
				long total = random.nextInt(1_000_000);
				long[] shares = Money.ofMinor(total).distribute(weights);
				long sum = 0;
				for (long share : shares) {
					assertThat(share).isGreaterThanOrEqualTo(0);
					sum += share;
				}
				assertThat(sum)
						.as("shares must always sum to the total (total=%d weights=%s)", total,
								java.util.Arrays.toString(weights))
						.isEqualTo(total);
			}
		}

		@Test
		@DisplayName("handles an empty weight set")
		void handlesEmpty() {
			assertThat(Money.ofMinor(100).distribute(new long[0])).isEmpty();
		}

		@Test
		@DisplayName("handles all-zero weights without dividing by zero")
		void handlesZeroWeights() {
			assertThat(Money.ofMinor(100).distribute(new long[] { 0, 0 })).containsExactly(0, 0);
		}

		@Test
		@DisplayName("rejects a negative weight")
		void rejectsNegativeWeight() {
			assertThatThrownBy(() -> Money.ofMinor(100).distribute(new long[] { 1, -1 }))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("proportions")
	class Proportions {

		@Test
		@DisplayName("computes a share")
		void computesShare() {
			assertThat(Money.ofMinor(1000).proportionOf(1, 4).minor()).isEqualTo(250);
		}

		@Test
		@DisplayName("rejects a zero denominator")
		void rejectsZeroDenominator() {
			assertThatThrownBy(() -> Money.ofMinor(1000).proportionOf(1, 0))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("display")
	class Display {

		@Test
		@DisplayName("formats paisa as a rupee amount")
		void formats() {
			assertThat(Money.ofMinor(125050).toDisplay()).isEqualTo("Rs. 1,250.50");
			assertThat(Money.ofMinor(0).toDisplay()).isEqualTo("Rs. 0.00");
			assertThat(Money.ofMinor(1).toDisplay()).isEqualTo("Rs. 0.01");
		}

		@Test
		@DisplayName("avoids float drift in the display path")
		void avoidsFloatDrift() {
			// 0.1 + 0.2 in float is 0.30000000000000004. Rounding to 2 places through
			// BigDecimal keeps it at 0.30.
			Money total = Money.ofMinor(10).plus(Money.ofMinor(20));
			assertThat(total.minor()).isEqualTo(30);
			assertThat(total.toDisplay()).isEqualTo("Rs. 0.30");
		}
	}
}
