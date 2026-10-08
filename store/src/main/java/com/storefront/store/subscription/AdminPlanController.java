package com.storefront.store.subscription;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Plan management (D4).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')}") (D36), so a new endpoint here cannot be
 * added without an explicit authorisation decision.
 *
 * <p><b>There is no delete.</b> Plans are withdrawn with {@code isActive = false}, because a
 * cancelled subscriber's history must still be able to name the plan they were on and MRR must
 * still be reconcilable for a month that included it. Deleting would also cascade away the
 * entitlements it granted and leave a lapsed subscriber's record unexplained.
 *
 * <p><b>Editing a plan with live subscribers is refused</b>, for the same reason: entitlements are
 * materialised at activation, so a plan edit would not reach current subscribers anyway — and would
 * reach the <em>next</em> one, changing what they were quoted against. The fix is a new plan.
 *
 * <p><b>Every write is {@code @Transactional}.</b> Not a style choice: creating a plan flushes the
 * plan row and <em>then</em> validates its entitlements, so without a transaction a request carrying
 * a typo'd feature key returned 400 and left the plan row committed behind it. An admin who was told
 * "nothing was saved" had in fact created a plan — one that grants nothing, on a pricing page they
 * would later publish. Found by {@code SubscriptionControllerIT}.
 */
