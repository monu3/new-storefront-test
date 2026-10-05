package com.storefront.store.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OAuth2 resource server configuration for the store.app API.
 *
 * <p>Two properties define this class and both are deliberate (plan decisions D3 and D5):
 *
 * <ol>
 *   <li><b>This server issues no tokens.</b> There is no login endpoint, no registration
 *       endpoint, no refresh endpoint. Keycloak owns authentication entirely. ArchUnit asserts
 *       no such endpoint ever appears.</li>
 *   <li><b>Two issuers, two decoders.</b> Tokens from the {@code ops} and {@code customers}
 *       realms are validated by separate decoders selected by the {@code iss} claim, then
 *       mapped to authorities by {@link KeycloakJwtAuthenticationConverter}. A token from the
 *       wrong realm is structurally incapable of carrying a role from the other realm.</li>
 * </ol>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(KeycloakProperties.class)
public class SecurityConfig {

	@Bean
	public JwtDecoder jwtDecoder(KeycloakProperties props) {
		// Selects a decoder purely by the 'iss' claim. A token from any other
		// issuer matches no entry and is rejected before any role is read.
		return new MultiIssuerJwtDecoder(perRealmDecoders(props));
	}

	/**
	 * Builds one decoder per realm, each bound to exactly one issuer.
	 *
	 * <p>Exposed as a package-visible method rather than inlined into the bean so tests can
	 * assert the per-realm guarantee directly: presenting a customers-realm token to the ops
	 * decoder must fail. Testing only the router would pass even if each decoder were
	 * misconfigured to accept both realms, because the router would send the token to the
	 * matching decoder and never expose the flaw.
	 */
	Map<String, JwtDecoder> perRealmDecoders(KeycloakProperties props) {
		// withIssuerLocation() discovers the realm's JWKS endpoint from its
		// OpenID configuration and caches the keys. There is no public
		// jwkSetUri() builder method in Spring Security 6 — the JWK Set URI is
		// derived, not configured.
		NimbusJwtDecoder opsDecoder = NimbusJwtDecoder
				.withIssuerLocation(props.opsIssuer())
				.build();
		opsDecoder.setJwtValidator(validatorFor(props.opsIssuer()));

		NimbusJwtDecoder customerDecoder = NimbusJwtDecoder
				.withIssuerLocation(props.customerIssuer())
				.build();
		customerDecoder.setJwtValidator(validatorFor(props.customerIssuer()));

		return Map.of(
				props.opsIssuer(), opsDecoder,
				props.customerIssuer(), customerDecoder);
	}

	/**
	 * Each decoder must accept exactly ONE issuer, and that issuer must be the one it was
	 * built for.
	 *
	 * <p>Why not a {@code DelegatingOAuth2TokenValidator} holding both
	 * {@link JwtIssuerValidator}s: that validator returns the <em>first</em> error, so a token
	 * from {@code ops} presented to the {@code customers} decoder fails its ops-validator and
	 * is rejected — which happens to be safe, but it is safe by accident. Worse, it also means
	 * a misconfigured decoder silently accepts <em>either</em> realm, which is exactly the
	 * cross-realm leak this design exists to prevent. Binding one validator per decoder makes
	 * the guarantee structural rather than incidental.
	 *
	 * <p>This bug was caught by {@code KeycloakLiveRealmIT}, not by the unit tests, because the
	 * unit tests substitute stub decoders and therefore never exercise the real validator
	 * chain.
	 */
	private OAuth2TokenValidator<Jwt> validatorFor(String expectedIssuer) {
		return new DelegatingOAuth2TokenValidator<>(
				new JwtIssuerValidator(expectedIssuer),
				new JwtTimestampValidator());
	}

	@Bean
	public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(KeycloakProperties props) {
		return new KeycloakJwtAuthenticationConverter(props.opsIssuer(), props.customerIssuer());
	}

