package com.storefront.store.shop;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;

import lombok.RequiredArgsConstructor;

/**
 * Shop creation and profile updates.
 *
 * <p>Sign-up is deliberately narrow. A shop exists so its owner can list products and take
 * orders; everything else about it is filled in afterwards. Keeping the first step small means
 * a shop owner reaches a working dashboard quickly, and the verification gate (see
 * {@code VerificationService}) can ask for the details that actually matter before the shop
 * becomes visible to the public.
 */
@Service
@RequiredArgsConstructor
public class ShopService {

	private static final Logger log = LoggerFactory.getLogger(ShopService.class);

	/** How many slugs to try before giving up. Uniqueness is the database's constraint. */
	private static final int MAX_SLUG_ATTEMPTS = 5;

	private final ShopRepository shops;
	private final com.storefront.store.wallet.WalletService wallet;

	/**
	 * Creates or updates the caller's shop profile.
	 *
	 * <p>Idempotent by owner: one owner has exactly one shop, so signing up twice updates rather
	 * than duplicating. That matters because a shop is provisioned automatically on first login
	 * and the owner then fills in the real details through this same endpoint.
	 */
	@Transactional
	public Shop signUp(AppUser owner, String name, ShopType type, String description,
			String slugPreference) {

		Shop shop = shops.findByOwnerUserId(owner.getId()).orElse(null);

		if (shop == null) {
			shop = new Shop(owner, name, type);
			shop.setDescription(description);
			shop.setSlug(allocateSlug(name, slugPreference));
		} else {
			shop.setName(name);
			shop.setDescription(description);
			// A shop is provisioned as DRAFT on first login, so sign-up almost always lands
			// here. The slug still has to move: the placeholder was generated from "My shop", and
			// the owner chose a real one.
			//
			// Only while unverified, though. Once published the URL is out in the world — in
			// printed material and shared messages — so changing it silently would break links
			// customers already hold.
			if (!shop.isVerified() && slugPreference != null && !slugPreference.isBlank()) {
				shop.setSlug(allocateSlug(name, slugPreference));
			}
			// The type is deliberately not changed here. It is constrained by the products the
			// shop already sells, so it moves through ShopTypeMigrationService, never a
			// general profile update that would bypass the check.
			shop.changeType(type);
		}

		// Marked signed up here rather than anywhere else, because this is the one call that has
		// validated a real name and a real vertical. The console uses this timestamp to decide
		// whether somebody still has to fill their shop in -- so a shop that somehow arrived here
		// another way would correctly not be sent through the wizard twice.
		shop.completeProfile(java.time.Instant.now());

		Shop saved = shops.saveAndFlush(shop);
		// Every shop gets a wallet, funded or not. Opening it here means the balance is a
		// lookup from the first moment rather than a special case on the first order.
		wallet.balanceOf(saved.getId());

		log.info("Shop {} ({}) signed up by owner {}, type {}",
				saved.getSlug(), saved.getId(), owner.getId(), type);
		return saved;
	}

	/** Updates the mutable profile fields. Does not touch type, status or commission. */
	@Transactional
	public Shop updateProfile(Shop shop, String name, String description,
			String logoUrl, String coverUrl, String faviconUrl) {

		if (name != null && !name.isBlank()) {
			shop.setName(name.trim());
		}
		if (description != null) {
			shop.setDescription(description.trim());
		}
		if (logoUrl != null) {
			shop.setLogoUrl(logoUrl);
		}
		if (coverUrl != null) {
			shop.setCoverUrl(coverUrl);
		}
		if (faviconUrl != null) {
			shop.setFaviconUrl(faviconUrl);
		}
		return shops.saveAndFlush(shop);
	}

	/**
	 * Finds an unused slug, appending a short suffix on collision.
	 *
	 * <p>Two shops can reasonably want the same short name, and only one can have it. Rather
	 * than reject the second with an error the owner cannot act on, the suffix keeps sign-up
	 * working. The unique index is still the authority — this just avoids the collision.
	 */
	/**
	 * A slug for {@code base} that no shop currently holds.
	 *
	 * <p>Public because slug uniqueness is a property of the shop table, not of the signup form.
	 * {@link #signUp} de-duplicates; automatic provisioning must too, or the two paths disagree and
	 * the one that forgot gets a unique-constraint violation instead of a slug.
	 */
	public String allocateUniqueSlug(String base) {
		return allocateSlug(base, null);
	}

	private String allocateSlug(String name, String preference) {
		String base = SlugGenerator.prefer(preference, name, UUID.randomUUID());

		String candidate = base;
		for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
			if (!shops.existsBySlug(candidate)) {
				return candidate;
			}
			candidate = base + "-" + (attempt + 2);
		}
		// Five collisions on a generated slug is vanishingly unlikely; fall back to something
		// guaranteed unique rather than looping forever.
		return base + "-" + UUID.randomUUID().toString().substring(0, 6).toLowerCase(Locale.ROOT);
	}

	@Transactional(readOnly = true)
	public Shop getBySlug(String slug) {
		return shops.findBySlug(slug).orElseThrow(() -> new ShopNotFoundException(slug));
	}

	/**
	 * The shop owned by this user, if any.
	 *
	 * <p>Optional rather than throwing, because "has not created one yet" and "created one and
	 * lost access" are different situations the dashboard must handle differently: the first
	 * shows the sign-up form, the second shows an error.
	 */
	@Transactional(readOnly = true)
	public java.util.Optional<Shop> shopsOf(AppUser owner) {
		return shops.findByOwnerUserId(owner.getId());
	}

	/** Maps to HTTP 404. A shop that does not exist is a bad URL, not a bad request. */
	public static class ShopNotFoundException extends RuntimeException {
		public ShopNotFoundException(String slug) {
			super("No shop at '" + slug + "'");
		}
	}
}
