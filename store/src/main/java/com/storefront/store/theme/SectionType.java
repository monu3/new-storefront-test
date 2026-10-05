package com.storefront.store.theme;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The closed set of section types (D8).
 *
 * <p><b>This enum and {@code web/packages/theme-engine/src/schema.ts} must contain exactly the
 * same values.</b> They are two halves of one contract: the schema is what the editor writes and
 * the renderer reads, and this enum is what the server accepts and validates. A type added to one
 * and not the other produces a template the server rejects, or worse, a theme the renderer turns
 * into "unknown section" for every customer.
 *
 * <p>That drift is prevented by a test rather than a comment:
 * {@code SectionRegistrySyncIT} reads the TypeScript file and asserts the two sets are equal. It
 * is a slightly unusual test — reading a source file from another repository — and it earns its
 * place, because the failure it catches is silent on both sides.
 *
 * <p>The order matches the TypeScript array so a diff between the two files is readable.
 */
public enum SectionType {

	ANNOUNCEMENT_BAR("announcement-bar"),
	SITE_HEADER("site-header"),
	HERO("hero"),
	CATEGORY_GRID("category-grid"),
	PRODUCT_CAROUSEL("product-carousel"),
	PRODUCT_GRID("product-grid"),
	COLLECTION_LIST("collection-list"),
	PROMO_BANNER("promo-banner"),
	IMAGE_WITH_TEXT("image-with-text"),
	RICH_TEXT("rich-text"),
	VIDEO("video"),
	GALLERY("gallery"),
	TESTIMONIALS("testimonials"),
	FAQ("faq"),
	NEWSLETTER("newsletter"),
	COUNTDOWN("countdown"),
	TRUST_BADGES("trust-badges"),
	FEATURE_LIST("feature-list"),
	CTA_BANNER("cta-banner"),
	MAP("map"),
	SOCIAL_FEED("social-feed"),
	DIVIDER("divider"),
	SPACER("spacer"),
	HTML("html"),
	SITE_FOOTER("site-footer"),
	UNKNOWN_SECTION("unknown-section");

	/** The wire value, which is what appears in the theme JSON. */
	private final String wireName;

	SectionType(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	/** Resolves a wire value, or null when it is not one of ours. */
	public static SectionType fromWireName(String value) {
		if (value == null) {
			return null;
		}
		for (SectionType type : values()) {
			if (type.wireName.equals(value)) {
				return type;
			}
		}
		return null;
	}

	/** Every wire name, in declaration order. */
	public static Set<String> wireNames() {
		Set<String> names = new LinkedHashSet<>();
		Arrays.stream(values()).forEach(t -> names.add(t.wireName));
		return names;
	}
}