	@Bean
	public SecurityFilterChain filterChain(
			HttpSecurity http,
			Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {

		return http
				// No cookies, no sessions, no CSRF token: this is a token-only API.
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// The frontends send Bearer tokens, never cookies, so CORS is
				// credential-free. Allowed origins are the two localhost dev servers.
				.cors(cors -> cors.configurationSource(request -> {
					var config = new org.springframework.web.cors.CorsConfiguration();
					config.setAllowedOrigins(List.of(
							"http://localhost:3000",
							"http://localhost:5173"));
					config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
					config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Requested-With"));
					config.setExposedHeaders(List.of("Location", "Idempotency-Key"));
					config.setMaxAge(Duration.ofHours(1));
					return config;
				}))
				.authorizeHttpRequests(auth -> auth
						// ---- public: storefront and gallery (Phase 1 onward) ----
						.requestMatchers(HttpMethod.GET,
								"/actuator/health",
								"/actuator/info",
								// OpenAPI contract: required by the Admin Data
								// Explorer (D5), and the SPA cannot send an
								// Authorization header on a plain fetch.
								"/api/v1/openapi.json",
								"/api/v1/swagger-ui.html",
								"/api/v1/swagger-ui/**",
								"/v3/api-docs/**",
								// public shop gallery / storefront reads
								"/api/v1/shops/**",
								"/api/v1/p/**",
								// enums feeding the shop-type and category
								// dropdowns; contains no tenant data
								"/api/v1/meta/**",
								// payment gateway callbacks are unauthenticated
								// by design: the gateway has no token, so
								// authenticity is proven by verifying the
								// transaction server-to-server.
								"/api/v1/payments/*/callback")
						.permitAll()
						// ---- guest checkout ----
						// Public, and deliberately so. A storefront must sell to someone
						// with no account: that is the single largest source of lost sales
						// in a marketplace, and requiring registration before a
						// customer can see a total is a good way to have it.
						//
						// A token is still read when one is present (optional auth), so
						// a signed-in customer's order is attached to their account and
						// appears in their history. Without one it is a guest order
						// identified by email, which is why guest_email exists on the
						// order at all.
						//
						// Nothing here reads tenant data: a quote prices a public
						// catalogue, and placing an order creates rows for a shop the
						// caller already identified by slug.
						//
						// Path-wide rather than per-method. CheckoutController
						// exposes only GET (quote, methods, shops) and POST (place
						// an order), and both are the guest flow; a DELETE or PUT
						// under /checkout would be a new feature that has to make
						// its own decision here. Scoping per method is not
						// possible in the lambda DSL anyway — requestMatchers()
						// returns a RequestMatcher, not a builder.
						.requestMatchers("/api/v1/checkout/**")
						.permitAll()
						// Push registration, and only the registration.
						//
						// A service worker obtains an FCM token after the page loads, which is
						// usually before anybody signs in -- a first visit to a storefront is
						// exactly when a token arrives. Requiring authentication would move
						// registration onto the account page, which customers never visit, and
						// order-status push would then reach only people who had already found
						// their way to settings.
						//
						// Safe because an unattached token belongs to nobody: the fan-out query
						// is keyed by user, so a token registered signed out can never be sent
						// anything until a customer signs in and claims it.
						//
						// Scoped to POST deliberately. GET lists the caller's own devices and
						// DELETE stops sending to one; both disclose or change something that
						// needs an identity, so they stay behind anyRequest().authenticated().
						.requestMatchers(HttpMethod.POST, "/api/v1/customer/devices")
						.permitAll()
						// A server-side error is dispatched back through the filter chain with the
						// original request. On a public endpoint that means anyRequest() fires
						// against a request with no token, and a 500 is reported to the caller as a
						// 401 "a valid Bearer token is required" — which is not just wrong, it
						// sends whoever is debugging it looking at authentication.
						//
						// Found by a hand-rolled Map.toString() producing invalid JSON: the database
						// error was real and correct, and the status code hid it completely.
						.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
						.permitAll()
						// ---- everything else requires a valid token ----
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint((request, response, ex) -> {
							response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
							response.setContentType("application/problem+json");
							response.getWriter().write(
									"{\"type\":\"about:blank\",\"title\":\"Unauthorized\","
									+ "\"status\":401,\"detail\":\"A valid Bearer token is required. "
									+ "Send an Authorization header issued by one of the configured Keycloak realms.\"}");
						}))
				.exceptionHandling(ex -> ex
						.accessDeniedHandler((request, response, denied) -> {
							response.setStatus(HttpServletResponse.SC_FORBIDDEN);
							response.setContentType("application/problem+json");
							response.getWriter().write(
									"{\"type\":\"about:blank\",\"title\":\"Forbidden\","
									+ "\"status\":403,\"detail\":\"Your token does not grant access to this resource.\"}");
						}))
				.headers(headers -> headers
						.frameOptions(frame -> frame.sameOrigin())   // swagger-ui needs frames
						.contentTypeOptions(ct -> {}))
				.build();
	}
}
