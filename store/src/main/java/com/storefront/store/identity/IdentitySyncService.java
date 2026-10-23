package com.storefront.store.identity;

import java.util.Map;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a validated Keycloak token into a Postgres user, and a SHOP user into a shop.
 *
 * <p>There is no registration endpoint in this application (D3). A user comes into existence by
 * authenticating: the first request carrying their token creates the row, and every later
 * request reuses it. That keeps the two systems in step without a sync job, a webhook, or a
 * reconciliation process.
 *
 * <p>The realm is read from the token's {@code iss}, not from a claim the client can influence,
 * so a user cannot end up in the wrong realm by crafting a token. Cross-realm protection is
 * already done by the decoder; this simply persists the outcome.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdentitySyncService {

	private final AppUserRepository users;
	private final ShopRepository shops;
	private final com.storefront.store.shop.ShopService shopService;
	private final com.storefront.store.security.KeycloakProperties keycloak;

	/**
	 * The user for this token, created if this is their first request.
	 *
	 * <p>Idempotent: the lookup is by {@code keycloak_sub}, which is unique across both realms.
	 * Two concurrent first requests race on the insert, the database's unique constraint picks a
	 * winner, and the loser re-reads — so a double-submitted form cannot create two accounts.
	 */
	@Transactional
	public AppUser resolveOrCreate(Jwt jwt) {
		String subject = jwt.getSubject();
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("Token has no subject");
		}

		AppUser.Realm realm = realmFromIssuer(jwt);
		String authorities = authorities(jwt);

		AppUser existing = users.findByKeycloakSub(subject).orElse(null);
		if (existing != null) {
			existing.recordLogin();

			// Roles are stamped here, on the one path every authenticated request takes.
			//
			// <p>So a role change takes effect on the holder's next request rather than at some
			// cleanup job nobody remembers to run -- and so the admin console can answer "is this
			// account an admin?" without calling Keycloak, which is what lets the guard against an
			// admin suspending another admin exist at all.
			//
			// <p>Only written when something actually changed: writing unconditionally would mark
			// every account dirty on every page view.
			if (existing.syncRoles(authorities)) {
				users.saveAndFlush(existing);
			}
			return existing;
		}

		AppUser created = new AppUser(realm, subject, email(jwt), name(jwt));
		created.recordLogin();
		created.syncRoles(authorities);

		try {
			users.saveAndFlush(created);
			log.info("Created {} user for keycloak sub {}", realm, subject);
		} catch (org.springframework.dao.DataIntegrityViolationException e) {
			// Another request won the race. Its row is just as good as ours.
			log.debug("Concurrent first-login for sub {}; using the existing row", subject);
			return users.findByKeycloakSub(subject).orElseThrow(() -> e);
		}

		// A shop owner gets a shop row immediately, so the dashboard has something to render
		// before they have filled in the profile form.
		if (realm == AppUser.Realm.OPS && hasRole(jwt, "SHOP")) {
			provisionShop(created);
		}

		return created;
	}

	/**
	 * Creates the DRAFT shop a new owner starts with.
	 *
	 * <p>Separate from the public signup because the two have different rules: this one runs
	 * automatically and must never fail, so it picks a placeholder name and leaves the type
	 * unset for the owner to choose. The real signup path validates the type and the name.
	 *
	 * <p><b>The slug has to be allocated, not derived — and getting that wrong cost the platform
	 * every shop owner but the first.</b> {@link Shop}'s constructor derives its slug from the name,
	 * and {@link com.storefront.store.catalog.SlugGenerator} only falls back to the id when the
	 * name folds to nothing. The name here is never nothing: it is the constant {@code "My shop"},
	 * so every provisionally-created shop was assigned the slug {@code my-shop}, and
	 * {@code shop.slug} is unique. The first owner registered and was fine. Every owner after that
	 * met a unique-constraint violation on their very first authenticated request — a 500 from
	 * {@code /api/v1/shop/me}, before they had done anything at all.
	 *
	 * <p>Nothing about the test suite caught it, because every test that creates a shop goes
	 * through {@link ShopService#signUp}, which allocates. Automatic provisioning was exercised
	 * exactly once in this project's life, by the one owner who had been here since Phase 1.
	 * It took registering a genuine second account to find it.
	 */
	private Shop provisionShop(AppUser owner) {
		if (shops.findByOwnerUserId(owner.getId()).isPresent()) {
			return shops.findByOwnerUserId(owner.getId()).orElseThrow();
		}
		Shop shop = new Shop(owner, "My shop", ShopType.GROCERY);
		// Same allocator the owner's own sign-up uses, so the two paths cannot disagree.
		shop.setSlug(shopService.allocateUniqueSlug("My shop"));
		shop.setStatus(Shop.Status.DRAFT);
		Shop saved = shops.saveAndFlush(shop);
		log.info("Provisioned draft shop {} for owner {}", saved.getSlug(), owner.getId());
		return saved;
	}

	/**
	 * The realm that issued the token.
	 *
	 * <p>Matches the <em>whole</em> issuer against the configured values, not just the last
	 * path segment. Matching on the segment alone would accept
	 * {@code https://rogue.example.com/realms/ops} as the ops realm — the realm name is not a
	 * secret and appears in every legitimate token. The full-URL comparison is what makes the
	 * check mean something.
	 *
	 * <p>This is defence in depth rather than the primary control: {@code MultiIssuerJwtDecoder}
	 * has already rejected any token not signed by a configured issuer. But the decoder is
	 * configured in one place and this in another, and a future change to one should not silently
	 * weaken the other.
	 */
	/**
	 * The authorities from the token, as a comma-joined string.
	 *
	 * <p>Read from {@code realm_access}, which is where Keycloak puts realm roles, and prefixed with
	 * {@code ROLE_} to match Spring's authority naming — so the stored string is the same thing the
	 * security context holds, rather than a second vocabulary that has to be translated.
	 *
	 * <p>Returns null for a token with no realm roles, which is stored as null rather than an empty
	 * string so "never signed in" and "signed in with no roles" stay distinguishable.
	 */
	private String authorities(Jwt jwt) {
		Object claim = jwt.getClaim("realm_access");
		if (!(claim instanceof java.util.Map<?, ?> realmAccess)) {
			return null;
		}

		String joined = realmAccess.values().stream()
				.filter(java.util.List.class::isInstance)
				.flatMap(value -> ((java.util.List<?>) value).stream().map(String::valueOf))
				.map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
				.sorted()
				.collect(java.util.stream.Collectors.joining(","));

		return joined.isEmpty() ? null : joined;
	}

	private AppUser.Realm realmFromIssuer(Jwt jwt) {
		String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();

		if (issuer == null) {
			throw new IllegalArgumentException("Token has no issuer");
		}
		if (issuer.equals(opsIssuer())) {
			return AppUser.Realm.OPS;
		}
		if (issuer.equals(customerIssuer())) {
			return AppUser.Realm.CUSTOMERS;
		}
		throw new IllegalArgumentException("Token issuer is not a configured Keycloak realm: " + issuer);
	}

	private String opsIssuer() {
		return opsRealmBase() + "/realms/ops";
	}

	private String customerIssuer() {
		return opsRealmBase() + "/realms/customers";
	}

	/**
	 * The Keycloak base URL, taken from the configured customer issuer.
	 *
	 * <p>Derived rather than configured twice, so the two issuers can never drift apart — and so
	 * there is one place to change when the instance moves.
	 */
	private String opsRealmBase() {
		String customer = keycloak.customerIssuer();
		int marker = customer.indexOf("/realms/");
		return marker == -1 ? customer : customer.substring(0, marker);
	}

	private static String email(Jwt jwt) {
		Object email = jwt.getClaims().get("email");
		return email != null ? email.toString() : (jwt.getSubject() + "@unknown.local");
	}

	private static String name(Jwt jwt) {
		Map<String, Object> claims = jwt.getClaims();
		Object name = claims.get("name");
		if (name != null) {
			return name.toString();
		}
		Object preferred = claims.get("preferred_username");
		return preferred != null ? preferred.toString() : email(jwt);
	}

	/**
	 * Whether the token carries a realm role.
	 *
	 * <p>Read from the same {@code realm_access.roles} the authorities were mapped from, so this
	 * cannot disagree with the authorisation decision.
	 */
	private static boolean hasRole(Jwt jwt, String role) {
		Object realmAccess = jwt.getClaims().get("realm_access");
		if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof java.util.Collection<?> roles) {
			return roles.stream().anyMatch(r -> role.equals(String.valueOf(r)));
		}
		return false;
	}
}
