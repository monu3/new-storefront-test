package com.storefront.store.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keycloak connection settings for BOTH realms.
 *
 * <p>The two realms are deliberately separate (plan decision D3): admin and shop live in
 * {@code ops}, customers live in {@code customers}. That separation is what lets a customer
 * token be rejected outright by a shop endpoint, so both issuers must be configured and
 * validated independently.
 */
@ConfigurationProperties(prefix = "store.keycloak")
public record KeycloakProperties(

		/** Base URL of the Keycloak server, without a realm path. */
		String baseUrl,

		/** Expected {@code iss} of the realm that holds ADMIN and SHOP. */
		String opsIssuer,

		/** Expected {@code iss} of the realm that holds CUSTOMER. */
		String customerIssuer,

		/** Public client id used by the Vite console (shop + admin). */
		String opsClientId,

		/** Public client id used by the Next.js customer portal. */
		String customerClientId
) {
}
