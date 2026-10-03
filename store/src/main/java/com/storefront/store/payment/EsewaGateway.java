package com.storefront.store.payment;

import org.springframework.stereotype.Component;

import com.storefront.store.common.Money;

/**
 * eSewa. Stubbed, and it says so.
 *
 * <p>Phase 1 does not integrate eSewa; Phase 5 does. This class exists now for one reason: so
 * that nothing in the checkout flow has to be written twice when it arrives. The alternative —
 * building checkout with {@code if (method == ESAWA) throw new UnsupportedOperationException()}
 * scattered through it — means the day eSewa lands, every one of those has to be found and
 * removed, and the ones missed are the ones that fall through to "treat it as COD".
 *
 * <p><b>It refuses rather than pretending.</b> {@link #initiate} throws instead of returning a
 * plausible-looking redirect URL, because a redirect to nowhere produces a customer who has
 * entered their details and then seen nothing happen — strictly worse than being told the method
 * is unavailable.
 *
 * <p>To make it live, three things have to happen and all three are noted here rather than left
 * for whoever picks this up:
 * <ol>
 *   <li>a merchant id and secret per shop, encrypted at rest (the columns exist:
 *       {@code esewa_merchant_id}, {@code esewa_secret_enc}),</li>
 *   <li>{@link #initiate} posts to eSewa's checkout API and returns its redirect URL,</li>
 *   <li>{@link #verify} calls eSewa's verification API and is called from the return handler,</li>
 * </ol>
 */
@Component
public class EsewaGateway implements PaymentGateway {

	@Override
	public ShopOrderPaymentMethod method() {
		return ShopOrderPaymentMethod.ESAWA;
	}

	@Override
	public String displayName() {
		return "eSewa";
	}

	@Override
	public Initiation initiate(Money amount, String orderRef, ShopContext shop) {
		throw new UnsupportedOperationException(
				"eSewa payment is not available yet. Please choose cash on delivery or bank transfer.");
	}

	@Override
	public Verification verify(String gatewayRef, ShopContext shop) {
		throw new UnsupportedOperationException("eSewa verification is not implemented yet.");
	}

	/**
	 * Whether this build can take an eSewa payment at all.
	 *
	 * <p>Called by checkout so the method is hidden from the customer's options rather than
	 * offered and then refused.
	 */
	public boolean isAvailable() {
		return false;
	}
}
