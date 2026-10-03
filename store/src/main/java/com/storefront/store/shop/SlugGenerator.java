package com.storefront.store.shop;

import java.util.Locale;
import java.util.UUID;

/**
 * Builds a URL-safe shop slug.
 *
 * <p>A slug has to be readable, typeable by a customer on a phone, and stable — a shop's URL
 * appears on printed material and in shared WhatsApp messages, so it cannot be a random string.
 */
public final class SlugGenerator {

	private SlugGenerator() {
	}

	/**
	 * Derives a slug from a display name.
	 *
	 * <p>Nepali and other non-Latin names reduce to nothing under a naive ASCII fold, which would
	 * produce an empty slug. The shop's id is appended in that case so a URL always exists; a
	 * Nepali-named shop gets a working link rather than a broken one.
	 */
	public static String from(String name, UUID fallbackId) {
		String base = fold(name);

		if (base.isBlank()) {
			return "shop-" + fallbackId.toString().substring(0, 8);
		}

		// Trim to a sensible length so a long business name does not produce an unwieldy URL.
		if (base.length() > 50) {
			base = base.substring(0, 50).replaceAll("-+$", "");
		}
		return base;
	}

	/** Lowercase, ASCII-folded, hyphen-separated. */
	private static String fold(String input) {
		if (input == null) {
			return "";
		}
		String normalised = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
				// Strip combining marks, so "Café" becomes "Cafe" rather than "Caf".
				.replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+|-+$", "");
		return normalised;
	}

	/**
	 * A product slug, for URLs of the form {@code /{shop-slug}/{product-slug}}.
	 *
	 * <p>Separate from {@link #from} only in intent, not in algorithm: the product segment has
	 * to read well on its own because the shop slug already precedes it in the URL. The same
	 * non-Latin fallback applies, so a Nepali product name still gets a working link rather
	 * than an empty path segment.
	 */
	public static String productSlug(String preference, String productName, UUID fallbackId) {
		String base = prefer(preference, productName, fallbackId);
		// Shorter than a shop slug: the whole URL has to survive being typed on a phone and
		// pasted into a WhatsApp message.
		if (base.length() > 60) {
			base = base.substring(0, 60).replaceAll("-+$", "");
		}
		return base;
	}

	/**
	 * Applies an owner's slug preference, falling back to a generated one.
	 *
	 * <p>The preference is advisory. Uniqueness is the database's job — a UNIQUE constraint plus
	 * a retry on collision — because two shops can plausibly want the same short name and only
	 * one can have it.
	 */
	public static String prefer(String preference, String name, UUID fallbackId) {
		if (preference == null || preference.isBlank()) {
			return from(name, fallbackId);
		}
		String cleaned = fold(preference);
		return cleaned.isBlank() ? from(name, fallbackId) : cleaned;
	}
}
