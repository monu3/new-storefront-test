package com.storefront.store.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.identity.IdentitySyncService;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the caller's {@link AppUser} from the current security context.
 *
 * <p>This is the boundary between "who the token says you are" and "who you are in this
 * application". A JWT is only proof of authentication; it says nothing about whether a Postgres
 * user exists yet, has been suspended, or owns a shop. Every authenticated request goes through
 * here so those questions are answered once, in one place.
 *
 * <p>Profile creation is delegated to {@link IdentitySyncService} rather than done inline, so
 * that the same logic is available to a startup self-test and to any future non-HTTP entry
 * point.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

	private final IdentitySyncService identitySync;
	private final AppUserRepository users;

	/**
	 * The caller's user row, created on first sight.
	 *
	 * <p>Throws when there is no authenticated JWT. Callers that expect anonymity must not use
	 * this — that is what {@link #optionalUser()} is for.
	 */
	@Transactional
	public AppUser require() {
		AppUser user = optionalUser();
		if (user == null) {
			throw new InvalidBearerTokenException("No authenticated principal on this request");
		}
		return user;
	}

	/** The caller's user row, or null for an anonymous request. */
	@Transactional
	public AppUser optionalUser() {
		Jwt jwt = currentJwt();
		if (jwt == null) {
			return null;
		}
		return identitySync.resolveOrCreate(jwt);
	}

	/** The caller's user id, or null. Cheaper than {@link #optionalUser()} when only the id is needed. */
	@Transactional
	public UUID optionalUserId() {
		AppUser user = optionalUser();
		return user == null ? null : user.getId();
	}

	/**
	 * The raw JWT, or null.
	 *
	 * <p>Returns null rather than throwing so public endpoints can read the token when present
	 * (to personalise a storefront) and carry on when it is not.
	 */
	public Jwt currentJwt() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		if (authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt;
		}
		return null;
	}

	/** The token's subject, i.e. the Keycloak user id. */
	public String currentSubject() {
		Jwt jwt = currentJwt();
		return jwt == null ? null : jwt.getSubject();
	}

	/**
	 * Throws unless the caller is signed in.
	 *
	 * <p>Separate from {@link #require()} because this one does not touch the database, so it is
	 * safe to use on a path that must not create a profile — an admin endpoint, say, where an
	 * unknown token should be rejected rather than silently create a user.
	 */
	public Jwt requireJwt() {
		Jwt jwt = currentJwt();
		if (jwt == null) {
			throw new InvalidBearerTokenException("This endpoint requires a Bearer token");
		}
		return jwt;
	}

	/** True when the caller holds the given authority, e.g. {@code ROLE_ADMIN}. */
	public boolean hasRole(String authority) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return false;
		}
		return authentication.getAuthorities().stream()
				.anyMatch(a -> a.getAuthority().equals(authority));
	}
}
