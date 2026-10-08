package com.storefront.store.subscription;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Answers "may this shop do X?" (D4).
 *
 * <p><b>Resolution is two layers, materialised rows over the FREE plan.</b> A shop with a running
 * subscription has its plan's entitlements copied into {@code shop_entitlement} on activation, and
 * those rows shadow everything. Any feature the plan says nothing about falls through to what the
 * FREE plan grants, which is normally "nothing".
 *
 * <p>The FREE fallback is the part that is easy to get wrong. If the FREE plan were represented only
 * by "no rows", then a shop that had subscribed once and let it lapse would have its 25-product cap
 * deleted along with its paid entitlements and would then be able to list without limit — the
 * opposite of what lapsing is supposed to do. Keeping the free tier as a real plan with real
 * entitlements means the baseline is data, so raising the free cap is a migration rather than a
 * code change, and a fresh database and a migrated one cannot disagree about what "free" means.
 *
 * <p><b>Nothing here trusts {@code shop.commission_plan}.</b> That column is the fast path the
 * order-accept code reads, and it is set from this service's conclusions at activation and expiry —
 * it is not a second source of truth that could drift. {@link #commissionsWaived} is the authority;
 * {@code OrderService} reads the column because it is already holding an order transaction and must
 * not run a subscription query per order.
 *
 * <p><b>Expiry is tested on every read.</b> No method here asks "is the status ACTIVE?" — it asks
 * whether the grant's own window is still open. A stopped scheduler shortens nothing and extends
 * nothing.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

	private static final String FREE_PLAN_CODE = "FREE";

	private final ShopEntitlementRepository entitlements;
	private final PlanEntitlementRepository planEntitlements;
	private final SubscriptionPlanRepository plans;

	// ------------------------------------------------------------------ reads

	/**
	 * Whether a shop currently holds a feature.
	 *
	 * <p>Cheap enough for a filter or a template decision, and the method every other one here
	 * ultimately calls.
	 */
	@Transactional(readOnly = true)
	public boolean has(UUID shopId, FeatureKey feature) {
		return resolve(shopId, feature, Instant.now()).granted();
	}

	/**
	 * A feature's cap for a shop, or null when uncapped.
	 *
	 * <p>Null means "no limit", which is different from "no grant". A caller that only checks null
	 * would treat a shop without the feature as having an unlimited one.
	 */
	@Transactional(readOnly = true)
	public Optional<Long> limitOf(UUID shopId, FeatureKey feature) {
		return Optional.ofNullable(resolve(shopId, feature, Instant.now()).limitValue());
	}

	/**
	 * Everything a shop may do, in one pass.
	 *
	 * <p>One call rather than N: the pricing comparison screen asks about every feature at once,
	 * and asking per feature is a query per feature for a page that shows them all.
	 */
	@Transactional(readOnly = true)
	public Map<FeatureKey, Grant> all(UUID shopId) {
		return all(shopId, Instant.now());
	}

	@Transactional(readOnly = true)
	public Map<FeatureKey, Grant> all(UUID shopId, Instant now) {
		// Both layers loaded once. The fallback is resolved by hand rather than through
		// resolve() per feature, which would re-read the materialised rows for every feature.
		Map<String, Grant> materialised = new LinkedHashMap<>();
		for (ShopEntitlement row : entitlements.findInForce(shopId, now)) {
			FeatureKey feature = row.feature();
			// A row for a key this build does not know is kept under its raw string so the admin
			// screen can report it. Dropping it silently would hide a feature that was sold.
			materialised.put(row.getFeatureKey(), new Grant(
					row.granted(now), row.getLimitValue(), true));
		}

		Map<String, Grant> freeTier = freePlanGrants();

		Map<FeatureKey, Grant> answer = new LinkedHashMap<>();
		for (FeatureKey feature : FeatureKey.values()) {
			if (feature == FeatureKey.LIMITED) {
				continue;
			}
			Grant grant = materialised.get(feature.key());
			answer.put(feature, grant != null ? grant : freeTier.getOrDefault(feature.key(),
					new Grant(false, null, false)));
		}
		return answer;
	}

	// ------------------------------------------------------------------ guards

	/**
	 * Refuses an operation a shop has not paid for.
	 *
	 * @throws EntitlementDeniedException carrying the feature and the plan to upgrade to, so the
	 *         endpoint's problem response can name both instead of a bare 403
	 */
	public void require(UUID shopId, FeatureKey feature) {
		Grant grant = resolve(shopId, feature, Instant.now());
		if (!grant.granted()) {
			throw new EntitlementDeniedException(feature, null, upgradePathFor(feature));
		}
	}

	/**
	 * Refuses a countable operation that would exceed the shop's cap.
	 *
	 * @param currentCount how many the shop has now
	 * @param adding      how many this operation would add — 1 for a create, n for an import
	 * @throws EntitlementDeniedException whose message says the numbers, because "limit reached" with
	 *         no figures is the least actionable message a shop owner can be given
	 */
	public void requireCapacity(UUID shopId, FeatureKey feature, long currentCount, long adding) {
		Grant grant = resolve(shopId, feature, Instant.now());

		if (!grant.granted() || grant.limitValue() == null) {
			// Not a countable feature, or uncapped. Either way there is nothing to enforce -- and if
			// the feature is simply not held, an uncapped reading would be the wrong answer, so the
			// not-granted branch is handled by require() instead of silently passing.
			if (!grant.granted()) {
				throw new EntitlementDeniedException(feature, null, upgradePathFor(feature));
			}
			return;
		}

		if (currentCount + adding <= grant.limitValue()) {
			return;
		}

		throw new EntitlementDeniedException(
				feature,
				new LimitSnapshot(currentCount, adding, grant.limitValue()),
				upgradePathFor(feature));
	}

	/** Whether an operation would fit, without throwing. For rendering a disabled button. */
	@Transactional(readOnly = true)
	public boolean hasCapacity(UUID shopId, FeatureKey feature, long currentCount, long adding) {
		Grant grant = resolve(shopId, feature, Instant.now());
		if (!grant.granted()) {
			return false;
		}
		return grant.limitValue() == null || currentCount + adding <= grant.limitValue();
	}

	// ------------------------------------------------------------------ commission

	/**
	 * Whether this shop owes no platform commission.
	 *
	 * <p>The authority for {@code shop.commission_plan}, which is what {@code OrderService} reads.
	 *
	 * <p><b>Derived from {@link FeatureKey#REMOVE_COMMISSION} rather than from "has a running
	 * subscription".</b> Those are the same thing today, but they are not the same <em>question</em>:
	 * a future commission-free tier for a non-subscriber would not have a subscription, and a future
	 * promotion might grant the waiver to a subscriber on the cheapest plan. Reading the feature
	 * means neither future change has to remember to also change this method.
	 */
	@Transactional(readOnly = true)
	public boolean commissionsWaived(UUID shopId) {
		return has(shopId, FeatureKey.REMOVE_COMMISSION);
	}

	// ------------------------------------------------------------------ resolution

	/**
	 * The effective grant for one feature.
	 *
	 * <p>Materialised row if there is one, else the FREE plan's answer.
	 */
	@Transactional(readOnly = true)
	public Grant resolve(UUID shopId, FeatureKey feature, Instant now) {
		Optional<ShopEntitlement> row = entitlements.findByShopIdAndFeatureKey(shopId, feature.key());
		if (row.isPresent()) {
			ShopEntitlement entitlement = row.get();
			// A row that has lapsed does not shadow the FREE tier: it grants nothing, and falling
			// through to the free baseline is strictly more correct than granting nothing at all.
			if (entitlement.inForce(now)) {
				return new Grant(entitlement.granted(now), entitlement.getLimitValue(), true);
			}
			return freePlanGrant(feature).orElse(new Grant(false, null, false));
		}
		return freePlanGrant(feature).orElse(new Grant(false, null, false));
	}

	/** What the FREE plan grants for one feature, or empty when the free plan grants nothing. */
	@Transactional(readOnly = true)
	public Optional<Grant> freePlanGrant(FeatureKey feature) {
		return plans.findByCode(FREE_PLAN_CODE)
				.map(SubscriptionPlan::getId)
				.flatMap(planId -> planEntitlements.findByPlanId(planId).stream()
						.filter(row -> feature.key().equals(row.getFeatureKey()))
						.findFirst())
				.map(row -> new Grant(row.isEnabled(), row.getLimitValue(), false));
	}

	/** The whole FREE baseline, loaded once. */
	@Transactional(readOnly = true)
	public Map<String, Grant> freePlanGrants() {
		Map<String, Grant> grants = new LinkedHashMap<>();
		plans.findByCode(FREE_PLAN_CODE)
				.map(SubscriptionPlan::getId)
				.ifPresent(planId -> {
					for (PlanEntitlement row : planEntitlements.findByPlanId(planId)) {
						grants.put(row.getFeatureKey(),
								new Grant(row.isEnabled(), row.getLimitValue(), false));
					}
				});
		return grants;
	}

	/**
	 * How a shop reaches this feature: the cheapest paid plan that grants it.
	 *
	 * <p>Null when no paid plan offers it, which is a different message from "upgrade" — the UI
	 * should not offer a purchase that does not exist.
	 *
	 * <p><b>Paid plans only.</b> The FREE plan is a candidate here and must be excluded: it grants
	 * {@code max_products}, so without this filter a free shop refused for exceeding its product cap
	 * would be told to "upgrade to Free (Rs. 0.00)" — advice that is not advice, in the one message
	 * a shop owner is guaranteed to read carefully.
	 */
	@Transactional(readOnly = true)
	public SubscriptionPlan upgradePathFor(FeatureKey feature) {
		// The free baseline is already the answer; only something paid can be an upgrade.
		return plans.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
				.filter(SubscriptionPlan::isPaid)
				.filter(plan -> grantsFeature(plan, feature))
				// Cheapest first, so the prompt names the plan a shop can actually afford rather
				// than the most expensive one that happens to be listed first.
				.min(java.util.Comparator.comparingLong(SubscriptionPlan::getPriceMinor))
				.orElse(null);
	}

	/** Whether a plan grants a feature. One query per plan, once. */
	private boolean grantsFeature(SubscriptionPlan plan, FeatureKey feature) {
		return planEntitlements.findByPlanId(plan.getId()).stream()
				.anyMatch(row -> feature.key().equals(row.getFeatureKey()) && row.isEnabled());
	}

	// ------------------------------------------------------------------ types

	/**
	 * The effective state of one feature for one shop.
	 *
	 * @param granted      whether the shop may use it now
	 * @param limitValue   the cap, or null when uncapped
	 * @param materialised true when this came from {@code shop_entitlement} rather than the free
	 *        baseline. Reported because it is the difference between "your plan says 500" and "you
	 *        get 25 because you have no plan", and a shop owner reading a limit needs to know which.
	 */
	public record Grant(boolean granted, Long limitValue, boolean materialised) {
	}

	/** The numbers behind a refused limit, for the message and the API response. */
	public record LimitSnapshot(long currentCount, long adding, long limit) {
	}

	/**
	 * A refused operation, carrying enough to render an upgrade prompt.
	 *
	 * <p><b>Not an {@code AccessDeniedException}.</b> A 403 says "no" and stops; this has to say
	 * what would make it yes, because the shop's owner is a paying customer of this platform and
	 * the difference between a wall and an upsell is the whole feature. It maps to 403 with a
	 * problem body naming the feature, the numbers and the plan.
	 */
	public static class EntitlementDeniedException extends RuntimeException {

		private final FeatureKey feature;
		private final LimitSnapshot limit;
		private final SubscriptionPlan upgradeTo;

		public EntitlementDeniedException(FeatureKey feature, LimitSnapshot limit,
				SubscriptionPlan upgradeTo) {
			super(message(feature, limit, upgradeTo));
			this.feature = feature;
			this.limit = limit;
			this.upgradeTo = upgradeTo;
		}

		private static String message(FeatureKey feature, LimitSnapshot limit,
				SubscriptionPlan upgradeTo) {

			StringBuilder text = new StringBuilder();

			if (limit != null) {
				text.append("Your plan allows ")
						.append(limit.limit())
						.append(' ')
						.append(human(feature))
						.append(", and you already have ")
						.append(limit.currentCount())
						.append(". ");
			} else {
				text.append("Your current plan does not include ")
						.append(human(feature))
						.append(". ");
			}

			if (upgradeTo == null) {
				// Not an upsell opportunity. Saying "no plan offers this" is honest where "upgrade"
				// would send the owner to a pricing page that cannot help them.
				return text.append("No plan currently offers it.").toString();
			}

			return text.append("Upgrade to ")
					.append(upgradeTo.getName())
					.append(" (")
					.append(upgradeTo.priceDisplay())
					.append(") to use it.")
					.toString();
		}

		/** "max_products" reads as a database column; this is what a shop owner sees. */
		private static String human(FeatureKey feature) {
			return switch (feature) {
				case MAX_PRODUCTS -> "products";
				case REMOVE_COMMISSION -> "commission-free trading";
				case USE_PAID_TEMPLATES -> "paid templates";
				case CUSTOM_CSS -> "custom CSS";
				case CUSTOM_DOMAIN -> "a custom domain";
				case DISCOUNT_CODES -> "discount codes";
				case CSV_IMPORT -> "CSV import";
				case PRIORITY_SUPPORT -> "priority support";
				case ABANDONED_CART_RECOVERY -> "abandoned-cart reminders";
				case LIMITED -> "this";
			};
		}

		public FeatureKey feature() {
			return feature;
		}

		public LimitSnapshot limit() {
			return limit;
		}

		/** The plan to buy, or null when none is on sale that grants this. */
		public SubscriptionPlan upgradeTo() {
			return upgradeTo;
		}

		/** Null when no active plan grants it. */
		public String upgradeToCode() {
			return upgradeTo == null ? null : upgradeTo.getCode();
		}
	}
}
