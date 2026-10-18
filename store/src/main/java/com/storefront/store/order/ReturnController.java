package com.storefront.store.order;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.PageResponse;
import com.storefront.store.order.ReturnService.ItemView;
import com.storefront.store.order.ReturnService.ReturnView;
import com.storefront.store.security.TenantGuard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Returns and refunds (Phase 8, D14).
 *
 * <p><b>Two audiences, two paths, both derived from the token.</b> A customer requests against
 * {@code /my/...} with no order id in the path they can choose freely; a shop works its queue at
 * {@code /shops/...} with the shop taken from the caller. Neither endpoint takes a shop id as a
 * parameter a customer controls, for the same reason the rest of the platform does not.
 *
 * <p><b>Approve and refund are separate endpoints, not one.</b> Approving records a decision;
 * refunding moves money. One endpoint would credit a customer's refund at the moment a shop clicked
 * a button, and the payment would then fail with the wallet already debited.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Returns", description = "Return requests, approvals and refunds")
@RequiredArgsConstructor
public class ReturnController {

	private static final int MAX_PAGE_SIZE = 100;

	private final ReturnService returns;
	private final TenantGuard tenantGuard;

	// ------------------------------------------------------------------ the customer

	@PostMapping("/orders/{orderId}/returns")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Request a return against a delivered order",
			description = "The order must belong to the caller. Partial returns are allowed.")
	public ReturnView request(@PathVariable UUID orderId,
			@Valid @RequestBody CustomerRequest body) {

		ReturnRequest request = returns.request(orderId, tenantGuard.requireCustomerId(), body.reason(),
				body.quantities());

		return ReturnView.from(request);
	}

	@GetMapping("/orders/{orderId}/returns")
	@Operation(summary = "Every return on one order, oldest first")
	public List<ReturnView> forOrder(@PathVariable UUID orderId) {
		return returns.forOrderView(orderId);
	}

	// ------------------------------------------------------------------ the shop

	@GetMapping("/shops/returns")
	@PreAuthorize("hasRole('ROLE_SHOP') or hasRole('ROLE_ADMIN')")
	@Operation(summary = "This shop's return queue",
			description = "The shop comes from the token. Open requests first, then decided ones.")
	public PageResponse<ReturnView> queue(
			@RequestParam(required = false) ReturnRequest.Status status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {

		var found = returns.queueView(tenantGuard.requireOwnedShop().getId(), status,
				PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));

		return new PageResponse<>(
				found.getContent(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	@PatchMapping("/shops/returns/{requestId}/approve")
	@PreAuthorize("hasRole('ROLE_SHOP') or hasRole('ROLE_ADMIN')")
	@Operation(summary = "Approve part or all of a return",
			description = "Omitting a line approves zero of it, which is how a shop approves two of "
					+ "four items. Does not move money — call refund for that.")
	public ReturnView approve(@PathVariable UUID requestId, @Valid @RequestBody ApproveRequest body) {
		return ReturnView.from(
				returns.approve(requestId, tenantGuard.requireOwnedShop().getId(),
						currentUser(), body.quantities(), body.note()));
	}

	@PatchMapping("/shops/returns/{requestId}/reject")
	@PreAuthorize("hasRole('ROLE_SHOP') or hasRole('ROLE_ADMIN')")
	@Operation(summary = "Decline a return, with a note the customer can be told")
	public ReturnView reject(@PathVariable UUID requestId, @Valid @RequestBody RejectRequest body) {
		return ReturnView.from(
				returns.reject(requestId, tenantGuard.requireOwnedShop().getId(),
						currentUser(), body.note()));
	}

	@PostMapping("/shops/returns/{requestId}/refund")
	@PreAuthorize("hasRole('ROLE_SHOP') or hasRole('ROLE_ADMIN')")
	@Operation(summary = "Refund an approved return",
			description = "Debits the shop's wallet and credits commission back in proportion. "
					+ "Refused when the shop cannot afford it, leaving the return approved and "
					+ "unrefunded so it stays visible.")
	public ResponseEntity<RefundView> refund(@PathVariable UUID requestId) {
		var outcome = returns.refund(requestId, tenantGuard.requireOwnedShop().getId(),
				currentUser());

		return ResponseEntity.ok(RefundView.from(outcome));
	}

	/**
	 * The signed-in account, for anything that records who did something.
	 *
	 * <p>A thin wrapper so the distinction is stated once: {@code tenantGuard.requireOwnedShop()} is a
	 * <em>shop</em> id and {@code this} is an <em>account</em> id, and passing one where the other
	 * belongs produces an empty result rather than an error — which is the worst kind of wrong.
	 */
	private java.util.UUID currentUser() {
		return tenantGuard.currentUserId();
	}

	// ------------------------------------------------------------------ types

	/** What a customer sends to request a return. */
	public record CustomerRequest(
			@NotBlank @Size(max = 500) String reason,
			@NotEmpty Map<UUID, Integer> quantities) {
	}

	/** How many units of each line the shop is refunding. */
	public record ApproveRequest(
			Map<UUID, Integer> quantities,
			@Size(max = 500) String note) {
	}

	public record RejectRequest(@NotBlank @Size(max = 500) String note) {
	}

	/** What a refund actually moved. Two figures, because they are not the same money. */
	public record RefundView(
			UUID requestId,
			long refundedMinor,
			long commissionReversedMinor,
			long refundableBaseMinor,
			double percentOfOrder) {

		static RefundView from(ReturnService.RefundOutcome outcome) {
			return new RefundView(
					outcome.request().getId(),
					outcome.refundedMinor(),
					outcome.commissionReversedMinor(),
					outcome.refundableBaseMinor(),
					outcome.percentOfOrder());
		}
	}
}
