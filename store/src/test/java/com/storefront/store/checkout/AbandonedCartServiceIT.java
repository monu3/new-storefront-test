package com.storefront.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.support.TestConfig;

/**
 * Abandoned-cart reminders (Phase 8).
 *
 * <p><b>The property worth protecting is that a cart is reminded about at most once.</b> The sweep
 * claims rows in the same statement that selects them, so an overlapping sweep — or two application
 * instances — cannot send the same email twice. And a second email to the same cart is the point
 * where a reminder becomes harassment and a shop's mail domain gets marked as spam.
 *
 * <p><b>The sweep is driven directly rather than waited for.</b> The scheduler is a cron string and
 * a test that waits for it is a test that takes an hour; {@code sweepOnce()} is the same code without
 * the clock, which is the only reason it is a separate method.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class AbandonedCartServiceIT {

	@Autowired
	private AbandonedCartService carts;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AppUserRepository users;

	private UUID customerId;

	@BeforeEach
	void setUp() {
		customerId = users.saveAndFlush(new AppUser(AppUser.Realm.CUSTOMERS,
				"sub-" + UUID.randomUUID(),
				"cart-" + UUID.randomUUID() + "@example.test",
				"Cart Owner")).getId();
	}

	// ------------------------------------------------------------------ recording

	@Nested
	@DisplayName("recording a cart")
	class Recording {

		@Test
		@DisplayName("upserts, so a refreshed cart is one row and not a history of abandoned ones")
		void upserts() {
			carts.touch(customerId, null, 50_000, 2);
			carts.touch(customerId, null, 75_000, 3);

			assertThat(carts.forCustomer(customerId, PageRequest.of(0, 10)).getContent())
					.hasSize(1);
			assertThat(carts.forCustomer(customerId, PageRequest.of(0, 10)).getContent().get(0).itemCount())
					.isEqualTo(3);
		}

		@Test
		@DisplayName("an empty cart is not recorded — that is a finished basket, not an abandoned one")
		void ignoresAnEmptyCart() {
			carts.touch(customerId, null, 0, 0);

			assertThat(carts.forCustomer(customerId, PageRequest.of(0, 10)).getContent()).isEmpty();
		}

		@Test
		@DisplayName("a guest cart is keyed on a browser key, so it survives without an account")
		void keysGuestCartsOnTheBrowserKey() {
			String key = "anon-" + UUID.randomUUID();

			carts.touch(null, key, 20_000, 1);

			Integer rows = jdbc.queryForObject(
					"SELECT count(*) FROM abandoned_cart WHERE anonymous_key = ?", Integer.class, key);

			assertThat(rows).isEqualTo(1);
		}
	}

	// ------------------------------------------------------------------ the sweep

	@Nested
	@DisplayName("the sweep")
	class Sweep {

		@Test
		@DisplayName("ignores a cart somebody is still using")
		void ignoresRecentCarts() {
			carts.touch(customerId, null, 50_000, 2);
			age(customerId, Instant.now().minus(5, ChronoUnit.MINUTES));

			assertThat(carts.sweepOnce())
					.as("a cart touched five minutes ago is not abandoned, and emailing about it is "
							+ "the fastest way to get a shop's reminders switched off")
					.isZero();
		}

		@Test
		@DisplayName("reminds once about a cart nobody has touched")
		void remindsOnce() {
			carts.touch(customerId, null, 50_000, 2);
			age(customerId, Instant.now().minus(3, ChronoUnit.HOURS));

			assertThat(carts.sweepOnce()).isEqualTo(1);
			assertThat(carts.sweepOnce())
					.as("a second email to the same cart is where a reminder becomes harassment")
					.isZero();
		}

		@Test
		@DisplayName("a recovered cart is never reminded about")
		void neverRemindsAboutARecoveredCart() {
			carts.touch(customerId, null, 50_000, 2);
			age(customerId, Instant.now().minus(3, ChronoUnit.HOURS));
			carts.markRecovered(customerId);

			assertThat(carts.sweepOnce())
					.as("emailing somebody 'you left something behind' about an order they have "
							+ "already placed is what gets a shop's mail marked as spam")
					.isZero();
		}

		@Test
		@DisplayName("a guest cart is claimed but not emailed — nobody to email")
		void skipsGuestCarts() {
			String key = "anon-" + UUID.randomUUID();
			carts.touch(null, key, 20_000, 1);
			ageByKey(key, Instant.now().minus(3, ChronoUnit.HOURS));

			assertThat(carts.sweepOnce()).isZero();
		}

		@Test
		@DisplayName("a cart that became an order stops being a candidate")
		void recoveredCartStops() {
			carts.touch(customerId, null, 50_000, 2);
			age(customerId, Instant.now().minus(3, ChronoUnit.HOURS));
			carts.markRecovered(customerId);

			var page = carts.forCustomer(customerId, PageRequest.of(0, 10));

			assertThat(page.getContent().get(0).recovered()).isTrue();
			assertThat(page.getContent().get(0).remindedAt()).isNull();
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Backdates a cart so the sweep's cutoff sees it as abandoned. */
	private void age(UUID customerId, Instant lastActiveAt) {
		jdbc.update("UPDATE abandoned_cart SET last_active_at = ? WHERE customer_user_id = ?",
				java.time.OffsetDateTime.ofInstant(lastActiveAt, java.time.ZoneOffset.UTC), customerId);
	}

	private void ageByKey(String key, Instant lastActiveAt) {
		jdbc.update("UPDATE abandoned_cart SET last_active_at = ? WHERE anonymous_key = ?",
				java.time.OffsetDateTime.ofInstant(lastActiveAt, java.time.ZoneOffset.UTC), key);
	}
}