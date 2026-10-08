package com.storefront.store.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.security.CurrentUser;
import com.storefront.store.subscription.SubscriptionPlan;
import com.storefront.store.wallet.WalletTopUpRepository;
import com.storefront.store.wallet.WalletTopUpRequest;
import com.storefront.store.wallet.WalletTopUpService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The admin's top-up queue.
 *
 * <p>Class-level {@code @PreAuthorize} (D36), so every endpoint here needs ADMIN and a new one
 * cannot be added without an authorisation decision.
 *
 * <p>Approving credits a shop's wallet, which is why this is admin-only and why the request's
 * reference is the thing to check against a bank statement. The UI must show the reference
 * prominently — an approval that credits money nobody sent is the single most expensive mistake
 * available in this platform, and it is made by clicking one button.
 */
@RestController
@RequestMapping("/api/v1/admin/wallet/topups")
@Tag(name = "Admin wallet", description = "Review and approve shop top-up requests")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminWalletController {

	private static final int MAX_PAGE_SIZE = 100;

	private final WalletTopUpService topUps;
	private final WalletTopUpRepository requests;
	private final com.storefront.store.shop.ShopRepository shops;
	private final com.storefront.store.subscription.SubscriptionPlanRepository plans;
	private final CurrentUser currentUser;

	/**
	 * The review queue.
	 *
	 * <p>Oldest first: an admin works through the queue in order, and a FIFO page is what makes
	 * "have I missed anything?" answerable.
	 */
	@GetMapping
	@Operation(summary = "Top-up requests, oldest pending first")
	public QueuePage queue(
			@RequestParam(required = false) WalletTopUpRequest.Status status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) int size) {

		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
		var result = topUps.queue(status, pageable);

		// One query for every plan this page references, rather than one per row.
		java.util.Map<UUID, SubscriptionPlan> plans = plansById(
				result.getContent().stream()
						.map(WalletTopUpRequest::getPlanId)
						.filter(java.util.Objects::nonNull)
						.collect(java.util.stream.Collectors.toSet()));

		return new QueuePage(
				result.getContent().stream().map(r -> toItem(r, plans)).toList(),
				page, result.getSize(), result.getTotalElements(), result.getTotalPages(),
				topUps.pendingCount());
	}

	/**
	 * Approves a request, crediting the shop's wallet.
	 *
	 * <p>409 if it was already reviewed — a double-clicked Approve must not credit twice.
	 */
	@PostMapping("/{requestId}/approve")
	@Operation(summary = "Approve a top-up",
			description = "Credits the shop's wallet. Refused if already reviewed.")
	public WalletTopUpRequest.TopUpView approve(@PathVariable UUID requestId,
			@Valid @RequestBody(required = false) DecisionRequest request) {

		return WalletTopUpRequest.TopUpView.from(topUps.approve(requestId,
				currentUser.optionalUserId(), request == null ? null : request.note()));
	}

	/** Rejects. A reason is required, so the shop knows what to fix. */
	@PostMapping("/{requestId}/reject")
	@Operation(summary = "Reject a top-up",
			description = "No money moves. A reason is required and shown to the shop.")
	public WalletTopUpRequest.TopUpView reject(@PathVariable UUID requestId,
			@Valid @RequestBody DecisionRequest request) {

		if (request == null || request.note() == null || request.note().isBlank()) {
			// Checked here as well as in the entity so the API returns a field error naming the
			// field, rather than a bare "must not be blank".
			throw new IllegalArgumentException("Give a reason so the shop knows what to fix");
		}
		return WalletTopUpRequest.TopUpView.from(
				topUps.reject(requestId, currentUser.optionalUserId(), request.note()));
	}

	/**
	 * Resolves the shop's name for the queue row.
	 *
	 * <p>An admin reconciling against a bank statement is looking for "who sent this", not for a
	 * UUID, so the name is resolved here rather than left for the client to join.
	 */
	private QueueItem toItem(WalletTopUpRequest request,
			java.util.Map<UUID, SubscriptionPlan> plans) {
		String name = shops.findById(request.getShopId())
				.map(com.storefront.store.shop.Shop::getName)
				.orElse("(deleted shop)");
		return QueueItem.from(request, name, plans);
	}

	/** Looks up the given plans in one query, skipping any that have been withdrawn and deleted. */
	private java.util.Map<UUID, SubscriptionPlan> plansById(java.util.Set<UUID> ids) {
		if (ids.isEmpty()) {
			return java.util.Map.of();
		}
		return plans.findAllById(ids).stream()
				.collect(java.util.stream.Collectors.toMap(
						SubscriptionPlan::getId, java.util.function.Function.identity()));
	}

	public record DecisionRequest(@Size(max = 500) String note) {
	}

	/**
	 * One row in the review queue.
	 *
	 * <p>Includes the shop name, because an admin reconciling against a bank statement is looking
	 * for "who sent this", not for a UUID.
	 */
	/**
	 * A request in the queue.
	 *
	 * <p><b>{@code isSubscription} is the field that stops an admin approving a payment that does not
	 * do what they think.</b> There are two approvals: a plain top-up credits the wallet, and a
	 * subscription purchase credits, charges the plan, activates it and grants its entitlements. A UI
	 * that showed only this list would send every subscription to the plain endpoint, which would
	 * credit the money and never start the plan -- leaving the shop's bank statement matched, its
	 * balance correct, and its subscription absent. That failure looks like a success from both sides.
	 *
	 * <p>So the client is told which kind this is, and routes accordingly.
	 *
	 * @param plans the plans referenced by this page's rows, indexed by id. Passed in rather than
	 *        reached for: a record cannot have dependencies, and looking a plan up per row would be
	 *        one query each for a queue of 25
	 * @param isPlanPrice whether the claimed amount equals the plan's price. True by construction
	 *        -- {@code WalletTopUpRequest.forSubscription} refuses a mismatch -- and surfaced so a
	 *        future path that lets a shop claim a different figure is visible in the queue instead of
	 *        being discovered at approval time
	 */
	public record QueueItem(
			UUID id,
			UUID shopId,
			String shopName,
			long amount,
			String amountDisplay,
			WalletTopUpRequest.Method method,
			String reference,
			String shopNote,
			WalletTopUpRequest.Status status,
			String reviewNote,
			java.time.Instant createdAt,
			/** Null for an ordinary top-up, which is the common case and must keep working. */
			Boolean isSubscription,
			String planCode,
			Boolean isPlanPrice) {

		static QueueItem from(WalletTopUpRequest r, String shopName,
				java.util.Map<UUID, SubscriptionPlan> plans) {

			boolean subscription = r.getSubscriptionId() != null;
			SubscriptionPlan plan = subscription ? plans.get(r.getPlanId()) : null;

			return new QueueItem(
					r.getId(),
					r.getShopId(),
					shopName,
					r.getAmountMinor(),
					com.storefront.store.common.Money.ofMinor(r.getAmountMinor()).toDisplay(),
					r.getMethod(),
					r.getReference(),
					r.getNote(),
					r.getStatus(),
					r.getReviewNote(),
					r.getCreatedAt(),
					subscription ? Boolean.TRUE : null,
					plan == null ? null : plan.getCode(),
					plan == null ? null
							: Boolean.valueOf(r.getAmountMinor() == plan.getPriceMinor()));
		}
	}

	public record QueuePage(List<QueueItem> content, int page, int size, long totalElements,
			int totalPages, long pendingCount) {
	}
}
