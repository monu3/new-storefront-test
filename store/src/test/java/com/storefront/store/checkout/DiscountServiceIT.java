package com.storefront.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Owner-facing discounts: what can be created, and what a code does once it exists.
 *
 * <p>The arithmetic itself belongs to {@link Discount} and is asserted in the checkout suite; what
 * is worth testing here is the rule set an owner can break — a percentage over 100, a cap above the
 * minimum, a window that ends before it starts — and the fact that a second shop cannot see or
 * touch the first shop's codes.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class DiscountServiceIT {

	@Autowired
	private DiscountService service;

	@Autowired
	private DiscountRepository discounts;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AppUserRepository users;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createShop("Discount Shop " + UUID.randomUUID(), ShopType.GROCERY);
		signIn(shop);
	}

	private void signIn(Shop asShop) {
		String subject = users.findById(asShop.getOwnerUserId())
				.map(AppUser::getKeycloakSub)
				.orElseThrow(() -> new IllegalStateException("owner row missing"));
		Jwt jwt = new Jwt("t-" + UUID.randomUUID(), Instant.now().minusSeconds(60),
				Instant.now().plusSeconds(600), java.util.Map.of("alg", "none"),
				java.util.Map.of("sub", subject,
						"iss", "https://auth-master.yenyasoft.com/realms/ops",
						"email", "owner@example.test"));
		TestSecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
						jwt, null, java.util.List.of()));
	}

	private DiscountService.Draft draft(String code, String name, Discount.Type type, long value) {
		return new DiscountService.Draft(code, name, type, value, null, null, null, null, null, null,
				true);
	}

	// ------------------------------------------------------------------ create

	@Nested
	@DisplayName("creating")
	class Creating {

		@Test
		@DisplayName("stores a code discount")
		void code() {
			var created = service.create(draft("FESTIVAL", "Festival sale", Discount.Type.PERCENT, 1000));

			assertThat(created.getCode()).isEqualTo("FESTIVAL");
			assertThat(created.getType()).isEqualTo(Discount.Type.PERCENT);
			assertThat(created.isActive()).isTrue();
		}

		@Test
		@DisplayName("uppercases and trims a code")
		void normalisesCode() {
			// Codes are compared case-insensitively by the citext column, so storing them as typed
			// would make the owner's own list inconsistent with what a customer can type.
			var created = service.create(draft("  festival20 ", "Festival", Discount.Type.PERCENT, 2000));
			assertThat(created.getCode()).isEqualTo("FESTIVAL20");
		}

		@Test
		@DisplayName("treats a blank code as an automatic discount")
		void blankCodeIsAutomatic() {
			var created = service.create(draft("   ", "Automatic 5%", Discount.Type.PERCENT, 500));

			assertThat(created.getCode()).isNull();
			assertThat(discounts.findByShopIdAndActiveTrueAndCodeIsNullOrderByIdAsc(shop.getId()))
					.hasSize(1);
		}

		@Test
		@DisplayName("rejects a percentage over 100")
		void percentageCap() {
			// 10000 basis points is 100%. Above that the order total goes negative.
			assertThatThrownBy(() -> service.create(draft("TOOMUCH", "Too much", Discount.Type.PERCENT, 10_001)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot exceed 100%");
		}

		@Test
		@DisplayName("accepts exactly 100%")
		void percentageExactly100() {
			assertThat(service.create(draft("FREE", "Everything free", Discount.Type.PERCENT, 10_000)).getValue())
					.isEqualTo(10_000);
		}

		@Test
		@DisplayName("refuses a free-shipping discount that carries a value")
		void freeShippingWithValue() {
			// Storing one suggests it does something it does not.
			assertThatThrownBy(() -> service.create(draft("SHIP", "Free ship", Discount.Type.FREE_SHIPPING, 500)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("takes no value");
		}

		@Test
		@DisplayName("refuses a cap that no order could ever satisfy")
		void capAboveMinimum() {
			// Minimum 1000 and cap 5000: an order must first reach 1000, by which point a 5000 cap
			// is already exceeded. The discount would exist on the storefront and never work.
			DiscountService.Draft impossible = new DiscountService.Draft("X", "Impossible",
					Discount.Type.PERCENT, 1000, 1000L, 5000L, null, null, null, null, true);

			assertThatThrownBy(() -> service.create(impossible))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no order qualifies");
		}

		@Test
		@DisplayName("refuses a window that ends before it starts")
		void backwardsWindow() {
			Instant now = Instant.now();
			DiscountService.Draft backwards = new DiscountService.Draft("W", "Backwards",
					Discount.Type.PERCENT, 1000, null, null, null, null, now.plusSeconds(3600), now, true);

			assertThatThrownBy(() -> service.create(backwards))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must be after the start");
		}

		@Test
		@DisplayName("refuses a duplicate code in the same shop")
		void duplicateCode() {
			service.create(draft("SAME", "First", Discount.Type.PERCENT, 1000));

			assertThatThrownBy(() -> service.create(draft("same", "Second", Discount.Type.PERCENT, 1000)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("already has a discount with the code");
		}
	}

	// ------------------------------------------------------------------ update

	@Nested
	@DisplayName("updating")
	class Updating {

		@Test
		@DisplayName("changes the name and the rule")
		void changesRule() {
			var created = service.create(draft("EDIT", "Before", Discount.Type.PERCENT, 1000));

			var updated = service.update(created.getId(),
					new DiscountService.Draft(null, "After", Discount.Type.FIXED, 2500L, null, null,
							null, null, null, null, true));

			assertThat(updated.getName()).isEqualTo("After");
			assertThat(updated.getType()).isEqualTo(Discount.Type.FIXED);
			assertThat(updated.getValue()).isEqualTo(2500L);
		}

		@Test
		@DisplayName("does not trip over the discount's own code")
		void selfExclusion() {
			// A rename that re-sends the same code must not look like a duplicate. Without the
			// self-exclusion every edit of a coded discount would fail.
			var created = service.create(draft("KEEP", "Before", Discount.Type.PERCENT, 1000));

			var updated = service.update(created.getId(),
					new DiscountService.Draft("KEEP", "Renamed", Discount.Type.PERCENT, 2000, null, null,
							null, null, null, null, true));

			assertThat(updated.getName()).isEqualTo("Renamed");
			assertThat(updated.getCode()).isEqualTo("KEEP");
		}

		@Test
		@DisplayName("pausing keeps the usage count")
		void pausePreservesUsage() {
			// A campaign redeployed next season must not let everyone who already used it use it
			// again, which is what deleting would do.
			var created = service.create(new DiscountService.Draft("LIMITED", "Limited",
					Discount.Type.PERCENT, 1000, null, null, 100, null, null, null, true));
			created.recordUse();
			created.recordUse();

			var paused = service.setActive(created.getId(), false);

			assertThat(paused.isActive()).isFalse();
			assertThat(paused.getUsageUsed()).isEqualTo(2);
		}
	}

	// ------------------------------------------------------------------ isolation

	@Test
	@DisplayName("another shop's discount is a 404, not another shop's campaign")
	void crossTenant() {
		Shop other = fixtures.createShop("Other " + UUID.randomUUID(), ShopType.GROCERY);
		var theirs = new Discount(other.getId(), "THEIRS", "Their sale", Discount.Type.PERCENT, 1000);
		discounts.saveAndFlush(theirs);

		// Still signed in as `shop`. Reading or editing theirs must fail rather than leak the code.
		assertThatThrownBy(() -> service.get(theirs.getId()))
				.isInstanceOf(DiscountService.DiscountNotFoundException.class);
		assertThatThrownBy(() -> service.setActive(theirs.getId(), false))
				.isInstanceOf(DiscountService.DiscountNotFoundException.class);
	}

	@Test
	@DisplayName("two shops may reuse the same code")
	void sameCodeDifferentShops() {
		// Code uniqueness is per shop. A national code reused by a competitor is not this shop's
		// problem, and forbidding it would be a strange platform rule.
		service.create(draft("SHARED", "Mine", Discount.Type.PERCENT, 1000));

		Shop other = fixtures.createShop("Other " + UUID.randomUUID(), ShopType.GROCERY);
		signIn(other);
		var theirs = service.create(draft("SHARED", "Theirs", Discount.Type.PERCENT, 2000));

		assertThat(theirs.getCode()).isEqualTo("SHARED");
	}
}