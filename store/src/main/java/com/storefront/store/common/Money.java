package com.storefront.store.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of money in minor units (paisa).
 *
 * <p>Every monetary value in the system is a {@code long} of paisa. Never a float, never a
 * {@code BigDecimal} in storage. A rupee amount of 1250.50 is {@code 125050}. Float arithmetic
 * in a ledger is how rounding bugs enter and then refuse to leave: 0.1 + 0.2 is famously not
 * 0.3, and a commission ledger that is off by a paisa on a tenth of orders is a reconciliation
 * problem forever.
 *
 * <p>{@link BigDecimal} is still used, but only for <em>computing</em> percentages, where a
 * division is genuinely needed. It is always converted back to paisa immediately, by an explicit
 * {@link RoundingMode}, so the rounding decision is visible in the code rather than implied by
 * the storage type.
 *
 * <p>Immutable.
 */
public record Money(long amountMinor, String currency) implements Comparable<Money> {

	/** Nepal uses the rupee, subdivided into 100 paisa. */
	public static final String NPR = "NPR";

	/** Basis points: 10,000 bp = 100%. */
	private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000);

	private static final int PAISA_PER_RUPEE = 100;

	public Money {
		if (amountMinor < 0) {
			throw new IllegalArgumentException("Money amount cannot be negative: " + amountMinor);
		}
		if (currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("Currency is required");
		}
	}

	public static Money ofMinor(long amountMinor) {
		return new Money(amountMinor, NPR);
	}

	public static Money ofMinor(long amountMinor, String currency) {
		return new Money(amountMinor, currency);
	}

	public static Money zero() {
		return new Money(0, NPR);
	}

	/** Parses a rupee string such as "1250.50" into paisa. Rejects more than 2 decimals. */
	public static Money parseRupees(String rupees) {
		if (rupees == null || rupees.isBlank()) {
			throw new IllegalArgumentException("Amount is required");
		}
		BigDecimal value;
		try {
			value = new BigDecimal(rupees.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not a valid amount: " + rupees, e);
		}
		if (value.signum() < 0) {
			throw new IllegalArgumentException("Amount cannot be negative: " + rupees);
		}
		// A price cannot be more precise than one paisa, so more than two decimals is a
		// malformed amount rather than something to round. UNNECESSARY signals that by
		// throwing ArithmeticException, which is translated into the caller-facing type.
		BigDecimal paisa = value.multiply(BigDecimal.valueOf(PAISA_PER_RUPEE));
		if (paisa.stripTrailingZeros().scale() > 0) {
			throw new IllegalArgumentException(
					"Amount has more precision than one paisa: " + rupees);
		}
		return new Money(paisa.setScale(0, RoundingMode.UNNECESSARY).longValueExact(), NPR);
	}

	/**
	 * Applies a rate expressed in basis points.
	 *
	 * <p>This is how commission and tax are computed. The rounding mode is stated rather than
	 * left to a default, because "does 5% of 99 paisa round up or down?" is a question the
	 * ledger must answer identically every time.
	 *
	 * <p>{@code HALF_UP} matches how a shopkeeper and a tax authority both round money, which
	 * matters more than mathematical purity when the number is what a merchant is charged.
	 */
	public Money applyBasisPoints(int basisPoints, RoundingMode rounding) {
		if (basisPoints < 0) {
			throw new IllegalArgumentException("Basis points cannot be negative: " + basisPoints);
		}
		BigDecimal result = BigDecimal.valueOf(amountMinor)
				.multiply(BigDecimal.valueOf(basisPoints))
				.divide(BASIS_POINTS, 0, rounding);
		return new Money(result.longValueExact(), currency);
	}

	public Money applyBasisPoints(int basisPoints) {
		return applyBasisPoints(basisPoints, RoundingMode.HALF_UP);
	}

	/**
	 * Distributes {@code totalMinor} across {@code weights} so the parts sum exactly to the
	 * total.
	 *
	 * <p>Needed when a discount or shipping charge must be spread over order lines. Dividing
	 * each share independently loses or invents a paisa, and the line items then do not add up
	 * to the order total — which is precisely the arithmetic a customer will check.
	 *
	 * <p>Remainder paisa are handed to the earliest weights, so the sum is exact by
	 * construction rather than by luck.
	 */
	public long[] distribute(long[] weights) {
		if (weights.length == 0) {
			return new long[0];
		}
		long weightSum = 0;
		for (long w : weights) {
			if (w < 0) {
				throw new IllegalArgumentException("Weights cannot be negative: " + w);
			}
			weightSum += w;
		}
		long[] shares = new long[weights.length];
		if (weightSum == 0) {
			return shares;
		}

		long allocated = 0;
		for (int i = 0; i < weights.length; i++) {
			shares[i] = Math.floorDiv(amountMinor * weights[i], weightSum);
			allocated += shares[i];
		}

		// Hand the leftover to the first non-zero weights, one paisa each.
		long remainder = amountMinor - allocated;
		for (int i = 0; remainder > 0 && i < weights.length; i++) {
			if (weights[i] > 0) {
				shares[i]++;
				remainder--;
			}
		}
		return shares;
	}

	public Money plus(Money other) {
		requireSameCurrency(other);
		return new Money(amountMinor + other.amountMinor, currency);
	}

	public Money minus(Money other) {
		requireSameCurrency(other);
		long result = amountMinor - other.amountMinor;
		if (result < 0) {
			throw new IllegalArgumentException(
					"Resulting amount would be negative: " + amountMinor + " - " + other.amountMinor);
		}
		return new Money(result, currency);
	}

	public Money times(long multiplier) {
		if (multiplier < 0) {
			throw new IllegalArgumentException("Multiplier cannot be negative: " + multiplier);
		}
		return new Money(amountMinor * multiplier, currency);
	}

	/** Fractional share of this amount, rounded to whole paisa. */
	public Money proportionOf(long part, long whole) {
		if (whole <= 0) {
			throw new IllegalArgumentException("Denominator must be positive: " + whole);
		}
		BigDecimal result = BigDecimal.valueOf(amountMinor)
				.multiply(BigDecimal.valueOf(part))
				.divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP);
		return new Money(result.longValueExact(), currency);
	}

	public boolean isZero() {
		return amountMinor == 0;
	}

	public boolean isPositive() {
		return amountMinor > 0;
	}

	public long minor() {
		return amountMinor;
	}

	/**
	 * Display string, e.g. 125050 -> "Rs. 1,250.50". For UI and invoices only.
	 *
	 * <p>Formatted manually rather than through {@code NumberFormat}, because the default
	 * pattern drops a trailing zero: 125050 would render "Rs. 1,250.5" instead of
	 * "Rs. 1,250.50". A price shown with one decimal looks like a rounding error to a customer.
	 */
	public String toDisplay() {
		BigDecimal rupees = BigDecimal.valueOf(amountMinor)
				.movePointLeft(2)
				.setScale(2, RoundingMode.UNNECESSARY);
		String[] parts = rupees.toPlainString().split("\\.");
		String grouped = java.text.NumberFormat.getNumberInstance(
				java.util.Locale.of("en-NP")).format(new BigDecimal(parts[0]));
		return "Rs. " + grouped + "." + parts[1];
	}

	@Override
	public int compareTo(Money other) {
		requireSameCurrency(other);
		return Long.compare(amountMinor, other.amountMinor);
	}

	private void requireSameCurrency(Money other) {
		if (!currency.equals(other.currency)) {
			throw new IllegalArgumentException(
					"Currency mismatch: " + currency + " vs " + other.currency);
		}
	}

	@Override
	public String toString() {
		return toDisplay();
	}
}
