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

		return new QueuePage(
				result.getContent().stream().map(this::toItem).toList(),
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
	private QueueItem toItem(WalletTopUpRequest request) {
		String name = shops.findById(request.getShopId())
				.map(com.storefront.store.shop.Shop::getName)
				.orElse("(deleted shop)");
		return QueueItem.from(request, name);
	}

	public record DecisionRequest(@Size(max = 500) String note) {
	}

	/**
	 * One row in the review queue.
	 *
	 * <p>Includes the shop name, because an admin reconciling against a bank statement is looking
	 * for "who sent this", not for a UUID.
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
			java.time.Instant createdAt) {

		static QueueItem from(WalletTopUpRequest r, String shopName) {
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
					r.getCreatedAt());
		}
	}

	public record QueuePage(List<QueueItem> content, int page, int size, long totalElements,
			int totalPages, long pendingCount) {
	}
}
