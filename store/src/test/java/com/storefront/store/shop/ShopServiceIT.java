package com.storefront.store.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.support.TestConfig;
import com.storefront.store.wallet.WalletService;

/**
 * Sign-up and the verification gate.
 *
 * <p>The property that matters: a shop is invisible to the public until an admin approves it, and
 * approval is never automatic. That gate is the plan's answer to keeping fake shops out, so it is
 * asserted rather than assumed.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ShopServiceIT {

	@Autowired
	private ShopService shopService;

	@Autowired
	private VerificationService verification;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private WalletService wallet;

	@Autowired
	private com.storefront.store.support.TestFixtures fixtures;

	private AppUser newOwner() {
		return users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"owner-" + UUID.randomUUID(),
				"owner-" + UUID.randomUUID() + "@example.test",
				"Test Owner"));
	}

	@Nested
	@DisplayName("sign up")
	class SignUp {

		@Test
		@DisplayName("creates a DRAFT shop that is not yet public")
		void createsDraft() {
			AppUser owner = newOwner();

			Shop shop = shopService.signUp(owner, "Ravi's Pharmacy", ShopType.PHARMACY,
					"Family pharmacy", null);

			assertThat(shop.getId()).isNotNull();
			assertThat(shop.getStatus())
					.as("a new shop must not be visible before an admin approves it")
					.isEqualTo(Shop.Status.DRAFT);
			assertThat(shop.getPublishedAt()).isNull();
			assertThat(shop.isVerified()).isFalse();
			assertThat(shop.getType()).isEqualTo(ShopType.PHARMACY);
		}

		@Test
		@DisplayName("generates a readable slug from the shop name")
		void generatesSlug() {
			Shop shop = shopService.signUp(newOwner(), "Ravi's Pharmacy & Store",
					ShopType.PHARMACY, null, null);

			assertThat(shop.getSlug())
					.as("a readable slug matters: it appears in shared WhatsApp links")
					.isEqualTo("ravi-s-pharmacy-store");
		}

		@Test
		@DisplayName("honours a slug preference when given")
		void honoursPreference() {
			Shop shop = shopService.signUp(newOwner(), "Ravi's Pharmacy", ShopType.PHARMACY,
					null, "ravi-pharmacy");

			assertThat(shop.getSlug()).isEqualTo("ravi-pharmacy");
		}

		@Test
		@DisplayName("suffixes rather than failing when a slug is taken")
		void resolvesSlugCollision() {
			shopService.signUp(newOwner(), "Ravi's Pharmacy", ShopType.PHARMACY, null, "ravi-pharmacy");

			Shop second = shopService.signUp(newOwner(), "Ravi's Pharmacy", ShopType.PHARMACY,
					null, "ravi-pharmacy");

			assertThat(second.getSlug())
					.as("two shops can want the same short name; only one can have it")
					.isNotEqualTo("ravi-pharmacy")
					.startsWith("ravi-pharmacy-");
		}

		@Test
		@DisplayName("one owner has exactly one shop — signing up again updates")
		void idempotentByOwner() {
			AppUser owner = newOwner();
			Shop first = shopService.signUp(owner, "First Name", ShopType.GROCERY, null, null);
			Shop second = shopService.signUp(owner, "Second Name", ShopType.PHARMACY, null, null);

			assertThat(second.getId())
					.as("sign-up must update, not duplicate")
					.isEqualTo(first.getId());
			assertThat(second.getName()).isEqualTo("Second Name");
		}

		@Test
		@DisplayName("opens a wallet immediately, so balance is a lookup not a special case")
		void opensWallet() {
			Shop shop = shopService.signUp(newOwner(), "Wallet Test", ShopType.PHARMACY, null, null);

			assertThat(wallet.balanceMinorOf(shop.getId()))
					.as("every shop gets a wallet, funded or not")
					.contains(0L);
		}

		@Test
		@DisplayName("a non-Latin name still yields a usable slug")
		void nonLatinNameGetsSlug() {
			// A Nepali or Devanagari business name reduces to nothing under an ASCII fold.
			// An empty slug would be an unusable URL, so the id is used instead.
			Shop shop = shopService.signUp(newOwner(), "औषधालय", ShopType.PHARMACY, null, null);

			assertThat(shop.getSlug())
					.as("a shop must never end up with a blank slug")
					.isNotBlank()
					.matches("[a-z0-9-]+");
		}
	}

	@Nested
	@DisplayName("the verification gate")
	class Verification {

		@Test
		@DisplayName("a submitted shop is not yet public")
		void submittedNotYetPublic() {
			Shop shop = shopService.signUp(newOwner(), "Pending", ShopType.PHARMACY, null, null);

			Shop submitted = verification.submitForReview(shop, "Please review");

			assertThat(submitted.getStatus()).isEqualTo(Shop.Status.PENDING_REVIEW);
			assertThat(submitted.isVerified()).isFalse();
			assertThat(submitted.getPublishedAt()).isNull();
		}

		@Test
		@DisplayName("approval publishes the shop")
		void approvalPublishes() {
			Shop shop = shopService.signUp(newOwner(), "Approved", ShopType.PHARMACY, null, null);
			verification.submitForReview(shop, null);

			Shop verified = verification.verify(shop);

			assertThat(verified.getStatus()).isEqualTo(Shop.Status.VERIFIED);
			assertThat(verified.getPublishedAt())
					.as("publishing is what makes the shop appear in the gallery")
					.isNotNull();
			assertThat(verified.isVerified()).isTrue();
		}

		@Test
		@DisplayName("rejection records the reason and keeps the shop hidden")
		void rejectionRecordsReason() {
			Shop shop = shopService.signUp(newOwner(), "Rejected", ShopType.PHARMACY, null, null);
			verification.submitForReview(shop, null);

			Shop rejected = verification.reject(shop, "Business registration could not be verified");

			assertThat(rejected.getStatus()).isEqualTo(Shop.Status.REJECTED);
			assertThat(rejected.isVerified()).isFalse();
			assertThat(rejected.getVerificationNote())
					.as("the owner needs something actionable, not a bare rejection")
					.isEqualTo("Business registration could not be verified");
		}

		@Test
		@DisplayName("a rejected shop may resubmit")
		void rejectedMayResubmit() {
			Shop shop = shopService.signUp(newOwner(), "Retry", ShopType.PHARMACY, null, null);
			verification.submitForReview(shop, null);
			verification.reject(shop, "Missing documents");

			Shop resubmitted = verification.submitForReview(shop, "Documents uploaded");

			assertThat(resubmitted.getStatus()).isEqualTo(Shop.Status.PENDING_REVIEW);
		}

		@Test
		@DisplayName("an already-verified shop cannot be resubmitted")
		void verifiedCannotResubmit() {
			Shop shop = shopService.signUp(newOwner(), "Done", ShopType.PHARMACY, null, null);
			verification.submitForReview(shop, null);
			verification.verify(shop);

			assertThatThrownBy(() -> verification.submitForReview(shop, "again"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("cannot be submitted");
		}

		@Test
		@DisplayName("suspension hides a verified shop without deleting it")
		void suspensionHidesButPreserves() {
			Shop shop = shopService.signUp(newOwner(), "Suspended", ShopType.PHARMACY, null, null);
			verification.submitForReview(shop, null);
			verification.verify(shop);

			Shop suspended = verification.suspend(shop, "Fraud under investigation");

			assertThat(suspended.getStatus()).isEqualTo(Shop.Status.SUSPENDED);
			assertThat(suspended.isVerified()).isFalse();
			assertThat(shops.findById(shop.getId()))
					.as("products, orders and the ledger must survive a suspension")
					.isPresent();
		}

		@Test
		@DisplayName("the admin queue lists exactly the shops awaiting a decision")
		void queueIsAccurate() {
			Shop waiting = shopService.signUp(newOwner(), "Waiting", ShopType.PHARMACY, null, null);
			verification.submitForReview(waiting, null);

			Shop approved = shopService.signUp(newOwner(), "Approved", ShopType.GROCERY, null, null);
			verification.submitForReview(approved, null);
			verification.verify(approved);

			Shop stillDraft = shopService.signUp(newOwner(), "Draft", ShopType.FASHION, null, null);

			assertThat(verification.pendingReview())
					.as("only PENDING_REVIEW shops belong in the queue")
					.extracting(Shop::getId)
					.contains(waiting.getId())
					.doesNotContain(approved.getId(), stillDraft.getId());
		}
	}

	@Nested
	@DisplayName("profile updates")
	class ProfileUpdates {

		@Test
		@DisplayName("updates the mutable fields")
		void updatesProfile() {
			Shop shop = shopService.signUp(newOwner(), "Original", ShopType.PHARMACY, null, null);

			Shop updated = shopService.updateProfile(shop, "New Name", "New description",
					"https://logo", "https://cover", "https://favicon");

			assertThat(updated.getName()).isEqualTo("New Name");
			assertThat(updated.getDescription()).isEqualTo("New description");
			assertThat(updated.getLogoUrl()).isEqualTo("https://logo");
		}

		@Test
		@DisplayName("a blank name is ignored rather than erasing the shop name")
		void blankNameIgnored() {
			Shop shop = shopService.signUp(newOwner(), "Keep Me", ShopType.PHARMACY, null, null);

			Shop updated = shopService.updateProfile(shop, "   ", null, null, null, null);

			assertThat(updated.getName())
					.as("an empty submit must not blank out a shop")
					.isEqualTo("Keep Me");
		}

		@Test
		@DisplayName("profile updates cannot change the type")
		void profileUpdateCannotChangeType() {
			// The type is constrained by existing products, so it moves only through
			// ShopTypeMigrationService. A general profile update that could set it would be a
			// way around that check.
			Shop shop = shopService.signUp(newOwner(), "Fixed Type", ShopType.PHARMACY, null, null);

			shopService.updateProfile(shop, "Still Pharmacy", null, null, null, null);

			assertThat(shop.getType()).isEqualTo(ShopType.PHARMACY);
		}
	}
}
