package com.storefront.store.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.shop.Shop;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.platform.AdminPlatformController;
import com.storefront.store.platform.ShopAdministrationService;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.shop.ShopService;
import com.storefront.store.shop.VerificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The admin side of shop verification.
 *
 * <p>{@code @PreAuthorize("hasRole('ROLE_ADMIN')")} on the class, not per method. A new endpoint
 * added here is admin-only by default; making it public would take a deliberate edit rather than
 * a forgotten annotation. That is the same reasoning behind deriving the shop from the token in
 * {@code ShopController}, and it is the reason the Admin Data Explorer (Phase 7) must treat
 * mutating endpoints as dangerous.
 */
@RestController
@RequestMapping("/api/v1/admin/shops")
@Tag(name = "Admin", description = "Platform administration")
@PreAuthorize("hasRole('ROLE_ADMIN')")
@RequiredArgsConstructor
public class AdminShopController {

	private final VerificationService verification;
	private final ShopRepository shops;
	private final ShopService shopService;
	private final ShopAdministrationService administration;

	@GetMapping("/verification-queue")
	@Operation(summary = "Shops waiting for a verification decision",
			description = "The queue an admin works through to keep fake shops out of the gallery.")
	public List<ShopSummary> queue() {
		return verification.pendingReview().stream().map(ShopSummary::from).toList();
	}

	/**
	 * Every shop, filtered and paged.
	 *
	 * <p><b>Replaces Phase 1's {@code List<ShopSummary>}, which returned every row.</b> That was fine
	 * with three shops and wrong with three thousand: an admin's shop list is the screen they open
	 * first and the one that grows fastest, and a list that loads the whole table to display twenty
	 * stops working at exactly the wrong moment. Filtering also moved here from
	 * {@code VerificationService} — verification answers questions about the queue, not about
	 * browsing every shop.
	 *
	 * <p>This endpoint stays on this controller rather than a Phase 7 one. Two controllers mapped to
	 * {@code /api/v1/admin/shops} fail at startup with an "ambiguous mapping" that names neither
	 * duplication as the cause, so there is exactly one of them.
	 */
	@GetMapping
	@Operation(summary = "Every shop, filterable by type, status, featured and name",
			description = "Paged. Returns {content, page, size, totalElements, totalPages}.")
	public com.storefront.store.common.PageResponse<ShopAdministrationService.ShopView> all(
			@RequestParam(required = false) ShopType type,
			@RequestParam(required = false) Shop.Status status,
			@RequestParam(required = false) Boolean featured,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {

		var found = administration.browse(type, status, featured, q, PageRequest.of(page, size));

		return new com.storefront.store.common.PageResponse<>(
				found.getContent(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	/**
	 * Puts a shop at the top of the gallery.
	 *
	 * <p>Not an entitlement and not purchasable — see {@link ShopAdministrationService#feature}.
	 */
	@PostMapping("/{shopId}/feature")
	@Operation(summary = "Put a shop at the top of the gallery",
			description = "An editorial decision. Refused for a shop that is not verified.")
	public ShopAdministrationService.ShopView feature(@PathVariable UUID shopId) {
		return ShopAdministrationService.ShopView.from(administration.feature(shopId));
	}

	@DeleteMapping("/{shopId}/feature")
	@Operation(summary = "Take a shop off the front page")
	public ShopAdministrationService.ShopView unfeature(@PathVariable UUID shopId) {
		return ShopAdministrationService.ShopView.from(administration.unfeature(shopId));
	}

	@PostMapping("/{shopId}/verify")
	@Operation(summary = "Approve a shop, making it visible in the public gallery")
	public ShopSummary verify(@PathVariable UUID shopId) {
		// Audited inside VerificationService, not here -- see the note there. Recording in both
		// places would write two rows for one action, and a log with duplicates in it is a log
		// nobody can count.
		return ShopSummary.from(verification.verify(requireShop(shopId)));
	}

	@PostMapping("/{shopId}/reject")
	@Operation(summary = "Reject a shop, with a reason the owner can act on")
	public ShopSummary reject(@PathVariable UUID shopId, @Valid @RequestBody ReasonRequest request) {
		return ShopSummary.from(verification.reject(requireShop(shopId), request.reason()));
	}

	@PostMapping("/{shopId}/suspend")
	@Operation(summary = "Suspend a shop, e.g. for fraud",
			description = "Hides the shop without deleting its products, orders or ledger.")
	public ShopSummary suspend(@PathVariable UUID shopId, @Valid @RequestBody ReasonRequest request) {
		return ShopSummary.from(verification.suspend(requireShop(shopId), request.reason()));
	}

	private Shop requireShop(UUID shopId) {
		return shops.findById(shopId)
				.orElseThrow(() -> new ShopService.ShopNotFoundException(shopId.toString()));
	}

	public record ReasonRequest(@NotNull @Size(min = 1, max = 1000) String reason) {
	}

	/**
	 * An admin's view of a shop.
	 *
	 * <p>Deliberately wider than {@code ShopResponse}: an admin judging whether a shop is
	 * legitimate needs the type, the status and when it was created. It is still a record rather
	 * than the entity, so a column added to {@code shop} cannot leak into the API by accident.
	 */
	public record ShopSummary(
			UUID id,
			String slug,
			String name,
			com.storefront.store.catalog.ShopType shopType,
			Shop.Status status,
			String verificationNote,
			int followerCount,
			boolean paysCommission,
			java.time.Instant createdAt,
			java.time.Instant verifiedAt) {

		static ShopSummary from(Shop shop) {
			return new ShopSummary(
					shop.getId(),
					shop.getSlug(),
					shop.getName(),
					shop.getType(),
					shop.getStatus(),
					shop.getVerificationNote(),
					shop.getFollowerCount(),
					shop.paysCommission(),
					shop.getCreatedAt(),
					shop.getVerifiedAt());
		}
	}
}