@RestController
@RequestMapping("/api/v1/admin/plans")
@Tag(name = "Admin plans", description = "Create, edit and withdraw subscription plans")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminPlanController {

	private static final int MAX_PAGE_SIZE = 100;

	private final SubscriptionPlanRepository plans;
	private final PlanEntitlementRepository planEntitlements;
	private final ShopSubscriptionRepository subscriptions;
	private final SubscriptionService service;
	private final CurrentUser currentUser;

	@GetMapping
	@Operation(summary = "All plans, including withdrawn ones")
	public PageResponse<PlanDetail> list(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "50") @Min(1) int maxSize) {

		Pageable pageable = PageRequest.of(page, Math.min(maxSize, MAX_PAGE_SIZE));
		var found = plans.findAllByOrderBySortOrderAscNameAsc(pageable);

		return new PageResponse<>(
				found.getContent().stream().map(this::detail).toList(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	/** Every feature key, so the admin UI does not hardcode a list that drifts from the enum. */
	@GetMapping("/features")
	@Operation(summary = "The feature keys a plan can grant")
	public List<String> features() {
		return java.util.Arrays.stream(FeatureKey.values())
				.filter(feature -> feature != FeatureKey.LIMITED)
				.map(FeatureKey::key)
				.toList();
	}

	@GetMapping("/{planId}")
	@Operation(summary = "One plan, with its entitlements and subscriber count")
	public PlanDetail one(@PathVariable UUID planId) {
		return detail(service.requirePlan(planId));
	}

	@PostMapping
	@Operation(summary = "Create a plan")
	@Transactional
	public ResponseEntity<PlanDetail> create(@Valid @RequestBody PlanDraft draft) {

		// Everything is checked before a single row is written.
		//
		// <p>The obvious order -- build the plan, flush it, then validate its entitlements -- leaves a
		// committed plan behind whenever an entitlement is rejected. An admin told "nothing was saved"
		// has in fact created a plan that grants nothing, on a pricing page they will later publish.
		// `@Transactional` would roll it back, but relying on the controller being proxied for a
		// correctness property is a bad trade when the check is free to run first.
		List<PlanEntitlement> granted = validateEntitlements(draft.entitlements());

		SubscriptionPlan plan = new SubscriptionPlan(
				draft.code(), draft.name(),
				draft.billingPeriod() == null ? SubscriptionPlan.BillingPeriod.NONE
						: draft.billingPeriod(),
				draft.priceMinor() == null ? 0L : draft.priceMinor(),
				draft.durationDays());

		plan.setDescription(draft.description());
		plan.setActive(draft.active() == null || draft.active());
		plan.setSortOrder(draft.sortOrder() == null ? 0 : draft.sortOrder());

		SubscriptionPlan saved = plans.saveAndFlush(plan);
		writeEntitlements(saved, granted);

		log.info("Plan {} ({}) created by {}", saved.getCode(), saved.getName(),
				currentUser.require().getId());
		return ResponseEntity.status(HttpStatus.CREATED).body(detail(saved));
	}

	@PutMapping("/{planId}")
	@Operation(summary = "Edit a plan",
			description = "Refused while an active subscription holds it. Create a new plan instead.")
	@Transactional
	public PlanDetail update(@PathVariable UUID planId, @Valid @RequestBody PlanDraft draft) {
		SubscriptionPlan plan = service.requirePlan(planId);

		// Validated up front for the same reason as create: an unknown feature key must not leave a
		// half-edited plan behind.
		List<PlanEntitlement> replacement =
				draft.entitlements() == null ? null : validateEntitlements(draft.entitlements());

		long live = subscriptions.countByPlanIdAndStatus(planId, ShopSubscription.Status.ACTIVE);
		if (live > 0 && changesWhatSubscribersBought(plan, draft)) {
			// Refused rather than silently ineffective. An admin who edits the price of a plan with
			// 40 subscribers would otherwise see "saved" and have no subscribers get it -- and the
			// next subscriber would be charged the new price for entitlements they were quoted the
			// old terms of.
			throw new PlanInUseException(plan.getCode(), live);
		}

		if (draft.name() != null && !draft.name().isBlank()) {
			plan.setName(draft.name().trim());
		}
		if (draft.description() != null) {
			plan.setDescription(draft.description());
		}
		if (draft.priceMinor() != null) {
			plan.setPriceMinor(draft.priceMinor());
		}
		if (draft.durationDays() != null) {
			plan.setDurationDays(draft.durationDays());
		}
		if (draft.billingPeriod() != null) {
			plan.setBillingPeriod(draft.billingPeriod());
		}
		if (draft.active() != null) {
			plan.setActive(draft.active());
		}
		if (draft.sortOrder() != null) {
			plan.setSortOrder(draft.sortOrder());
		}

		SubscriptionPlan saved = plans.saveAndFlush(plan);

		if (replacement != null) {
			writeEntitlements(saved, replacement);
		}

		log.info("Plan {} updated by {}", saved.getCode(), currentUser.require().getId());
		return detail(saved);
	}

	/**
	 * Withdraws a plan.
	 *
	 * <p>A DELETE in name only — it sets {@code isActive = false} and never removes the row, for the
	 * reason on the class note. Named as a delete because that is what an admin's intent is, and
	 * given a distinct path so the response can say which happened.
	 */
	@DeleteMapping("/{planId}")
	@Operation(summary = "Withdraw a plan from sale",
			description = "Existing subscribers keep it. It is not deleted.")
	@Transactional
	public PlanDetail withdraw(@PathVariable UUID planId) {
		SubscriptionPlan plan = service.requirePlan(planId);
		plan.setActive(false);
		SubscriptionPlan saved = plans.saveAndFlush(plan);
		log.info("Plan {} withdrawn by {}", saved.getCode(), currentUser.require().getId());
		return detail(saved);
	}

	@PostMapping("/{planId}/entitlements")
	@Operation(summary = "Set what a plan grants", description = "Replaces the whole set.")
	@Transactional
	public PlanDetail setEntitlements(@PathVariable UUID planId,
			@Valid @RequestBody List<@Valid EntitlementDraft> entitlements) {

		SubscriptionPlan plan = service.requirePlan(planId);
		writeEntitlements(plan, validateEntitlements(entitlements));
		return detail(plan);
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Whether an edit changes what subscribers were sold.
	 *
	 * <p>Display-only edits (the name, the description, the sort order, withdrawing it) are allowed
	 * on a live plan. Anything that changes the price, the duration or what is granted is not,
	 * because those are the terms the sale was made on.
	 */
	private boolean changesWhatSubscribersBought(SubscriptionPlan plan, PlanDraft draft) {
		if (draft.priceMinor() != null && draft.priceMinor() != plan.getPriceMinor()) {
			return true;
		}
		if (draft.durationDays() != null
				&& !java.util.Objects.equals(draft.durationDays(), plan.getDurationDays())) {
			return true;
		}
		if (draft.billingPeriod() != null && draft.billingPeriod() != plan.getBillingPeriod()) {
			return true;
		}
		return draft.entitlements() != null
				&& !sameEntitlements(service.entitlementsOf(plan.getId()), draft.entitlements());
	}

	/**
	 * Whether a draft's entitlements match the stored ones.
	 *
	 * <p>Compared as a map rather than list order, because an admin's UI will send them in whatever
	 * order it happens to render them and re-saving would otherwise always look like a change and
	 * always be refused.
	 */
	private boolean sameEntitlements(List<PlanEntitlement> stored, List<EntitlementDraft> draft) {
		java.util.Map<String, java.util.List<Long>> current = new java.util.HashMap<>();
		for (PlanEntitlement row : stored) {
			current.put(row.getFeatureKey(), java.util.List.of(
					row.isEnabled() ? 1L : 0L,
					row.getLimitValue() == null ? -1L : row.getLimitValue()));
		}

		java.util.Map<String, java.util.List<Long>> proposed = new java.util.HashMap<>();
		for (EntitlementDraft row : draft) {
			proposed.put(row.feature(), java.util.List.of(
					row.enabled() ? 1L : 0L,
					row.limit() == null ? -1L : row.limit()));
		}
		return current.equals(proposed);
	}

	/**
	 * Checks a draft entitlement set, without writing anything.
	 *
	 * <p>Split from the write so a request can be fully validated before the first row is inserted.
	 * An unknown key is <b>refused, not skipped</b>: skipping would show "saved" and quietly grant
	 * nothing, which is the outcome that makes a shop think it paid for a feature.
	 */
	private List<PlanEntitlement> validateEntitlements(List<EntitlementDraft> drafts) {
		if (drafts == null) {
			return List.of();
		}
		for (EntitlementDraft draft : drafts) {
			FeatureKey feature = FeatureKey.fromKey(draft.feature());
			if (feature == null || feature == FeatureKey.LIMITED) {
				throw new UnknownFeatureException(draft.feature());
			}
			if (!feature.isLimit() && draft.limit() != null) {
				throw new IllegalArgumentException(
						feature.key() + " is a switch, not a cap, so it cannot have a limit value.");
			}
			if (draft.limit() != null && draft.limit() < 0) {
				throw new IllegalArgumentException(
						feature.key() + " cannot have a negative limit: " + draft.limit());
			}
		}
		return drafts.stream().map(draft -> new PlanEntitlement(
				null, FeatureKey.fromKey(draft.feature()), draft.enabled(), draft.limit())).toList();
	}

	/** Replaces a plan's entitlement rows wholesale. Already validated. */
	private void writeEntitlements(SubscriptionPlan plan, List<PlanEntitlement> drafts) {
		planEntitlements.deleteByPlanId(plan.getId());
		planEntitlements.flush();

		for (PlanEntitlement draft : drafts) {
			// Built fresh against the saved plan: the validated copy carries a null plan, because
			// validation must happen before the row exists.
			planEntitlements.save(new PlanEntitlement(
					plan, draft.feature(), draft.isEnabled(), draft.getLimitValue()));
		}
		planEntitlements.flush();
	}

	private PlanDetail detail(SubscriptionPlan plan) {
		return new PlanDetail(
				plan.getId(),
				plan.getCode(),
				plan.getName(),
				plan.getDescription(),
				plan.getBillingPeriod().name(),
				plan.getPriceMinor(),
				plan.priceDisplay(),
				plan.monthlyEquivalentMinor(),
				plan.getDurationDays(),
				plan.isActive(),
				plan.getSortOrder(),
				service.entitlementsOf(plan.getId()).stream()
						.map(row -> new EntitlementView(
								row.getFeatureKey(), row.isEnabled(), row.getLimitValue()))
						.toList(),
				subscriptions.countByPlanIdAndStatus(plan.getId(), ShopSubscription.Status.ACTIVE));
	}

	// ------------------------------------------------------------------ types

	public record PlanDraft(
			@NotBlank @Size(max = 40) String code,
			@NotBlank @Size(max = 80) String name,
			@Size(max = 500) String description,
			SubscriptionPlan.BillingPeriod billingPeriod,
			Long priceMinor,
			Integer durationDays,
			Boolean active,
			Integer sortOrder,
			List<@Valid EntitlementDraft> entitlements) {
	}

	public record EntitlementDraft(
			@NotBlank String feature,
			boolean enabled,
			Long limit) {
	}

	public record EntitlementView(String feature, boolean enabled, Long limit) {
	}

	public record PlanDetail(
			UUID id,
			String code,
			String name,
			String description,
			String billingPeriod,
			long priceMinor,
			String priceDisplay,
			/** What this plan contributes to MRR, so an admin can check the dashboard's arithmetic. */
			long monthlyEquivalentMinor,
			Integer durationDays,
			boolean isActive,
			int sortOrder,
			List<EntitlementView> entitlements,
			long activeSubscribers) {
	}

	public record PageResponse<T>(
			List<T> content,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}

	/** Maps to HTTP 409. */
	public static class PlanInUseException extends RuntimeException {
		public PlanInUseException(String code, long liveSubscribers) {
			super("Plan " + code + " has " + liveSubscribers + " active subscriber(s). "
					+ "Changing its price, duration or entitlements would not reach them, and would "
					+ "change what the next subscriber is sold. Create a new plan instead.");
		}
	}

	/** Maps to HTTP 400. */
	public static class UnknownFeatureException extends RuntimeException {
		public UnknownFeatureException(String feature) {
			super("'" + feature + "' is not a feature this version knows about. "
					+ "Nothing was saved. See GET /api/v1/admin/plans/features for the valid keys.");
		}
	}
}
