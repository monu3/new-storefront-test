package com.storefront.store.subscription;

/**
 * What a subscription can unlock (D4).
 *
 * <p><b>A closed enum, deliberately.</b> Shop types and product categories are closed enums (D17,
 * D18) for the same reason this is: an entitlement check that resolves a {@code String} will
 * eventually be handed {@code "use_paid_template"} -- singular -- by something, and the failure is
 * a shop quietly losing a feature it paid for with no error anywhere. The database has a CHECK-free
 * text column precisely because {@code FeatureKey.valueOf} is what validates it on the way in.
 *
 * <p>The {@link #key()} strings are persisted, so renaming a constant's Java name is safe but
 * changing one of these strings is a migration. That is the point: the value is a contract.
 *
 * <p><b>{@link #LIMITED} is not a feature.</b> It exists so a caller iterating the enum can tell
 * "a cap" from "a switch", and so {@link #isLimit()} has something to answer. It is never stored.
 */
public enum FeatureKey {

	/** How many products a shop may have. The one capped feature in v1. */
	MAX_PRODUCTS("max_products", true),

	/** Zero platform commission on accepted orders. Mirrors {@code shop.commission_plan}. */
	REMOVE_COMMISSION("remove_commission", false),

	/** The marketplace's paid theme templates, rather than only the free ones (D8). */
	USE_PAID_TEMPLATES("use_paid_templates", false),

	/** A shop's own CSS, sanitised (D15). */
	CUSTOM_CSS("custom_css", false),

	/** A shop's own domain rather than its store.app subdomain. */
	CUSTOM_DOMAIN("custom_domain", false),

	/** Creating and managing discount codes. */
	DISCOUNT_CODES("discount_codes", false),

	/** Bulk CSV product import. */
	CSV_IMPORT("csv_import", false),

	/** A faster path to human support. A promise, not a mechanism. */
	PRIORITY_SUPPORT("priority_support", false),

	/** Abandoned-cart reminders. */
	ABANDONED_CART_RECOVERY("abandoned_cart_recovery", false),

	/**
	 * Not a real feature.
	 *
	 * <p>Only ever returned by {@link #isLimit()}, never persisted and never looked up. Without it,
	 * "is this feature one with a cap?" would be answered by a hardcoded list somewhere else, and
	 * that list is exactly what goes stale.
	 */
	LIMITED("__limited", false);

	private final String key;
	private final boolean limit;

	FeatureKey(String key, boolean limit) {
		this.key = key;
		this.limit = limit;
	}

	/** The persisted string. Stable across renames of the Java constant. */
	public String key() {
		return key;
	}

	/** True when {@link #limitValue} means something. */
	public boolean isLimit() {
		return limit;
	}

	/**
	 * The feature for a persisted key, or null when the key is unknown.
	 *
	 * <p>Null rather than an exception. This is called while building a shop's entitlement set, and
	 * a plan row written by a future version of the app with a feature this build has never heard of
	 * should be skipped, not fatal: an unknown key cannot grant anything, and refusing to boot
	 * because of one would be a worse outcome than ignoring it.
	 *
	 * <p>The unknown key is still reported by the caller, so the plan screen can say "this plan
	 * grants a feature your version of the app does not know about" instead of silently showing
	 * fewer things than were sold.
	 */
	public static FeatureKey fromKey(String key) {
		if (key == null) {
			return null;
		}
		for (FeatureKey candidate : values()) {
			if (candidate.key.equals(key)) {
				return candidate;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return key;
	}
}
