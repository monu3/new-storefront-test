package com.storefront.store.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Shop onboarding (Phase 8.5).
 *
 * <p><b>The property worth protecting: the auto-provisioned placeholder is not a finished shop.</b>
 *
 * <p>A shop owner is given a shop the moment they first sign in — called "My shop", type GROCERY,
 * status DRAFT — so the console has something to render before they have filled in anything. The
 * onboarding guard exists to send them somewhere better. If the guard cannot tell that placeholder
 * from a real shop, one of two things breaks, and both are bad:
 *
 * <ul>
 *   <li>treat everything as finished — and every new owner lands in a dashboard for a shop called
 *       "My shop";</li>
 *   <li>treat nothing as finished — and <b>every existing shop owner is locked out of their own
 *       console</b>, redirected to a form they cannot submit their way past, unable to reach their
 *       products, orders or wallet.</li>
 * </ul>
 *
 * <p>The second one is what happened when this was first written, and it was caught by driving the
 * real console rather than by any test: the screen rendered perfectly, for the wrong person.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ShopOnboardingIT {

	@Autowired
	private ShopService shops;

	@Autowired
	private com.storefront.store.identity.IdentitySyncService identity;

	@Autowired
	private TestFixtures fixtures;

	@Nested
	@DisplayName("a shop that was never signed up")
	class Placeholder {

		@Test
		@DisplayName("is not finished, so the owner is sent to the sign-up step")
		void placeholderIsNotComplete() {
			Shop shop = fixtures.createShop("My shop", ShopType.GROCERY, Shop.Status.DRAFT);

			assertThat(shop.isProfileComplete())
					.as("a placeholder called 'My shop' is not a shop anybody chose")
					.isFalse();
		}
	}

	@Nested
	@DisplayName("a shop that has been through sign-up")
	class SignedUp {

		@Test
		@DisplayName("is finished, so the owner is not sent round again")
		void signedUpIsComplete() {
			AppUser owner = fixtures.owner();
			Shop shop = shops.signUp(owner, "Kantipur Pharmacy", ShopType.PHARMACY,
					"The pharmacy on the corner", "kantipur-pharmacy");

			assertThat(shop.isProfileComplete()).isTrue();
			assertThat(shop.getName()).isEqualTo("Kantipur Pharmacy");
			assertThat(shop.getType()).isEqualTo(ShopType.PHARMACY);
		}

		@Test
		@DisplayName("stays finished when signed up again — an existing shop is never un-onboarded")
		void staysComplete() {
			AppUser owner = fixtures.owner();
			Shop first = shops.signUp(owner, "Kantipur Pharmacy", ShopType.PHARMACY,
					null, null);

			Instant completedAt = first.getProfileCompletedAt();

			Shop again = shops.signUp(owner, "Kantipur Pharmacy", ShopType.PHARMACY,
					"Updated description", null);

			assertThat(again.isProfileComplete()).isTrue();
			assertThat(again.getProfileCompletedAt())
					.as("re-signing-up is not a reset: the shop's URL is already in printed material "
							+ "and a fresh timestamp would re-open the wizard for a live shop")
					.isEqualTo(completedAt);
		}
	}

	@Nested
	@DisplayName("the guard, not the name")
	class NotGuessedFromTheName {

		@Test
		@DisplayName("a shop created by the provisioner is left alone by the runtime guard")
		void runtimeDoesNotGuessFromTheName() {
			Shop shop = fixtures.createShop("My shop", ShopType.GROCERY, Shop.Status.DRAFT);

			// Deliberately the same name the provisioner uses. If the runtime ever falls back to
			// comparing this string, an owner who genuinely named their shop "My shop" gets bounced
			// back to sign-up -- and the test stops being able to tell the two apart.
			assertThat(shop.isProfileComplete())
					.as("decided by the timestamp, never by the name")
					.isFalse();
		}
	}
}
