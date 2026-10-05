package com.storefront.store.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Push registration and fan-out.
 *
 * <p>The properties worth protecting are all about <em>who receives a notification</em>. A token
 * that is reachable by the wrong person means an order's status goes to a stranger's phone, which
 * is a privacy leak rather than a cosmetic bug — and it is exactly the shape a public
 * registration endpoint invites.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class DeviceServiceIT {

	@Autowired
	private DeviceService devices;

	@Autowired
	private CustomerDeviceRepository repository;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private com.storefront.store.identity.AppUserRepository users;

	private UUID alice;
	private UUID bob;
	private Shop shop;

	@BeforeEach
	void setUp() {
		// Real users, because customer_device.user_id is a foreign key. A random UUID is not enough
		// to test ownership -- and the constraint rejecting it is the constraint working.
		alice = users.saveAndFlush(new com.storefront.store.identity.AppUser(
				com.storefront.store.identity.AppUser.Realm.CUSTOMERS,
				"alice-" + UUID.randomUUID(), "alice@example.test", "Alice")).getId();
		bob = users.saveAndFlush(new com.storefront.store.identity.AppUser(
				com.storefront.store.identity.AppUser.Realm.CUSTOMERS,
				"bob-" + UUID.randomUUID(), "bob@example.test", "Bob")).getId();
		shop = fixtures.createVerifiedShop("Push " + UUID.randomUUID(), ShopType.GROCERY);
	}

	// ------------------------------------------------------------------ registration

	@Nested
	@DisplayName("registration")
	class Registration {

		@Test
		@DisplayName("stores a token that has no owner yet")
		void anonymousRegistration() {
			// The common case: a first-time visitor's service worker fires before they sign in.
			assertThat(devices.register("token-abc", "web", null)).isTrue();

			var stored = repository.findByToken("token-abc").orElseThrow();
			assertThat(stored.getUserId()).isNull();
		}

		@Test
		@DisplayName("does not duplicate a token registered twice")
		void idempotent() {
			// A service worker registers on every page load. Two rows for one token would send
			// every notification twice.
			devices.register("token-abc", "web", alice);
			devices.register("token-abc", "web", alice);

			assertThat(repository.findByToken("token-abc")).isPresent();
			assertThat(repository.count()).isEqualTo(1);
		}

		@Test
		@DisplayName("refuses a blank token rather than storing one")
		void blankToken() {
			assertThat(devices.register(null, "web", alice)).isFalse();
			assertThat(devices.register("   ", "web", alice)).isFalse();
			assertThat(repository.count()).isZero();
		}

		@Test
		@DisplayName("attaches a known token to whoever signs in next")
		void claimsOnSignIn() {
			devices.register("token-abc", "web", null);
			devices.register("token-abc", "web", alice);

			assertThat(repository.findByToken("token-abc").orElseThrow().getUserId())
					.isEqualTo(alice);
		}

		@Test
		@DisplayName("revives a token FCM had rejected")
		void revivesDeadToken() {
			devices.register("token-abc", "web", alice);
			repository.findByToken("token-abc").orElseThrow().markDead();
			repository.flush();

			// A reinstalled service worker re-registers a token that was dead a moment ago.
			devices.register("token-abc", "web", alice);

			assertThat(repository.findByToken("token-abc").orElseThrow().isActive()).isTrue();
		}

		@Test
		@DisplayName("bounds how many devices one account may register")
		void deviceCap() {
			for (int i = 0; i < 10; i++) {
				assertThat(devices.register("token-" + i, "web", alice)).isTrue();
			}

			// The eleventh is refused rather than silently unregistering the oldest, which would
			// leave a customer quietly without notifications on a device they still use.
			assertThat(devices.register("token-10", "web", alice)).isFalse();
		}
	}

	// ------------------------------------------------------------------ isolation

	@Nested
	@DisplayName("who receives a notification")
	class Isolation {

		@Test
		@DisplayName("only the owner's devices, and only live ones")
		void fanOutIsScoped() {
			devices.register("alice-phone", "android", alice);
			devices.register("alice-laptop", "web", alice);
			devices.register("bob-phone", "android", bob);
			devices.register("orphan", "web", null);
			repository.findByToken("alice-laptop").orElseThrow().markDead();
			repository.flush();

			var targets = devices.listFor(alice);

			// Bob's device and the unowned token are both absent. The unowned one is the important
			// case: it exists, but nobody owns it, so a push can never be addressed to it.
			assertThat(targets).extracting(CustomerDevice::getToken)
					.containsExactly("alice-phone");
		}

		@Test
		@DisplayName("one customer cannot stop another customer's push")
		void cannotUnregisterSomeoneElses() {
			devices.register("bob-phone", "android", bob);

			assertThat(devices.unregister(alice, "bob-phone")).isFalse();
			assertThat(repository.findByToken("bob-phone").orElseThrow().getUserId())
					.as("Bob's device must survive a stranger's request")
					.isEqualTo(bob);
		}

		@Test
		@DisplayName("unregistering keeps the row, so the browser can register again")
		void unregisterDetaches() {
			devices.register("alice-phone", "android", alice);

			assertThat(devices.unregister(alice, "alice-phone")).isTrue();

			// Detached, not deleted. A browser still signed out would otherwise re-register the same
			// token and, with no row, quietly become the next person's to inherit.
			assertThat(repository.findByToken("alice-phone")).isPresent();
			assertThat(repository.findByToken("alice-phone").orElseThrow().getUserId()).isNull();
		}

		@Test
		@DisplayName("signing out detaches every device but keeps them")
		void signOut() {
			devices.register("a1", "web", alice);
			devices.register("a2", "android", alice);

			devices.signOut(alice);

			assertThat(devices.listFor(alice)).isEmpty();
			assertThat(repository.count()).as("the tokens survive for whoever signs in next").isEqualTo(2);
		}
	}

	// ------------------------------------------------------------------ notify

	@Test
	@DisplayName("notifying with no devices attempts nothing")
	void notifyWithNoDevices() {
		assertThat(devices.notifyOrderStatus(alice, shop.getSlug(), "ORD-0001", "t", "b")).isZero();
	}

	@Test
	@DisplayName("notifying a null user attempts nothing")
	void notifyWithoutUser() {
		// A guest order has no account and therefore no devices. Sending nothing is correct, not a
		// failure worth logging as one.
		assertThat(devices.notifyOrderStatus(null, shop.getSlug(), "ORD-0001", "t", "b")).isZero();
	}
}