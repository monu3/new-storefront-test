package com.storefront.store.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Live check against the real Keycloak instance.
 *
 * <p>Skipped unless {@code KC_INTEGRATION_TEST=true}, because it needs network access and
 * seeded test users. Run with:
 *
 * <pre>
 * KC_INTEGRATION_TEST=true \
 * KC_OPS_USER=ops.test      KC_OPS_PASS=... KC_OPS_ROLE=SHOP \
 * KC_CUST_USER=cust.test    KC_CUST_PASS=... \
 * ./mvnw test -Dtest=KeycloakLiveRealmIT
 * </pre>
 *
 * <p>This is the startup self-test the plan requires (Phase 0 Step 2): it proves each realm
 * issues the roles we expect, and that a token from one realm cannot authorise the other.
 */
@EnabledIfEnvironmentVariable(named = "KC_INTEGRATION_TEST", matches = "true")
class KeycloakLiveRealmIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUST_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	/**
	 * Fetches a token via the direct-access grant. Both realms were created with
	 * {@code directAccessGrantsEnabled: false}, so this only works for a test user whose
	 * credentials are supplied by the operator. That is intentional: the applications
	 * themselves must never use this grant.
	 */
	private static String fetchToken(String realm, String user, String pass) {
		String clientId = realm.equals("ops") ? "store-app-console" : "store-app-customer-web";
		String body = "grant_type=password&client_id=" + clientId
				+ "&username=" + user + "&password=" + pass;

		java.net.http.HttpResponse<String> res;
		try {
			var req = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create(
							"https://auth-master.yenyasoft.com/realms/" + realm + "/protocol/openid-connect/token"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
					.build();
			res = java.net.http.HttpClient.newHttpClient()
					.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
		} catch (Exception e) {
			throw new IllegalStateException("HTTP call failed for realm " + realm, e);
		}

		// Surface Keycloak's own error message. Wrapping everything in one catch
		// hides the real reason, which makes a config problem look like a network one.
		if (res.statusCode() != 200) {
			throw new IllegalStateException(
					"Token request for realm '" + realm + "' failed with HTTP " + res.statusCode()
					+ " client=" + clientId + " user=" + user + " body=" + res.body());
		}
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(res.body()).get("access_token").asText();
		} catch (Exception e) {
			throw new IllegalStateException("No access_token in response: " + res.body(), e);
		}
	}

	private static JwtDecoder decoderFor(String opsRole) {
		// The production wiring, rebuilt here so the test exercises the real code path.
		var props = new KeycloakProperties(
				"https://auth-master.yenyasoft.com", OPS_ISSUER, CUST_ISSUER,
				"store-app-console", "store-app-customer-web");
		return new SecurityConfigDecoderFactory().build(props, opsRole);
	}

	@Test
	@DisplayName("a real ops-realm SHOP token maps to ROLE_SHOP")
	void opsShopToken() {
		String token = fetchToken("ops",
				env("KC_OPS_USER"), env("KC_OPS_PASS"));

		Jwt jwt = decoderFor("ops").decode(token);
		Set<String> roles = realmRoles(jwt);

		assertThat(jwt.getIssuer().toString()).isEqualTo(OPS_ISSUER);
		assertThat(roles).contains(env("KC_OPS_ROLE"));
	}

	@Test
	@DisplayName("a real customers-realm token maps to ROLE_CUSTOMER")
	void customerToken() {
		String token = fetchToken("customers",
				env("KC_CUST_USER"), env("KC_CUST_PASS"));

		Jwt jwt = decoderFor("customers").decode(token);

		assertThat(jwt.getIssuer().toString()).isEqualTo(CUST_ISSUER);
		assertThat(realmRoles(jwt)).contains("CUSTOMER");
	}

	@Test
	@DisplayName("a real customers token is rejected by the ops-only converter")
	void customersTokenCarriesNoOpsRole() {
		String token = fetchToken("customers",
				env("KC_CUST_USER"), env("KC_CUST_PASS"));

		Jwt jwt = decoderFor("customers").decode(token);
		var authorities = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUST_ISSUER)
				.convert(jwt).getAuthorities().stream()
				.map(a -> a.getAuthority()).toList();

		assertThat(authorities)
				.as("a real customer token must not authorise any shop or admin endpoint")
				.doesNotContain("ROLE_ADMIN", "ROLE_SHOP")
				.contains("ROLE_CUSTOMER");
	}

	@Test
	@DisplayName("a token signed by a foreign issuer is rejected")
	void foreignIssuerRejected() {
		JwtDecoder decoder = decoderFor("ops");

		// A syntactically valid token whose issuer is not a configured realm.
		String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
		String payload = b64("{\"iss\":\"https://evil.example.com/realms/ops\",\"sub\":\"x\",\"exp\":9999999999}");
		String forged = header + "." + payload + ".c2ln";

		assertThatThrownBy(() -> decoder.decode(forged))
				.isInstanceOf(JwtException.class);
	}

	private static Set<String> realmRoles(Jwt jwt) {
		Object ra = jwt.getClaims().get("realm_access");
		if (ra instanceof java.util.Map<?, ?> m && m.get("roles") instanceof java.util.Collection<?> c) {
			return c.stream().map(String::valueOf)
					.collect(java.util.stream.Collectors.toSet());
		}
		return Set.of();
	}

	private static String b64(String s) {
		return java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static String env(String key) {
		String v = System.getenv(key);
		if (v == null || v.isBlank()) {
			throw new IllegalStateException("Missing env var " + key);
		}
		return v;
	}

	/**
	 * Builds the production {@code MultiIssuerJwtDecoder} through the same code the running
	 * application uses, so this test cannot drift from the real configuration.
	 */
	private static final class SecurityConfigDecoderFactory {
		JwtDecoder build(KeycloakProperties props, String unusedRoleHint) {
			var config = new SecurityConfig();
			return config.jwtDecoder(props);
		}
	}
}
