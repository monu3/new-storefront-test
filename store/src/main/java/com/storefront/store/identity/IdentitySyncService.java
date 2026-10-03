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
		AppUser existing = users.findByKeycloakSub(subject).orElse(null);
		if (existing != null) {
			existing.recordLogin();
			return existing;
		}

		AppUser created = new AppUser(realm, subject, email(jwt), name(jwt));
		created.recordLogin();

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
	 */
	private Shop provisionShop(AppUser owner) {
		if (shops.findByOwnerUserId(owner.getId()).isPresent()) {
			return shops.findByOwnerUserId(owner.getId()).orElseThrow();
		}
		Shop shop = new Shop(owner, "My shop", ShopType.GROCERY);
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
