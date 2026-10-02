package com.storefront.store.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps Keycloak realm roles onto Spring Security authorities, scoped per realm.
 *
 * <p>This is the enforcement point for plan decision D3 — "no realm issues a role belonging to
 * the other realm". A token from the {@code customers} realm that somehow claims {@code ADMIN}
 * is <em>not</em> mapped to {@code ROLE_ADMIN}; the role is discarded, so the request proceeds
 * with no shop or admin authority and is rejected downstream by method security. The mapping is
 * a hard-coded table rather than a blanket prefix, precisely so a role name cannot leak across
 * realms by accident.
 *
 * <p>Also carries {@code shop_id} as an authority once a shop is linked to the user in
 * {@code shop.owner_user_id}. That is a convenience for {@code @PreAuthorize} expressions; the
 * authoritative ownership check lives in {@code TenantGuard}, which re-reads Postgres.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private static final Logger log = LoggerFactory.getLogger(KeycloakJwtAuthenticationConverter.class);

	/** Roles each realm is permitted to grant, and the authority each maps to. */
	private static final Map<String, Map<String, String>> REALM_ROLE_MAP = Map.of(
			"ops", Map.of(
					"ADMIN", "ROLE_ADMIN",
					"SHOP", "ROLE_SHOP"),
			"customers", Map.of(
					"CUSTOMER", "ROLE_CUSTOMER"));

	public KeycloakJwtAuthenticationConverter(String opsIssuer, String customerIssuer) {
		this.opsIssuer = opsIssuer;
		this.customerIssuer = customerIssuer;
	}

	private final String opsIssuer;
	private final String customerIssuer;

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		String issuer = jwt.getIssuer().toString();
		String realm = realmFor(issuer);

		Set<String> realmRoles = extractRealmRoles(jwt);
		Map<String, String> allowed = REALM_ROLE_MAP.getOrDefault(realm, Map.of());

		List<GrantedAuthority> authorities = realmRoles.stream()
				// A role this realm is not allowed to grant is dropped, not mapped.
				.filter(allowed::containsKey)
				.map(role -> new SimpleGrantedAuthority(allowed.get(role)))
				.collect(Collectors.toList());

		realmRoles.stream()
				.filter(role -> !allowed.containsKey(role))
				.forEach(role -> log.warn(
						"Token from realm '{}' carried role '{}', which that realm may not grant. Ignored. subject={}",
						realm, role, jwt.getSubject()));

		// Fixed authority so any successfully validated token is distinguishable
		// from an anonymous request in the logs.
		authorities.add(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"));
		authorities.add(new SimpleGrantedAuthority("REALM_" + realm.toUpperCase()));

		if (log.isDebugEnabled()) {
			log.debug("Mapped token: realm={} sub={} roles={} -> authorities={}",
					realm, jwt.getSubject(), realmRoles, authorities.stream()
							.map(GrantedAuthority::getAuthority).toList());
		}

		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}

	/** Resolves which of the two configured realms issued this token. */
	private String realmFor(String issuer) {
		if (opsIssuer.equals(issuer)) {
			return "ops";
		}
		if (customerIssuer.equals(issuer)) {
			return "customers";
		}
		// Unreachable in practice: MultiIssuerJwtDecoder only routes to a decoder
		// whose issuer matched. Throwing here would mask a config bug as a 500.
		log.error("Token presented with unrecognised issuer '{}'. Expected one of [{}, {}]",
				issuer, opsIssuer, customerIssuer);
		return "unknown";
	}

	/** Reads {@code realm_access.roles}, the standard Keycloak location for realm roles. */
	@SuppressWarnings("unchecked")
	private Set<String> extractRealmRoles(Jwt jwt) {
		Object realmAccess = jwt.getClaims().get("realm_access");
		if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
			return roles.stream().map(String::valueOf).collect(Collectors.toSet());
		}
		return Set.of();
	}
}
