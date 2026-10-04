package com.storefront.store.audience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The audience: its arithmetic, its privacy, and its ownership.
 *
 * <p>The interesting assertions are the ones about numbers an owner would act on. A summary that
 * silently reports the wrong figure is worse than no summary: the owner prices their next campaign
 * against it.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class AudienceServiceIT {

	@Autowired
	private AudienceService audience;

	@Autowired
	private CustomerRepository customers;

	@Autowired
	private com.storefront.store.identity.AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createShop("Audience Shop " + UUID.randomUUID(), ShopType.GROCERY);
		signIn(shop);
	}

	/**
	 * Puts the caller in the security context as this shop's owner.
	 *
	 * <p>A real {@link Jwt}, not a username/password token: {@code CurrentUser} reads the principal
	 * as a {@code Jwt} and returns null for anything else, so a substitute principal would make
	 * every call look anonymous and the tests would pass for the wrong reason.
	 *
	 * <p>Deliberately not mocking {@code TenantGuard}. The guard is what makes this screen
	 * single-tenant, and a test that replaced it would keep passing after the guard stopped
	 * filtering by shop — which is precisely the bug the cross-tenant case below exists to catch.
	 */
	private void signIn(Shop asShop) {
		// `sub` must be the owner's *Keycloak subject*, not their database id.
		// IdentitySyncService resolves the caller by `findByKeycloakSub(subject)` and creates a
		// brand-new AppUser when nothing matches -- so a UUID here silently produces a second user
		// who owns nothing, and TenantGuard then answers "This account does not own a shop" for
		// every call. The symptom points at the shop, not at the token.
		String subject = users.findById(asShop.getOwnerUserId())
				.map(com.storefront.store.identity.AppUser::getKeycloakSub)
				.orElseThrow(() -> new IllegalStateException("owner row missing"));

		Jwt jwt = new Jwt(
				"token-" + UUID.randomUUID(),
				Instant.now().minusSeconds(60),
				Instant.now().plusSeconds(600),
				Map.of("alg", "none"),
				Map.of(
						"sub", subject,
						"iss", "https://auth-master.yenyasoft.com/realms/ops",
						"email", "owner@example.test",
						"preferred_username", subject));
		TestSecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
						jwt, null, List.of()));
	}

	@Nested
	@DisplayName("the header figures")
	class Summary {

		@Test
		@DisplayName("count the whole shop, not one page")
		void sumsEveryCustomer() {
			customers.saveAndFlush(spent("a@example.test", 3, 100_00));
			customers.saveAndFlush(spent("b@example.test", 1, 50_00));
			customers.saveAndFlush(spent("c@example.test", 5, 200_00));

			var summary = audience.summary();

			assertThat(summary.customers()).isEqualTo(3);
			assertThat(summary.buyers()).isEqualTo(3);
			assertThat(summary.orders()).isEqualTo(9);
			assertThat(summary.spentMinor()).isEqualTo(350_00);
		}

		@Test
		@DisplayName("average order value divides by buyers, not by rows")
		void averageIgnoresNonBuyers() {
			customers.saveAndFlush(spent("a@example.test", 2, 300_00));
			customers.saveAndFlush(spent("b@example.test", 1, 100_00));
			// A row with no orders must not drag the average down: it never bought anything.
			customers.saveAndFlush(buyerless("never@example.test"));

			var summary = audience.summary();

			assertThat(summary.averageOrderMinor()).isEqualTo(200_00);
		}

		@Test
		@DisplayName("repeat rate is the share of buyers who came back")
		void repeatRate() {
			customers.saveAndFlush(spent("a@example.test", 4, 100_00));
			customers.saveAndFlush(spent("b@example.test", 1, 100_00));
			customers.saveAndFlush(spent("c@example.test", 1, 100_00));
			customers.saveAndFlush(spent("d@example.test", 2, 100_00));
			customers.saveAndFlush(spent("e@example.test", 1, 100_00));
			customers.saveAndFlush(spent("f@example.test", 1, 100_00));

			// a and d have more than one order, out of six buyers. Two different orders of the same
			// numerator give the same 33.3; seeded this way so the expectation is not simply "half",
			// which would pass with the numerator and denominator swapped in a way that happened to
			// divide evenly.
			assertThat(audience.summary().repeatRate()).isEqualTo(33.3);
		}

		@Test
		@DisplayName("a customer with no orders is not a repeat buyer")
		void nonBuyerIsNotRepeat() {
			customers.saveAndFlush(spent("a@example.test", 3, 100_00));
			customers.saveAndFlush(buyerless("never@example.test"));

			// One of one *buyers* came back. Counting the non-buyer as a non-repeat would give 50%.
			assertThat(audience.summary().repeatRate()).isEqualTo(100.0);
		}

		@Test
		@DisplayName("an empty shop reports zeroes rather than dividing by zero")
		void emptyShop() {
			var summary = audience.summary();

			assertThat(summary.customers()).isZero();
			assertThat(summary.buyers()).isZero();
			assertThat(summary.spentMinor()).isZero();
			assertThat(summary.averageOrderMinor()).isZero();
			assertThat(summary.repeatRate()).isZero();
		}
	}

	@Nested
	@DisplayName("sorting")
	class Sorting {

		@BeforeEach
		void seed() {
			customers.saveAndFlush(spent("big@example.test", 4, 900_00));
			customers.saveAndFlush(spent("small@example.test", 1, 10_00));
		}

		@Test
		@DisplayName("by spend puts the best customer first")
		void bySpend() {
			var page = audience.list(AudienceService.Sort.SPENT, 0, 20);
			assertThat(page.getContent()).first()
					.extracting(Customer::getEmail).isEqualTo("big@example.test");
		}

		@Test
		@DisplayName("by first seen is stable and returns everyone")
		void byFirstSeen() {
			var page = audience.list(AudienceService.Sort.FIRST_SEEN, 0, 20);
			assertThat(page.getTotalElements()).isEqualTo(2);
		}

		@Test
		@DisplayName("most recent does not put never-ordered customers first")
		void recentStillWorksWithNulls() {
			// The hand-written query exists because a derived "…DescNullsLast" is read by Spring
			// Data as a property name and fails at startup. This test is the reason it is hand-written.
			customers.saveAndFlush(buyerless("never@example.test"));

			var page = audience.list(AudienceService.Sort.RECENT, 0, 20);

			assertThat(page.getTotalElements()).isEqualTo(3);
			// The customer who never ordered sorts last, not first.
			assertThat(page.getContent().get(page.getContent().size() - 1))
					.extracting(Customer::getEmail).isEqualTo("never@example.test");
		}
	}

	@Nested
	@DisplayName("private notes")
	class Notes {

		@Test
		@DisplayName("are saved with their author and time")
		void stamped() {
			Customer customer = customers.saveAndFlush(spent("a@example.test", 1, 100_00));

			var updated = audience.annotate(customer.getId(), "  Always wants evening delivery.  ");

			assertThat(updated.getNotes()).isEqualTo("Always wants evening delivery.");
			assertThat(updated.getPrivateNoteUpdatedAt()).isNotNull();
			assertThat(updated.getPrivateNoteUpdatedBy()).isNotNull();
		}

		@Test
		@DisplayName("are cleared by saving a blank note")
		void cleared() {
			Customer customer = customers.saveAndFlush(spent("a@example.test", 1, 100_00));
			audience.annotate(customer.getId(), "something");

			var updated = audience.annotate(customer.getId(), "   ");

			assertThat(updated.getNotes()).isNull();
			assertThat(updated.getPrivateNoteUpdatedAt()).isNull();
		}
	}

	@Test
	@DisplayName("another shop's customer is a 404, not somebody else's data")
	void crossTenant() {
		Shop other = fixtures.createShop("Other " + UUID.randomUUID(), ShopType.GROCERY);
		Customer theirs = new Customer(other.getId(), "theirs@example.test");
		theirs.setTotalOrders(2);
		theirs.setTotalSpentMinor(500_00);
		customers.saveAndFlush(theirs);

		// Still signed in as `shop`. Reading theirs must fail rather than return their spend.
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> audience.get(theirs.getId()))
				.isInstanceOf(AudienceService.CustomerNotFoundException.class);
	}

	@Test
	@DisplayName("a display name falls back to the email's local part")
	void displayName() {
		Customer withName = spent("priya@example.test", 1, 100_00);
		withName.setFullName("Priya Sharma");
		Customer withoutName = spent("ram@example.test", 1, 100_00);

		assertThat(withName.displayName()).isEqualTo("Priya Sharma");
		// Never blank: a table cell must never have to invent a placeholder.
		assertThat(withoutName.displayName()).isEqualTo("ram");
	}

	// ------------------------------------------------------------------ fixtures

	private Customer spent(String email, int orders, long spentMinor) {
		Customer c = new Customer(shop.getId(), email);
		c.setTotalOrders(orders);
		c.setTotalSpentMinor(spentMinor);
		c.setLastOrderAt(java.time.Instant.now());
		return c;
	}

	private Customer buyerless(String email) {
		return new Customer(shop.getId(), email);
	}
}