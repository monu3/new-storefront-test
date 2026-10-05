package com.storefront.store.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * eSewa's endpoints and sandbox identity (D11).
 *
 * <p>Bound from {@code store.esewa.*}. The sandbox defaults are eSewa's published test values and
 * are safe to leave in a repository; a production deployment sets {@code ESEWA_FORM_URL} and
 * {@code ESEWA_STATUS_URL} to the live hosts and supplies the merchant secret per shop.
 *
 * <p><b>The merchant secret is not the property's job.</b> A shop's own credential lives in
 * {@code shop_payment_method.esewa_secret_enc}, encrypted (see {@link SecretCipher}), and this
 * class's {@code merchantSecret} is only the fallback for a shop that has not configured its own —
 * which is what makes the sandbox work out of the box without any setup.
 */
@ConfigurationProperties(prefix = "store.esewa")
public record EsewaProperties(

		/** Whether eSewa may be offered at all. */
		boolean enabled,

		/** Fallback merchant code when a shop has not configured its own. */
		String merchantId,

		/** Fallback secret. A shop's own secret overrides this. */
		String merchantSecret,

		/**
		 * Where the browser posts the payment form.
		 *
		 * <p>UAT is {@code rc-epay}; production is {@code epay.esewa.com.np}. A host difference
		 * here is not cosmetic — the sandbox accepts test wallets that the production host does
		 * not, and vice versa.
		 */
		String formUrl,

		/**
		 * The server-to-server status check.
		 *
		 * <p>A different host from the form: UAT is {@code rc.esewa.com.np}, not
		 * {@code rc-epay.esewa.com.np}. Verified live — the form path under
		 * {@code rc-epay} answers 404, which is a confusing way to discover the split.
		 */
		String statusUrl,

		/**
		 * Seconds to wait on eSewa.
		 *
		 * <p>Short by design. The customer's browser is parked on our return page while this call
		 * runs, and a slow gateway that times out after thirty seconds has already lost the sale.
		 */
		int timeoutSeconds) {

	public EsewaProperties {
		if (timeoutSeconds <= 0) {
			timeoutSeconds = 20;
		}
	}
}