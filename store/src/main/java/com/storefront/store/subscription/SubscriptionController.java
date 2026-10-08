package com.storefront.store.subscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.Money;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;
import com.storefront.store.wallet.WalletTopUpRequest;
import com.storefront.store.wallet.WalletTopUpService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * The shop's own subscription screen (Phase 6).
 *
 * <p><b>No shop id anywhere.</b> The shop is {@link TenantGuard#requireOwnedShop()} — derived from
 * the token, never a parameter. A path variable here would be an invitation to add one later, and
 * "which shop am I?" is the single question this platform has already been bitten by twice.
 *
 * <p><b>Buying is two steps, and the UI shows both.</b> Requesting a plan queues it and returns
 * where to send the money; nothing is granted and no commission waiver happens. The shop then
 * submits a top-up for exactly the plan's price, and an admin approves it. That is D7's manual model,
 * and collapsing the two into one "subscribe" button would imply a charge that has not happened.
 *
 * <p>Requests and top-ups are created here but approved by an admin, so this controller creates rows
 * and never moves money. The only endpoint that moves money is
 * {@code AdminSubscriptionController}, which is why that one is admin-gated.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions", description = "The caller's own plan, entitlements and renewal")
@RequiredArgsConstructor
public class SubscriptionController {

	private final SubscriptionService subscriptions;
	private final EntitlementService entitlements;
	private final SubscriptionPlanRepository plans;
	private final WalletTopUpService topUps;
	private final TenantGuard tenantGuard;
	private final CurrentUser currentUser;

	/** The pricing screen: what can be bought, and what the caller already has. */
	@GetMapping("/plans")
	@Operation(summary = "Plans on sale, each with what it grants and what the caller has now")
	public List<PlanView> plans() {
		// Resolved through the guard rather than assumed: an unsaved shop owner asking what plans
		// exist is not the same question as one asking what to upgrade to, and the endpoint is
		// reachable before a shop exists.
		UUID shopId = tenantGuard.ownedShopId();

		return subscriptions.onSale().stream()
				.map(plan -> PlanView.of(plan, subscriptions.entitlementsOf(plan.getId()),
						shopId == null ? java.util.Map.of() : entitlements.all(shopId)))
				.toList();
	}

	/** Everything about the caller's current subscription. The account screen's one request. */
	@GetMapping("/me")
	@Operation(summary = "The caller's subscription, entitlements and limits")
	public ResponseEntity<MySubscription> me() {
		Shop shop = tenantGuard.requireOwnedShop();
		Instant now = Instant.now();

		var current = subscriptions.current(shop.getId());
		var grants = entitlements.all(shop.getId(), now);

		return ResponseEntity.ok(new MySubscription(
				shop.getId(),
				subscriptions.history(shop.getId()).stream().map(HistoryEntry::from).toList(),
				current.map(CurrentPlan::from).orElse(null),
				grants.entrySet().stream()
						.map(entry -> new FeatureView(
								entry.getKey().key(),
								entry.getValue().granted(),
								entry.getValue().limitValue(),
								entry.getValue().materialised()))
						.toList(),
				// The commission flag is read here so the UI never has to infer it, and so the number
				// the owner sees is the number the order-accept path will use.
				shop.paysCommission(),
				shop.getCommissionPlan().name()));
	}

	/**
	 * Queues a plan request.
	 *
	 * <p>Returns what to do next rather than a bare id: the price to send and the reference the bank
	 * transfer must carry. A shop that has to work out the amount itself will eventually send the
	 * wrong one, and {@code forSubscription} will then refuse the top-up.
	 */
	@PostMapping("/requests")
	@Operation(summary = "Request a plan", description = "Queues it. Nothing is charged or granted yet.")
	public ResponseEntity<RequestResult> request(@Valid @RequestBody RequestPlan body) {
		Shop shop = tenantGuard.requireOwnedShop();
		SubscriptionPlan plan = subscriptions.requirePlan(body.planId());

		ShopSubscription queued = subscriptions.request(shop, plan);

		return ResponseEntity.status(HttpStatus.CREATED).body(new RequestResult(
				queued.getId().toString(),
				plan.getCode(),
				plan.getName(),
				plan.getPriceMinor(),
				plan.priceDisplay(),
				plan.getBillingPeriod().name(),
				plan.getDurationDays(),
				// The reference is the plan code plus the subscription id, so an admin matching a bank
				// statement to this request can do it by eye. Generated here rather than by the shop,
				// which is the whole reason it can be relied on.
				referenceFor(queued),
				"Transfer " + plan.priceDisplay() + " to the platform, then submit the top-up "
						+ "with reference " + referenceFor(queued) + ".",
				Money.ofMinor(plan.getPriceMinor()).toDisplay()));
	}

	/**
	 * Submits the top-up that pays for a queued request.
	 *
	 * <p>The amount is <b>not</b> taken from the client. It comes from the plan the request names, so
	 * a shop cannot ask to be charged one plan's price and be granted another; the entity checks it
	 * as well, and refuses before the request is ever queued.
	 */
	@PostMapping("/requests/{subscriptionId}/topup")
	@Operation(summary = "Submit proof of payment for a queued request")
	public ResponseEntity<WalletTopUpRequest> submitPayment(
			@PathVariable UUID subscriptionId,
			@Valid @RequestBody PaymentProof body) {

		Shop shop = tenantGuard.requireOwnedShop();

		// Always a *pending* request. `subscriptions.current()` deliberately ignores pending rows --
		// they grant nothing -- so a payment must be matched against the queued request explicitly.
		// Letting a shop submit payment against an already-running subscription would queue a
		// second one and charge for it.
		ShopSubscription subscription = findPending(subscriptionId, shop.getId());

		WalletTopUpRequest request = topUps.requestForSubscription(
				shop.getId(),
				subscription,
				body.method(),
				body.reference() == null || body.reference().isBlank()
						? referenceFor(subscription)
						: body.reference(),
				body.proofMediaId(),
				body.note());

		return ResponseEntity.status(HttpStatus.CREATED).body(request);
	}

	/** Cancels. Takes effect at the end of the paid period — see {@link ShopSubscription#cancel()}. */
	@PostMapping("/{subscriptionId}/cancel")
	@Operation(summary = "Do not renew",
			description = "Keeps the plan until the period already paid for ends.")
	public MySubscription cancel(@PathVariable UUID subscriptionId) {
		Shop shop = tenantGuard.requireOwnedShop();
		subscriptions.cancel(shop.getId(), subscriptionId);
		return me().getBody();
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * The shop's own pending request.
	 *
	 * <p>Exists because {@code current} deliberately ignores pending rows — they grant nothing, so
	 * "what am I subscribed to" must not answer with one. The payment form needs the opposite
	 * question, and conflating the two would let a shop submit payment against a running
	 * subscription and be charged for a second one.
	 */
	private ShopSubscription findPending(UUID subscriptionId, UUID shopId) {
		return subscriptions.history(shopId).stream()
				.filter(row -> row.getId().equals(subscriptionId))
				.filter(row -> row.getStatus() == ShopSubscription.Status.PENDING_PAYMENT)
				.findFirst()
				.orElseThrow(() -> new SubscriptionService.NoSuchSubscriptionException(subscriptionId));
	}

	/**
	 * The bank reference for a subscription purchase.
	 *
	 * <p>Plan code plus the subscription id, and the subscription id is what links the transfer to
	 * this subscription: two shops buying the same plan produce different references.
	 */
	private static String referenceFor(ShopSubscription subscription) {
		return subscription.getPlan().getCode() + "-" + subscription.getId().toString().substring(0, 8);
	}

	// ------------------------------------------------------------------ types

	public record RequestPlan(@NotNull UUID planId) {
	}

	public record PaymentProof(
			WalletTopUpRequest.Method method,
			String reference,
			UUID proofMediaId,
			String note) {
	}

	/** A plan on the pricing screen, with the caller's position on it. */
	public record PlanView(
			String id,
			String code,
			String name,
			String description,
			String billingPeriod,
			long priceMinor,
			String priceDisplay,
			Integer durationDays,
			boolean isActive,
			int sortOrder,
			List<String> grants) {

		static PlanView of(SubscriptionPlan plan, List<PlanEntitlement> planGrants,
				java.util.Map<FeatureKey, EntitlementService.Grant> callerGrants) {

			return new PlanView(
					plan.getId().toString(),
					plan.getCode(),
					plan.getName(),
					plan.getDescription(),
					plan.getBillingPeriod().name(),
					plan.getPriceMinor(),
					plan.priceDisplay(),
					plan.getDurationDays(),
					plan.isActive(),
					plan.getSortOrder(),
					planGrants.stream()
							.filter(PlanEntitlement::isEnabled)
							.map(row -> describe(row.feature(), row.getLimitValue()))
							// Sorted, so two admins editing a plan's rows cannot make its feature
							// list appear in a different order between page loads.
							.sorted()
							.toList());
		}

		/** "Up to 500 products", not "max_products: 500". The UI renders prose. */
		private static String describe(FeatureKey feature, Long limit) {
			String name = switch (feature) {
				case MAX_PRODUCTS -> "Products";
				case REMOVE_COMMISSION -> "No commission";
				case USE_PAID_TEMPLATES -> "Paid templates";
				case CUSTOM_CSS -> "Custom CSS";
				case CUSTOM_DOMAIN -> "Custom domain";
				case DISCOUNT_CODES -> "Discount codes";
				case CSV_IMPORT -> "CSV import";
				case PRIORITY_SUPPORT -> "Priority support";
				case ABANDONED_CART_RECOVERY -> "Abandoned-cart reminders";
				case LIMITED -> "Limitation";
			};
			return limit == null ? name : "Up to " + limit + " " + name.toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** The caller's subscription state, whole. */
	public record MySubscription(
			UUID shopId,
			List<HistoryEntry> history,
			CurrentPlan current,
			List<FeatureView> features,
			/** True when the shop still pays commission. Mirrors what accept-time will do. */
			boolean paysCommission,
			String commissionPlan) {
	}

	public record CurrentPlan(
			String subscriptionId,
			String planCode,
			String planName,
			String status,
			Instant startedAt,
			Instant expiresAt,
			long daysRemaining,
			/** True once cancelled: still active, but will lapse at expiry. */
			boolean willNotRenew,
			/** Null once cancelled -- nothing to renew. */
			String upgradeToCode) {

		static CurrentPlan from(ShopSubscription subscription) {
			Instant now = Instant.now();
			return new CurrentPlan(
					subscription.getId().toString(),
					subscription.getPlan().getCode(),
					subscription.getPlan().getName(),
					subscription.getStatus().name(),
					subscription.getStartedAt(),
					subscription.getExpiresAt(),
					subscription.daysRemaining(now),
					subscription.willNotRenew(),
					// Named rather than a bare boolean, because "what happens at expiry" is a
					// different question from "what do I have now" and the UI needs both answers.
					subscription.willNotRenew() ? null : subscription.getPlan().getCode());
		}
	}

	/**
	 * One row of the shop's subscription history.
	 *
	 * <p><b>The payment fields are on the row, not only in the request response.</b> Without them the
	 * payment step exists only in the browser's memory: a shop that requested a plan and then closed
	 * the tab, reloaded, or hit a refresh, came back to a history table reading "Awaiting payment"
	 * with no amount to send and no reference to quote — and no way to finish. That is the whole D7
	 * flow, stuck, because the screen kept state it could have read from the server.
	 *
	 * <p>So a pending row carries what the shop needs to pay, and the screen renders the payment
	 * step from it. The reference is generated rather than stored, because it is derived from the
	 * plan code and the subscription id and must match on both sides.
	 *
	 * @param reference the transfer reference, or null once there is nothing left to pay
	 */
	public record HistoryEntry(
			String subscriptionId,
			String planCode,
			String planName,
			String status,
			Instant startedAt,
			Instant expiresAt,
			Instant cancelledAt,
			long amountMinor,
			String amountDisplay,
			String reference) {

		static HistoryEntry from(ShopSubscription subscription) {
			boolean awaitingPayment = subscription.getStatus() == ShopSubscription.Status.PENDING_PAYMENT;

			return new HistoryEntry(
					subscription.getId().toString(),
					subscription.getPlan().getCode(),
					subscription.getPlan().getName(),
					subscription.getStatus().name(),
					subscription.getStartedAt(),
					subscription.getExpiresAt(),
					subscription.getCancelledAt(),
					subscription.getPlan().getPriceMinor(),
					Money.ofMinor(subscription.getPlan().getPriceMinor()).toDisplay(),
					awaitingPayment ? referenceFor(subscription) : null);
		}
	}

	public record FeatureView(String key, boolean enabled, Long limit, boolean materialised) {
	}

	/** What the shop must do next after requesting a plan. */
	public record RequestResult(
			String subscriptionId,
			String planCode,
			String planName,
			long amountMinor,
			String amountDisplay,
			String billingPeriod,
			Integer durationDays,
			String reference,
			String instructions,
			String nextStep) {
	}
}
