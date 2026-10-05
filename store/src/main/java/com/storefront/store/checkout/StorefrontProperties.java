package com.storefront.store.checkout;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the customer-facing storefront lives.
 *
 * <p>Needed by the backend for one reason: eSewa sends the customer back to a URL, and that URL has
 * to be the shop's storefront checkout.
 *
 * <p><b>Server-configured, never taken from the request.</b> A payment return url that the
 * customer's browser can influence is an open redirect, and an open redirect on a payment page is
 * a phishing page — an attacker sends a victim to a convincing copy of the checkout and captures
 * their details. That is the whole reason this is a property rather than something assembled from
 * a query parameter.
 */
@ConfigurationProperties(prefix = "store.storefront")
public record StorefrontProperties(String baseUrl) {

	public StorefrontProperties {
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "http://localhost:3000";
		}
		// A trailing slash would produce "//checkout" in every return url. Harmless to a browser,
		// but it makes the signed field set differ from what a test expects, which is a bad way to
		// lose an afternoon.
		baseUrl = baseUrl.replaceAll("/+$", "");
	}
}