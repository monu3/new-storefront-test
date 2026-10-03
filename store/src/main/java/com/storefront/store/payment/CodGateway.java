package com.storefront.store.payment;

import org.springframework.stereotype.Component;

import com.storefront.store.common.Money;

/**
 * Cash on delivery.
 *
 * <p>Nothing to initiate and nothing to verify at checkout — the money changes hands when the
 * courier arrives. That is exactly why the interface has two methods rather than one: COD's
 * correct behaviour at checkout is to do nothing, and a single "pay" method would have to fake
 * something to satisfy its own signature.
 *
 * <p>{@link #verify} returns "not paid, and cannot know" rather than "paid". The order is
 * marked paid at <em>ship</em> time, when the shop has the cash in hand — see
 * {@code OrderService.markShipped}. Getting that wrong in the other direction would let a shop
 * declare money collected that was never collected.
 */
@Component
public class CodGateway implements PaymentGateway {

	@Override
	public ShopOrderPaymentMethod method() {
		return ShopOrderPaymentMethod.COD;
	}

	@Override
	public String displayName() {
		return "Cash on delivery";
	}

	@Override
	public Initiation initiate(Money amount, String orderRef, ShopContext shop) {
		// The reference is the order number itself: there is no gateway transaction for cash, and
		// inventing a reference here would suggest to a shop owner that a payment had been
		// registered somewhere.
		return new Initiation(null, orderRef,
				"Pay Rs. " + amount.toDisplay().replaceFirst("^Rs\\.\\s*", "")
						+ " in cash when your order arrives.",
				false);
	}

	@Override
	public Verification verify(String gatewayRef, ShopContext shop) {
		// Cannot be verified remotely, and saying otherwise would be a lie the platform then acts
		// on. The order is marked paid by the shop at dispatch.
		return Verification.notYetPaid(
				"Cash on delivery is confirmed by the shop when the order is dispatched.");
	}
}
