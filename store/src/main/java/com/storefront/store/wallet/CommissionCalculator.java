package com.storefront.store.wallet;

import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.storefront.store.common.Money;

/**
 * Computes the platform commission on an order.
 *
 * <p>The rules, exactly as specified:
 *
 * <ul>
 *   <li><b>When:</b> charged when the shop <em>accepts</em> the order, not when it is placed
 *       and not when it is delivered. An order the shop never accepts is never charged.</li>
 *   <li><b>How much:</b> 5% of {@code (subtotal - discount)}. Shipping and tax are
 *       <em>excluded</em> — the platform does not take a cut of what it does not carry.</li>
 *   <li><b>Who:</b> a subscribed shop pays nothing.</li>
 *   <li><b>Cancellation:</b> a reversal credit restores the debit.</li>
 * </ul>
 *
 * <p>Every one of those is a test in {@code CommissionCalculatorTest}, because this is the
 * class where a bug costs real money (plan risk R1).
 */
@Component
public class CommissionCalculator {

	/** 5%, in basis points. Matches platform_setting.commission_rate_bp. */
	public static final int DEFAULT_RATE_BP = 500;

	/**
	 * The amount a commission is charged on.
	 *
	 * <p>Subtotal minus discount, excluding shipping and tax. This is the one place that
	 * definition lives; the same rule appears in the open question at plan section 11 and must
	 * not be restated differently anywhere else.
	 */
	public Money commissionableBase(Money subtotal, Money discount, Money shipping, Money tax) {
		Money base = subtotal.minus(discount);
		// Shipping and tax are deliberately not added: the platform takes no cut of delivery
		// or of government tax. Passing them keeps the signature honest about what callers
		// might wrongly include.
		if (shipping != null && tax != null) {
			// Referenced so the intent is explicit in the signature and in the Javadoc.
			assert shipping.minor() >= 0 && tax.minor() >= 0;
		}
		return base;
	}

	/**
	 * Commission for an accepted order.
	 *
	 * @param rateBp          commission rate in basis points
	 * @param subscribed      true when the shop is on a paid plan and pays nothing
	 * @return zero money when subscribed, otherwise the computed commission
	 */
	public Commission calculate(Money subtotal, Money discount, Money shipping, Money tax,
			int rateBp, boolean subscribed) {

		if (rateBp < 0) {
			throw new IllegalArgumentException("Rate cannot be negative: " + rateBp);
		}

		Money base = commissionableBase(subtotal, discount, shipping, tax);

		if (subscribed) {
			// Recorded as an explicit waived result rather than a null, so the order and the
			// ledger entry both carry a reason a reviewer can read.
			return new Commission(base, Money.zero(), 0, true);
		}

		Money commission = base.applyBasisPoints(rateBp, RoundingMode.HALF_UP);
		return new Commission(base, commission, rateBp, false);
	}

	public Commission calculate(Money subtotal, Money discount, int rateBp, boolean subscribed) {
		return calculate(subtotal, discount, Money.zero(), Money.zero(), rateBp, subscribed);
	}

	public Commission calculate(Money subtotal, int rateBp, boolean subscribed) {
		return calculate(subtotal, Money.zero(), rateBp, subscribed);
	}

	/**
	 * The outcome of a commission calculation.
	 *
	 * @param commissionableBase what the percentage was applied to
	 * @param amount            the commission, zero when waived
	 * @param rateBp            the rate used, zero when waived
	 * @param waived            true when the shop is subscribed
	 */
	public record Commission(Money commissionableBase, Money amount, int rateBp, boolean waived) {

		public boolean isChargeable() {
			return !waived && amount.isPositive();
		}
	}
}
