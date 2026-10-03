package com.storefront.store.checkout;

import java.util.List;

import com.storefront.store.common.Money;

/**
 * A server-computed price breakdown.
 *
 * <p><b>The server is the only authority on these numbers.</b> The browser sends
 * {@code variantId} and {@code quantity}; everything else — price, discount, tax, shipping,
 * total — is computed here from the database. A client that sends its own total is asking to be
 * believed, and a client that is believed loses money on every discounted order.
 *
 * <p><b>The line totals sum to the order total exactly.</b> Not approximately: the discount and
 * tax are distributed across lines with {@link Money#distribute(long[])}, which hands the
 * leftover paisa to the earliest weights so the parts add up by construction. A customer who adds
 * up the line totals and finds a one-paisa discrepancy concludes the shop's arithmetic is broken,
 * and they are right to.
 *
 * <p>Immutable, so a quote can be returned to the customer, displayed, and then re-derived at
 * order time without any risk of a half-applied edit in between.
 */
public record Quote(
		List<QuoteLine> lines,
		long subtotalMinor,
		long discountMinor,
		long taxMinor,
		long shippingMinor,
		long totalMinor,
		String currency,
		/** The discount that applied, null when none did. */
		Discount appliedDiscount,
		/** Why a supplied code did not apply. Null when there was no code, or it worked. */
		String discountMessage,
		/** The zone used, null when the shop has none configured and delivery is free. */
		ShippingZone shippingZone,
		String deliveryEstimate) {

	/** A delivery estimate the customer can be told, even when the shop has no zones. */
	public String deliveryEstimateOrDefault() {
		return deliveryEstimate == null ? "Delivery arranged by the shop" : deliveryEstimate;
	}

	public boolean hasDiscount() {
		return discountMinor > 0;
	}

	/**
	 * The commission the shop will owe if it accepts.
	 *
	 * <p>Shown in the shop console so an owner knows what accepting costs, and never to a
	 * customer. The same {@link com.storefront.store.wallet.CommissionCalculator} instance is
	 * used, so this figure cannot drift from what {@code OrderService} actually debits.
	 */
	public Money commissionIfAccepted(com.storefront.store.wallet.CommissionCalculator calculator,
			boolean subscribed, int rateBp) {
		return calculator.calculate(Money.ofMinor(subtotalMinor), Money.ofMinor(discountMinor),
				Money.ofMinor(shippingMinor), Money.ofMinor(taxMinor), rateBp, subscribed).amount();
	}

	/**
	 * One line of the quote.
	 *
	 * <p>{@code lineTotal} includes this line's share of the discount and its tax, so the sum of
	 * the line totals is the order total less shipping — which is exactly how an invoice should
	 * read, with delivery as the final line.
	 */
	public record QuoteLine(
			String productId,
			String variantId,
			String productName,
			String variantName,
			String imageUrl,
			String category,
			String categoryLabel,
			long unitPriceMinor,
			int quantity,
			long grossMinor,
			long discountMinor,
			long taxMinor,
			long lineTotalMinor,
			/** Units the shop can actually fulfil. Lower than quantity only for a backorder. */
			int availableStock,
			boolean purchasable) {

		/** What the storefront shows. Money fields are paisa; the client formats. */
		public String displayTotal() {
			return Money.ofMinor(lineTotalMinor).toDisplay();
		}
	}
}
