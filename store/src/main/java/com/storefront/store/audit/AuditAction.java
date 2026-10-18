package com.storefront.store.audit;

/**
 * Every privileged action the platform records (Phase 7).
 *
 * <p><b>A closed enum, for the same reason {@code FeatureKey} is one.</b> An audit log's whole
 * value is that a reader can trust its vocabulary: "show me every suspension" is a query against
 * {@link #SHOP_SUSPEND}, and it silently misses anything misspelled. A string column turns a typo
 * into a hole in the record that nobody notices until they need it.
 *
 * <p>Adding a value is a code change on purpose. A new privileged action that nobody thought to
 * classify should not be one keystroke away from being shipped unrecorded.
 *
 * <p>The names read {@code ENTITY_VERB} and are what a filter groups by, so they are deliberately
 * not sentences — {@code SHOP_SUSPEND}, not {@code "A_SHOP_WAS_SUSPENDED"}.
 */
public enum AuditAction {

	// --- shops
	SHOP_VERIFY,
	SHOP_REJECT,
	SHOP_SUSPEND,
	SHOP_REINSTATE,
	/** Putting a shop on the front page of the gallery. */
	SHOP_FEATURE,
	SHOP_UNFEATURE,
	SHOP_SETTINGS_UPDATE,

	// --- people
	USER_SUSPEND,
	USER_REINSTATE,
	/** Cross-realm: an operator acting on somebody in the other realm. */
	USER_DELETE,

	// --- money
	WALLET_TOPUP_APPROVE,
	WALLET_TOPUP_REJECT,
	/**
	 * A manual balance change that is not a top-up.
	 *
	 * <p>Separate from the top-up pair because it is the one that needs the most scrutiny: it is the
	 * only way to change a balance without money arriving, so it is the first thing an investigator
	 * looks for.
	 */
	WALLET_ADJUST,
	SUBSCRIPTION_APPROVE,
	SUBSCRIPTION_REJECT,

	// --- plans and entitlements
	PLAN_CREATE,
	PLAN_UPDATE,
	PLAN_WITHDRAW,
	PLAN_ENTITLEMENTS_SET,

	// --- commerce
	/** A shop's commission rate frozen differently from the platform default. */
	COMMISSION_RATE_CHANGE,

	/**
	 * Returns and refunds (Phase 8, D14).
	 *
	 * <p>Four actions rather than one, because they are four different decisions. "A return was
	 * requested" and "a return was refunded" are separate claims, and a log that recorded only the
	 * first would be unable to answer the only question that matters about a dispute: was the money
	 * ever sent back.
	 *
	 * <p>In {@code COMMERCE} rather than {@code MONEY}, because {@code MONEY} here means the wallet
	 * and subscriptions. A return is about an order; it merely happens to move money on the way.
	 */
	ORDER_RETURN_REQUESTED,
	ORDER_RETURN_APPROVED,
	ORDER_RETURN_REJECTED,
	ORDER_REFUNDED,

	// --- the platform
	SETTING_UPDATE,
	THEME_TEMPLATE_PUBLISH,
	THEME_TEMPLATE_WITHDRAW,
	FEATURED_SHOPS_SET,

	// --- automation
	/**
	 * The expiry sweep closing a lapsed subscription.
	 *
	 * <p>Recorded with a null actor, so the log distinguishes "an admin did this" from "the
	 * scheduler did this". That distinction is the whole reason a subscription can be revoked
	 * without anybody having touched it.
	 */
	SUBSCRIPTION_EXPIRED;

