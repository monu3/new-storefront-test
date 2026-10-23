package com.storefront.store.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.support.TestConfig;

/**
 * First-login provisioning against a real database.
 *
 * <p>What matters here is that a Keycloak user becomes a usable Postgres user without any
 * registration call, that a SHOP user gets a shop, and that none of it can be tricked into the
 * wrong realm.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class IdentitySyncServiceIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUST_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private IdentitySyncService identitySync;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private com.storefront.store.support.TestFixtures fixtures;

	/** A structurally valid token. The decoder is not involved — this unit starts after validation. */
	private static Jwt jwt(String issuer, String sub, String role, String email) {
		return Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.claim("iss", issuer)
				.claim("sub", sub)
				.claim("email", email)
				.claim("name", "Test " + role)
				.claim("realm_access", Map.of("roles", List.of(role)))
				.build();
	}

	@Nested
	@DisplayName("first login")
	class FirstLogin {

		@Test
		@DisplayName("creates a user on the first request")
		void createsUser() {
			String sub = "new-" + UUID.randomUUID();

			AppUser created = identitySync.resolveOrCreate(
					jwt(OPS_ISSUER, sub, "SHOP", "shop@example.test"));

			assertThat(created.getId()).isNotNull();
			assertThat(created.getKeycloakSub()).isEqualTo(sub);
			assertThat(created.getRealm())
					.as("the realm comes from the issuer, not from a claim a client controls")
					.isEqualTo(AppUser.Realm.OPS);
			assertThat(created.getLastLoginAt()).isNotNull();
		}

		@Test
		@DisplayName("reuses the same user on later requests")
		void reusesUser() {
			String sub = "repeat-" + UUID.randomUUID();
			Jwt token = jwt(OPS_ISSUER, sub, "SHOP", "shop@example.test");

			AppUser first = identitySync.resolveOrCreate(token);
			Instant firstLogin = first.getLastLoginAt();
			AppUser second = identitySync.resolveOrCreate(token);

			assertThat(second.getId())
					.as("a second request must not create a duplicate account")
					.isEqualTo(first.getId());
			assertThat(users.findByKeycloakSub(sub)).isPresent();
		}

		@Test
		@DisplayName("updates the last-seen timestamp on each request")
		void recordsLogin() {
			String sub = "seen-" + UUID.randomUUID();
			Jwt token = jwt(OPS_ISSUER, sub, "SHOP", "shop@example.test");

			identitySync.resolveOrCreate(token);
			AppUser again = identitySync.resolveOrCreate(token);

			assertThat(again.getLastLoginAt()).isNotNull();
		}
	}

	@Nested
	@DisplayName("realm assignment")
	class Realms {

		@Test
		@DisplayName("a customers token produces a CUSTOMERS user")
		void customerRealm() {
			AppUser user = identitySync.resolveOrCreate(
					jwt(CUST_ISSUER, "cust-" + UUID.randomUUID(), "CUSTOMER", "c@example.test"));

			assertThat(user.getRealm()).isEqualTo(AppUser.Realm.CUSTOMERS);
		}

		@Test
		@DisplayName("an unrecognised issuer is rejected")
		void unknownIssuerRejected() {
			assertThatThrownBy(() -> identitySync.resolveOrCreate(
					jwt("https://rogue.example.com/realms/ops", "x", "SHOP", "x@example.test")))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("not a configured Keycloak realm");
		}

		@Test
		@DisplayName("a rogue host wearing a valid realm name is rejected")
		void rogueHostWithValidRealmNameRejected() {
			// The realm name is not a secret — it is the last path segment of every
			// legitimate token. Matching on it alone would let
			// https://rogue.example.com/realms/ops pass as the ops realm. This test pins the
			// full-URL comparison that prevents it.
			assertThatThrownBy(() -> identitySync.resolveOrCreate(
					jwt("https://rogue.example.com/realms/ops", "y", "SHOP", "y@example.test")))
					.isInstanceOf(IllegalArgumentException.class);

			// The same trick against the customer realm.
			assertThatThrownBy(() -> identitySync.resolveOrCreate(
					jwt("https://evil.test/realms/customers", "z", "CUSTOMER", "z@example.test")))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("a token with no issuer is rejected")
		void noIssuerRejected() {
			Jwt noIssuer = Jwt.withTokenValue("t").header("alg", "RS256")
					.claim("sub", "noiss-" + UUID.randomUUID())
					.claim("realm_access", Map.of("roles", List.of("SHOP")))
					.build();

			assertThatThrownBy(() -> identitySync.resolveOrCreate(noIssuer))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no issuer");
		}

		@Test
		@DisplayName("a token with no subject is rejected")
		void noSubjectRejected() {
			Jwt noSub = Jwt.withTokenValue("t").header("alg", "RS256")
					.claim("iss", OPS_ISSUER).build();

			assertThatThrownBy(() -> identitySync.resolveOrCreate(noSub))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no subject");
		}
	}

	@Nested
	@DisplayName("shop provisioning")
	class Provisioning {

		@Test
		@DisplayName("a SHOP user gets a DRAFT shop immediately")
		void shopOwnerGetsShop() {
			AppUser user = identitySync.resolveOrCreate(
					jwt(OPS_ISSUER, "owner-" + UUID.randomUUID(), "SHOP", "o@example.test"));

			Shop shop = shops.findByOwnerUserId(user.getId()).orElseThrow();
			assertThat(shop.getStatus())
					.as("a new shop is invisible until an admin approves it")
					.isEqualTo(Shop.Status.DRAFT);
			assertThat(shop.getPublishedAt())
					.as("nothing is published before verification")
					.isNull();
		}

		@Test
		@DisplayName("an ADMIN user gets no shop")
		void adminGetsNoShop() {
			AppUser user = identitySync.resolveOrCreate(
					jwt(OPS_ISSUER, "admin-" + UUID.randomUUID(), "ADMIN", "a@example.test"));

			assertThat(shops.findByOwnerUserId(user.getId()))
					.as("an admin is not a shop owner and must not get a placeholder shop")
					.isEmpty();
		}

		@Test
		@DisplayName("a CUSTOMER user gets no shop")
		void customerGetsNoShop() {
			AppUser user = identitySync.resolveOrCreate(
					jwt(CUST_ISSUER, "cust-" + UUID.randomUUID(), "CUSTOMER", "c@example.test"));

			assertThat(shops.findByOwnerUserId(user.getId())).isEmpty();
		}

		@Test
		@DisplayName("provisioning happens once, not on every login")
		void provisionedOnce() {
			String sub = "twice-" + UUID.randomUUID();
			Jwt token = jwt(OPS_ISSUER, sub, "SHOP", "t@example.test");

			identitySync.resolveOrCreate(token);
			AppUser user = users.findByKeycloakSub(sub).orElseThrow();
			identitySync.resolveOrCreate(token);

			assertThat(shops.findByOwnerUserId(user.getId()))
					.as("one owner has exactly one shop")
					.isPresent();
		}
	}

	@Nested
	@DisplayName("profile fields")
	class ProfileFields {

		@Test
		@DisplayName("falls back to the subject when the token has no email")
		void fallsBackWhenNoEmail() {
			Jwt noEmail = Jwt.withTokenValue("t").header("alg", "RS256")
					.claim("iss", OPS_ISSUER)
					.claim("sub", "noemail-" + UUID.randomUUID())
					.claim("realm_access", Map.of("roles", List.of("SHOP")))
					.build();

			AppUser user = identitySync.resolveOrCreate(noEmail);

			assertThat(user.getEmail())
					.as("a usable placeholder keeps the NOT NULL column satisfiable")
					.isNotBlank()
					.endsWith("@unknown.local");
		}

		@Test
		@DisplayName("prefers name over preferred_username")
		void prefersDisplayName() {
			Jwt token = Jwt.withTokenValue("t").header("alg", "RS256")
					.claim("iss", OPS_ISSUER)
					.claim("sub", "named-" + UUID.randomUUID())
					.claim("email", "n@example.test")
					.claim("name", "Ravi Sharma")
					.claim("preferred_username", "ravi")
					.claim("realm_access", Map.of("roles", List.of("SHOP")))
					.build();

			assertThat(identitySync.resolveOrCreate(token).getFullName()).isEqualTo("Ravi Sharma");
		}
	}

	@Nested
	@DisplayName("two owners registering")
	class TwoOwners {

		/**
		 * The bug this pins, and it is worth reading before changing anything here.
		 *
		 * <p>{@code Shop}'s constructor derives its slug from the name, and {@code SlugGenerator}
		 * only reaches for the id when the name folds to nothing. Automatic provisioning names every
		 * draft shop the same constant — {@code "My shop"} — so every draft shop was assigned the
		 * slug {@code my-shop}, and {@code shop.slug} is unique.
		 *
		 * <p>The consequence: the first owner registered and got a shop. Every owner after that hit
		 * a unique-constraint violation on their first authenticated request, which surfaced as a
		 * 500 from {@code /api/v1/shop/me} — before they had done anything whatsoever.
		 *
		 * <p>Why no existing test saw it: every other test in this project that creates a shop goes
		 * through {@code ShopService.signUp}, which allocates a unique slug. Provisioning runs on a
		 * brand-new Keycloak account and had been executed exactly once in this project's life, by
		 * the owner who had been around since Phase 1. A single execution cannot find a bug that
		 * needs two.
		 */
		@Test
		@DisplayName("both get a shop, and the slugs differ")
		void bothOwnersAreProvisioned() {
			AppUser first = identitySync.resolveOrCreate(
					jwt(OPS_ISSUER, "owner-a-" + UUID.randomUUID(), "SHOP", "a-" + UUID.randomUUID() + "@example.test"));
			AppUser second = identitySync.resolveOrCreate(
					jwt(OPS_ISSUER, "owner-b-" + UUID.randomUUID(), "SHOP", "b-" + UUID.randomUUID() + "@example.test"));

			Shop firstShop = shops.findByOwnerUserId(first.getId()).orElseThrow();
			Shop secondShop = shops.findByOwnerUserId(second.getId()).orElseThrow();

			assertThat(firstShop.getSlug())
					.as("provisioning must not collide with the other drafts")
					.isNotEqualTo(secondShop.getSlug());

			assertThat(shops.existsBySlug(firstShop.getSlug()))
					.as("the slug is the one the database actually stored")
					.isTrue();
			assertThat(shops.existsBySlug(secondShop.getSlug())).isTrue();
		}

		@Test
		@DisplayName("a shop already holding the placeholder slug does not block a new owner")
		void existingPlaceholderSlugDoesNotBlock() {
			// The oldest shop on a live platform was provisioned before this was fixed and still
			// holds `my-shop`. That row must not stop the next person registering, so seed one and
			// then provision an owner against it.
			AppUser incumbent = fixtures.owner();
			Shop holder = new Shop(incumbent, "My shop", com.storefront.store.catalog.ShopType.GROCERY);
			holder.setSlug("my-shop-" + UUID.randomUUID().toString().substring(0, 8));
			shops.saveAndFlush(holder);

			AppUser newcomer = identitySync.resolveOrCreate(
					jwt(OPS_ISSUER, "newcomer-" + UUID.randomUUID(), "SHOP", "n-" + UUID.randomUUID() + "@example.test"));

			Shop provisioned = shops.findByOwnerUserId(newcomer.getId()).orElseThrow();
			assertThat(provisioned.getSlug()).isNotBlank();
			assertThat(shops.existsBySlug(provisioned.getSlug())).isTrue();
		}
	}
}
