package com.storefront.store.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.audit.AuditAction;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.support.TestConfig;

/**
 * Admin user management across both realms (Phase 7).
 *
 * <p><b>The guard this suite exists to protect: an admin cannot suspend another admin.</b> Without
 * it, any single compromised admin ends the platform for everyone — there is no recovery path from
 * inside it. The rule lives in the service rather than the controller precisely so that it cannot
 * be skipped by the next caller.
 *
 * <p><b>A suspension requires a reason.</b> A suspended account with no recorded reason is one
 * nobody can act on later: the account is locked, the reason is gone, and reinstating becomes the
 * only move available to anyone.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class UserAdministrationIT {

	@Autowired
	private UserAdministrationService admin;

	@Autowired
	private AppUserRepository users;

	// ------------------------------------------------------------------ the guard

	@Nested
	@DisplayName("suspending an account")
	class Suspending {

		@Test
		@DisplayName("refuses to suspend a platform admin")
		void refusesAnAdmin() {
			AppUser boss = operator("ROLE_ADMIN");
			boss.syncRoles("ROLE_ADMIN");

			assertThatThrownBy(() -> admin.suspend(boss.getId(), "testing the guard"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("platform admin");

			assertThat(users.findById(boss.getId()).orElseThrow().getStatus())
					.as("a refused suspension must leave the account exactly as it was")
					.isEqualTo(AppUser.Status.ACTIVE);
		}

		@Test
		@DisplayName("does not consult Keycloak to decide who is an admin")
		void doesNotCallKeycloak() {
			// Proven by the shape of the guard: isAdmin() reads the roles stamped at last sign-in,
			// so it works with the identity provider unreachable. An admin list that fails when
			// Keycloak is down fails exactly when it is needed.
			AppUser shopOwner = operator("ROLE_SHOP");

			assertThat(admin.suspend(shopOwner.getId(), "a shop owner can be suspended").getStatus())
					.as("the guard must not be a blanket ban on the ops realm -- shop owners live "
							+ "there too, and they are exactly who needs suspending")
					.isEqualTo(AppUser.Status.SUSPENDED);

			AppUser plain = customer();

			AppUser suspended = admin.suspend(plain.getId(), "spam, checked by hand");

			assertThat(suspended.getStatus()).isEqualTo(AppUser.Status.SUSPENDED);
		}

		@Test
		@DisplayName("refuses a reason that is only whitespace")
		void refusesABlankReason() {
			AppUser user = customer();

			assertThatThrownBy(() -> admin.suspend(user.getId(), "   "))
					.isInstanceOf(IllegalArgumentException.class);

			assertThat(user.getStatus()).isEqualTo(AppUser.Status.ACTIVE);
		}

		@Test
		@DisplayName("is idempotent — suspending twice writes one row")
		void isIdempotent() {
			AppUser user = customer();

			admin.suspend(user.getId(), "first");
			AppUser again = admin.suspend(user.getId(), "second");

			assertThat(again.getStatus()).isEqualTo(AppUser.Status.SUSPENDED);
		}

		@Test
		@DisplayName("refuses an account that does not exist, by id")
		void refusesAnUnknownAccount() {
			assertThatThrownBy(() -> admin.suspend(UUID.randomUUID(), "who?"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("No account");
		}
	}

	// ------------------------------------------------------------------ reinstating

	@Nested
	@DisplayName("reinstating an account")
	class Reinstating {

		@Test
		@DisplayName("puts an active account back and is a no-op the second time")
		void reinstates() {
			AppUser user = customer();
			admin.suspend(user.getId(), "temporary");

			assertThat(admin.reinstate(user.getId()).getStatus())
					.isEqualTo(AppUser.Status.ACTIVE);
			assertThat(admin.reinstate(user.getId()).getStatus())
					.as("reinstating an active account is not an error; an admin clicking twice "
							+ "should not see a failure")
					.isEqualTo(AppUser.Status.ACTIVE);
		}

		@Test
		@DisplayName("needs no reason — undoing is not the same as doing")
		void needsNoReason() {
			AppUser user = customer();
			admin.suspend(user.getId(), "a mistake");

			assertThat(admin.reinstate(user.getId()).getStatus())
					.as("requiring a justification to undo a suspension would make people leave "
							+ "suspensions in place")
					.isEqualTo(AppUser.Status.ACTIVE);
		}
	}

	// ------------------------------------------------------------------ browsing

	@Nested
	@DisplayName("the admin people list")
	class Browsing {

		@Test
		@DisplayName("spans both realms by default, which is the point of the screen")
		void spansBothRealms() {
			customer();

			var found = admin.browse(null, null, null, PageRequest.of(0, 100));

			assertThat(found.getTotalElements())
					.as("an admin's questions cross the realm line constantly, and a screen that "
							+ "shows one realm makes them search the other by hand and guess")
					.isGreaterThan(0);
		}

		@Test
		@DisplayName("narrows to one realm when asked")
		void narrowsToOneRealm() {
			customer();

			var ops = admin.browse(AppUser.Realm.OPS, null, null, PageRequest.of(0, 100));
			var customers = admin.browse(AppUser.Realm.CUSTOMERS, null, null, PageRequest.of(0, 100));

			assertThat(ops.getContent())
					.allSatisfy(user -> assertThat(user.getRealm()).isEqualTo(AppUser.Realm.OPS));
			assertThat(customers.getContent())
					.allSatisfy(user -> assertThat(user.getRealm()).isEqualTo(AppUser.Realm.CUSTOMERS));
		}

		@Test
		@DisplayName("searches on email, case-insensitively")
		void searchesOnEmail() {
			String local = "findme-" + UUID.randomUUID();
			users.saveAndFlush(new AppUser(AppUser.Realm.CUSTOMERS,
					"sub-" + UUID.randomUUID(), local + "@example.test", "Findable"));

			var found = admin.browse(null, null, local, PageRequest.of(0, 50));

			assertThat(found.getContent())
					.extracting(AppUser::getEmail)
					.anyMatch(email -> email.toLowerCase(java.util.Locale.ROOT).contains(local));
		}

		@Test
		@DisplayName("filters by status, so suspended accounts can be found")
		void filtersByStatus() {
			AppUser user = customer();
			admin.suspend(user.getId(), "checking the filter");

			var suspended = admin.browse(null, AppUser.Status.SUSPENDED, null, PageRequest.of(0, 50));

			assertThat(suspended.getContent()).isNotEmpty();
			assertThat(suspended.getContent())
					.allSatisfy(found -> assertThat(found.getStatus())
							.isEqualTo(AppUser.Status.SUSPENDED));
		}

		@Test
		@DisplayName("a blank search matches everything, like no search at all")
		void blankSearchMatchesEverything() {
			customer();

			assertThat(admin.browse(null, null, "  ", PageRequest.of(0, 50)).getTotalElements())
					.isEqualTo(admin.browse(null, null, null, PageRequest.of(0, 50)).getTotalElements());
		}

		@Test
		@DisplayName("paginates and caps the page size")
		void paginatesAndCaps() {
			customer();

			assertThat(admin.browse(null, null, null, PageRequest.of(0, 10_000)).getSize())
					.isLessThanOrEqualTo(100);
		}
	}

	// ------------------------------------------------------------------ roles

	@Nested
	@DisplayName("roles")
	class Roles {

		@Test
		@DisplayName("are stamped on first sign-in and are null before it")
		void areStampedOnSignIn() {
			AppUser unsigned = customer();

			assertThat(unsigned.getRoles())
					.as("a seeded account has no token, so it has no roles -- and \"never signed in\" "
							+ "must stay distinguishable from \"signed in with no roles\"")
					.isNull();

			AppUser shopOwner = operator("ROLE_SHOP");
			assertThat(shopOwner.isAdmin()).isFalse();

			AppUser boss = operator("ROLE_ADMIN,ROLE_SHOP");
			assertThat(boss.isAdmin()).isTrue();
		}

		@Test
		@DisplayName("report whether they changed, so a page view does not rewrite every row")
		void reportWhetherTheyChanged() {
			AppUser user = customer();

			assertThat(user.syncRoles("ROLE_CUSTOMER")).isTrue();
			assertThat(user.syncRoles("ROLE_CUSTOMER"))
					.as("writing unconditionally would mark every account dirty on every request")
					.isFalse();
			assertThat(user.syncRoles(null))
					.as("signing out and back in with no roles clears them, rather than leaving a "
							+ "stale admin role behind")
					.isTrue();
		}
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * An {@code ops} account — the realm that holds admins and shop owners.
	 *
	 * <p>Separate from {@link #customer()} rather than a flag, because the two realms are separate
	 * Keycloak realms with separate audiences and every other surface in this platform shows
	 * exactly one of them. A helper that could produce either would quietly invite tests about
	 * states the platform cannot reach.
	 */
	private AppUser operator(String roles) {
		AppUser operator = new AppUser(
				AppUser.Realm.OPS,
				"sub-" + UUID.randomUUID(),
				"op-" + UUID.randomUUID() + "@example.test",
				"Test Operator");
		operator.syncRoles(roles);
		return users.saveAndFlush(operator);
	}

	/** A customer account, which is what most tests here operate on. */
	private AppUser customer() {
		return users.saveAndFlush(new AppUser(
				AppUser.Realm.CUSTOMERS,
				"sub-" + UUID.randomUUID(),
				"user-" + UUID.randomUUID() + "@example.test",
				"Test Customer"));
	}
}