	/**
	 * The group an operator filters by, so the admin screen can offer "shops" and "money" rather than
	 * a flat list of thirty actions.
	 */
	public Group group() {
		return switch (this) {
			case SHOP_VERIFY, SHOP_REJECT, SHOP_SUSPEND, SHOP_REINSTATE,
					SHOP_FEATURE, SHOP_UNFEATURE, SHOP_SETTINGS_UPDATE -> Group.SHOPS;
			case USER_SUSPEND, USER_REINSTATE, USER_DELETE -> Group.PEOPLE;
			case WALLET_TOPUP_APPROVE, WALLET_TOPUP_REJECT, WALLET_ADJUST,
					SUBSCRIPTION_APPROVE, SUBSCRIPTION_REJECT -> Group.MONEY;
			case PLAN_CREATE, PLAN_UPDATE, PLAN_WITHDRAW, PLAN_ENTITLEMENTS_SET -> Group.PLANS;
			case COMMISSION_RATE_CHANGE, ORDER_RETURN_REQUESTED, ORDER_RETURN_APPROVED,
					ORDER_RETURN_REJECTED, ORDER_REFUNDED -> Group.COMMERCE;
			case SETTING_UPDATE, THEME_TEMPLATE_PUBLISH, THEME_TEMPLATE_WITHDRAW,
					FEATURED_SHOPS_SET -> Group.PLATFORM;
			case SUBSCRIPTION_EXPIRED -> Group.AUTOMATION;
		};
	}

	/** What the reader is looking at, which is not the same as who did it. */
	public enum Group {
		SHOPS, PEOPLE, MONEY, PLANS, COMMERCE, PLATFORM, AUTOMATION
	}

	/**
	 * Whether this action must carry a reason.
	 *
	 * <p>The ones that take something away from somebody. "An admin suspended a shop" without a
	 * reason helps neither the shop, which cannot tell what to fix, nor the next admin, who has to
	 * reverse-engineer it.
	 */
	public boolean requiresReason() {
		return switch (this) {
			case SHOP_SUSPEND, SHOP_REJECT, USER_SUSPEND, USER_DELETE,
					WALLET_TOPUP_REJECT, WALLET_ADJUST, SUBSCRIPTION_REJECT -> true;
			default -> false;
		};
	}

	/** A verb for the one-line summary, so summaries read as sentences rather than key dumps. */
	public String verb() {
		return switch (this) {
			case SHOP_VERIFY -> "verified";
			case SHOP_REJECT -> "rejected";
			case SHOP_SUSPEND -> "suspended";
			case SHOP_REINSTATE -> "reinstated";
			case SHOP_FEATURE -> "featured";
			case SHOP_UNFEATURE -> "unfeatured";
			case SHOP_SETTINGS_UPDATE -> "updated settings for";
			case USER_SUSPEND -> "suspended";
			case USER_REINSTATE -> "reinstated";
			case USER_DELETE -> "deleted";
			case WALLET_TOPUP_APPROVE -> "approved a top-up for";
			case WALLET_TOPUP_REJECT -> "rejected a top-up for";
			case WALLET_ADJUST -> "adjusted the wallet of";
			case SUBSCRIPTION_APPROVE -> "started a subscription for";
			case SUBSCRIPTION_REJECT -> "rejected a subscription for";
			case PLAN_CREATE -> "created the plan";
			case PLAN_UPDATE -> "updated the plan";
			case PLAN_WITHDRAW -> "withdrew the plan";
			case PLAN_ENTITLEMENTS_SET -> "set entitlements on";
			case ORDER_RETURN_REQUESTED -> "requested a return on";
			case ORDER_RETURN_APPROVED -> "approved a return on";
			case ORDER_RETURN_REJECTED -> "rejected a return on";
			case ORDER_REFUNDED -> "refunded";
			case COMMISSION_RATE_CHANGE -> "changed the commission rate";
			case SETTING_UPDATE -> "changed the platform setting";
			case THEME_TEMPLATE_PUBLISH -> "published the template";
			case THEME_TEMPLATE_WITHDRAW -> "withdrew the template";
			case FEATURED_SHOPS_SET -> "set the featured shops to";
			case SUBSCRIPTION_EXPIRED -> "expired the subscription of";
		};
	}
}
