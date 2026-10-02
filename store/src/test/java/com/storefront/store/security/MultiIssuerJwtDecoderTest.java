package com.storefront.store.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Proves plan decision D3: two realms, three roles, and no role may cross realms.
 *
 * <p>These are pure unit tests with no Spring context and no network. The live end-to-end
 * check against the real Keycloak is {@code KeycloakLiveRealmIT}.
 */
class MultiIssuerJwtDecoderTest {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUST_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	/** Builds a structurally valid unsigned JWT with the given issuer. Signature is not checked here. */
	private static String token(String issuer) {
		String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
		String payload = base64Url("{\"iss\":\"" + issuer + "\",\"sub\":\"user-1\",\"exp\":"
				+ (Instant.now().getEpochSecond() + 3600) + "}");
		return header + "." + payload + ".signature";
	}

	private static String base64Url(String json) {
		return java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static Jwt jwtFrom(String issuer, Set<String> realmRoles) {
		return Jwt.withTokenValue(token(issuer))
				.header("alg", "RS256")
				.claim("iss", issuer)
				.claim("sub", "user-1")
				.claim("realm_access", Map.of("roles", realmRoles))
				.build();
	}

	@Nested
	@DisplayName("issuer routing")
	class IssuerRouting {

		@Test
		@DisplayName("routes an ops token to the ops decoder")
		void routesOpsToken() {
			var opsCalls = new int[1];
			var decoder = new MultiIssuerJwtDecoder(Map.of(
					OPS_ISSUER, counting(opsCalls),
					CUST_ISSUER, forbidden()));
			decoder.decode(token(OPS_ISSUER));

			assertThat(opsCalls[0]).as("ops decoder should have been used exactly once").isEqualTo(1);
		}

		@Test
		@DisplayName("routes a customers token to the customers decoder")
		void routesCustomerToken() {
			var custCalls = new int[1];
			var decoder = new MultiIssuerJwtDecoder(Map.of(
					OPS_ISSUER, forbidden(),
					CUST_ISSUER, counting(custCalls)));
			decoder.decode(token(CUST_ISSUER));

			assertThat(custCalls[0]).isEqualTo(1);
		}

		@Test
		@DisplayName("rejects a token from an untrusted issuer without trying any decoder")
		void rejectsUnknownIssuer() {
			var anyCalls = new int[1];
			JwtDecoder spy = counting(anyCalls);

			var decoder = new MultiIssuerJwtDecoder(Map.of(OPS_ISSUER, spy));

			assertThatThrownBy(() -> decoder.decode(token("https://evil.example.com/realms/ops")))
					.isInstanceOf(JwtException.class)
					.hasMessageContaining("Untrusted issuer");

			assertThat(anyCalls[0]).as("an unknown issuer must not be tried against a configured decoder").isZero();
		}

		@Test
		@DisplayName("rejects a malformed token")
		void rejectsMalformed() {
			var decoder = new MultiIssuerJwtDecoder(Map.of(OPS_ISSUER, counting(new int[1])));
			assertThatThrownBy(() -> decoder.decode("not-a-jwt"))
					.isInstanceOf(JwtException.class)
					.hasMessageContaining("Malformed");
		}

		@Test
		@DisplayName("rejects construction with no decoders")
		void rejectsEmptyDecoders() {
			assertThatThrownBy(() -> new MultiIssuerJwtDecoder(Map.of()))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@EnabledIfEnvironmentVariable(named = "KC_INTEGRATION_TEST", matches = "true")
		@DisplayName("regression: a real decoder rejects a token from the other realm")
		void realDecoderRejectsForeignRealm() {
			var props = new KeycloakProperties(
					"https://auth-master.yenyasoft.com", OPS_ISSUER, CUST_ISSUER,
					"store-app-console", "store-app-customer-web");
			// Reach past the router to the ops decoder specifically. Testing the
			// router would pass trivially, because the router would simply send
			// a customers token to the customers decoder.
			JwtDecoder opsOnly = new SecurityConfig().perRealmDecoders(props).get(OPS_ISSUER);

			// A genuine customers-realm token, presented to the ops decoder.
			String custToken = fetchCustToken();

			// The earlier implementation shared one validator across BOTH
			// issuers on both decoders, so each decoder accepted either realm.
			// The rejection may surface as a signature failure or an issuer
			// failure depending on which check fires first; either proves the
			// ops decoder will not accept a customers-realm token.
			assertThatThrownBy(() -> opsOnly.decode(custToken))
					.isInstanceOf(JwtException.class);
		}

		@Test
		@EnabledIfEnvironmentVariable(named = "KC_INTEGRATION_TEST", matches = "true")
		@DisplayName("regression: an ops token is rejected by the customers decoder")
		void realCustomerDecoderRejectsOpsToken() {
			var props = new KeycloakProperties(
					"https://auth-master.yenyasoft.com", OPS_ISSUER, CUST_ISSUER,
					"store-app-console", "store-app-customer-web");
			JwtDecoder custOnly = new SecurityConfig().perRealmDecoders(props).get(CUST_ISSUER);

			String opsToken = fetchOpsToken();

			assertThatThrownBy(() -> custOnly.decode(opsToken))
					.isInstanceOf(JwtException.class);
		}

		private static String fetchOpsToken() {
			String body = "grant_type=password&client_id=store-app-console"
					+ "&username=" + System.getenv("KC_OPS_USER")
					+ "&password=" + System.getenv("KC_OPS_PASS");
			try {
				var req = java.net.http.HttpRequest.newBuilder()
						.uri(java.net.URI.create(OPS_ISSUER + "/protocol/openid-connect/token"))
						.header("Content-Type", "application/x-www-form-urlencoded")
						.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
						.build();
				var res = java.net.http.HttpClient.newHttpClient()
						.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
				return new com.fasterxml.jackson.databind.ObjectMapper()
						.readTree(res.body()).get("access_token").asText();
			} catch (Exception e) {
				throw new IllegalStateException("could not mint ops token", e);
			}
		}

		private static String fetchCustToken() {
			String body = "grant_type=password&client_id=store-app-customer-web"
					+ "&username=" + System.getenv("KC_CUST_USER")
					+ "&password=" + System.getenv("KC_CUST_PASS");
			try {
				var req = java.net.http.HttpRequest.newBuilder()
						.uri(java.net.URI.create(CUST_ISSUER + "/protocol/openid-connect/token"))
						.header("Content-Type", "application/x-www-form-urlencoded")
						.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
						.build();
				var res = java.net.http.HttpClient.newHttpClient()
						.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
				return new com.fasterxml.jackson.databind.ObjectMapper()
						.readTree(res.body()).get("access_token").asText();
			} catch (Exception e) {
				throw new IllegalStateException("could not mint customer token", e);
			}
		}

		/** A decoder that records invocation via a counter and returns a stub Jwt. */
		private JwtDecoder counting(int[] calls) {
			return token -> {
				calls[0]++;
				return Jwt.withTokenValue(token)
						.header("alg", "RS256")
						.claim("sub", "user-1")
						.claim("iss", "stub")
						.build();
			};
		}

		/** A decoder that must never be reached. */
		private JwtDecoder forbidden() {
			return token -> {
				throw new AssertionError("the wrong decoder was used for this token");
			};
		}
	}

	@Nested
	@DisplayName("role mapping is realm-scoped")
	class RoleMapping {

		private final KeycloakJwtAuthenticationConverter converter =
				new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUST_ISSUER);

		private Set<String> authorities(Jwt jwt) {
			return converter.convert(jwt).getAuthorities().stream()
					.map(a -> a.getAuthority())
					.collect(java.util.stream.Collectors.toSet());
		}

		@Test
		@DisplayName("ops ADMIN -> ROLE_ADMIN")
		void opsAdmin() {
			assertThat(authorities(jwtFrom(OPS_ISSUER, Set.of("ADMIN")))).contains("ROLE_ADMIN");
		}

		@Test
		@DisplayName("ops SHOP -> ROLE_SHOP")
		void opsShop() {
			assertThat(authorities(jwtFrom(OPS_ISSUER, Set.of("SHOP")))).contains("ROLE_SHOP");
		}

		@Test
		@DisplayName("customers CUSTOMER -> ROLE_CUSTOMER")
		void customer() {
			assertThat(authorities(jwtFrom(CUST_ISSUER, Set.of("CUSTOMER")))).contains("ROLE_CUSTOMER");
		}

		@Test
		@DisplayName("a customers token claiming ADMIN does NOT get ROLE_ADMIN")
		void customerCannotEscalateToAdmin() {
			Set<String> auth = authorities(jwtFrom(CUST_ISSUER, Set.of("CUSTOMER", "ADMIN")));

			assertThat(auth)
					.as("a customers-realm token must never carry ROLE_ADMIN")
					.doesNotContain("ROLE_ADMIN")
					.doesNotContain("ROLE_SHOP")
					.contains("ROLE_CUSTOMER");
		}

		@Test
		@DisplayName("an ops token claiming CUSTOMER does NOT get ROLE_CUSTOMER")
		void opsCannotClaimCustomerRole() {
			Set<String> auth = authorities(jwtFrom(OPS_ISSUER, Set.of("SHOP", "CUSTOMER")));

			assertThat(auth)
					.as("an ops-realm token must never carry ROLE_CUSTOMER")
					.doesNotContain("ROLE_CUSTOMER")
					.contains("ROLE_SHOP");
		}

		@Test
		@DisplayName("a completely unknown role is dropped, not granted")
		void unknownRoleDropped() {
			Set<String> auth = authorities(jwtFrom(OPS_ISSUER, Set.of("SUPERUSER")));

			assertThat(auth)
					.doesNotContain("ROLE_SUPERUSER")
					.contains("ROLE_AUTHENTICATED", "REALM_OPS");
		}

		@Test
		@DisplayName("a token with no realm_access claim still authenticates, with no business role")
		void noRealmRoles() {
			Jwt jwt = Jwt.withTokenValue(token(OPS_ISSUER))
					.header("alg", "RS256")
					.claim("iss", OPS_ISSUER)
					.claim("sub", "user-1")
					.build();

			Set<String> auth = authorities(jwt);

			assertThat(auth)
					.contains("ROLE_AUTHENTICATED", "REALM_OPS")
					.doesNotContain("ROLE_ADMIN", "ROLE_SHOP", "ROLE_CUSTOMER");
		}

		@Test
		@DisplayName("multiple valid roles in one realm are all mapped")
		void multipleRoles() {
			Set<String> auth = authorities(jwtFrom(OPS_ISSUER, Set.of("ADMIN", "SHOP")));
			assertThat(auth).contains("ROLE_ADMIN", "ROLE_SHOP");
		}
	}
}
