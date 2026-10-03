package com.storefront.store.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.shop.Shop;
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

	@GetMapping("/verification-queue")
	@Operation(summary = "Shops waiting for a verification decision",
			description = "The queue an admin works through to keep fake shops out of the gallery.")
	public List<ShopSummary> queue() {
		return verification.pendingReview().stream().map(ShopSummary::from).toList();
	}

	@GetMapping
	@Operation(summary = "All shops, optionally filtered by status")
	public List<ShopSummary> all(@RequestParam(required = false) Shop.Status status) {
		List<Shop> found = status == null
				? shops.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
				: verification.allByStatus(status);
		return found.stream().map(ShopSummary::from).toList();
	}

	@PostMapping("/{shopId}/verify")
	@Operation(summary = "Approve a shop, making it visible in the public gallery")
	public ShopSummary verify(@PathVariable UUID shopId) {
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
