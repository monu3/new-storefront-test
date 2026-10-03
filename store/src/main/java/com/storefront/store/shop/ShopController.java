package com.storefront.store.shop;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Shop-scoped endpoints, for the owner's own shop only.
 *
 * <p>Every path here resolves the shop from the caller rather than from a request parameter.
 * That is deliberate: accepting a {@code shopId} in the body would make authorisation a
 * per-endpoint decision, and one forgotten {@code @PreAuthorize} would be a cross-tenant read.
 * Deriving it from the token means there is nothing to get wrong.
 */
@RestController
@RequestMapping("/api/v1/shop")
@Tag(name = "Shop", description = "The shop owner's own dashboard and settings")
@RequiredArgsConstructor
public class ShopController {

	private final ShopService shopService;
	private final VerificationService verification;
	private final CurrentUser currentUser;

	/**
	 * The caller's own shop.
	 *
	 * <p>404 rather than 403 when the caller has no shop: "you have not created one yet" is a
	 * different problem from "that is not yours", and the client needs to tell them apart to
	 * show the right screen.
	 */
	@GetMapping("/me")
	@Operation(summary = "The caller's own shop profile")
	public ResponseEntity<ShopResponse> myShop() {
		return shopService.shopsOf(currentUser.require())
				.map(shop -> ResponseEntity.ok(ShopResponse.from(shop)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Creates or completes the shop profile.
	 *
	 * <p>Idempotent by owner, so calling it again after sign-in simply updates the placeholder
	 * shop that was provisioned on first request.
	 */
	@PostMapping("/signup")
	@PreAuthorize("hasRole('SHOP')")
	@Operation(summary = "Create or update the shop profile",
			description = "One owner has one shop, so this updates rather than duplicating.")
	public ShopResponse signUp(@Valid @RequestBody SignUpRequest request) {
		AppUser owner = currentUser.require();
		Shop shop = shopService.signUp(owner, request.name(), request.shopType(),
				request.description(), request.slugPreference());
		return ShopResponse.from(shop);
	}

	@PatchMapping("/me")
	@PreAuthorize("hasRole('SHOP')")
	@Operation(summary = "Update the mutable profile fields",
			description = "Does not change the shop type, status or commission plan.")
	public ShopResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
		Shop shop = shopService.shopsOf(currentUser.require())
				.orElseThrow(() -> new ShopService.ShopNotFoundException("this account"));
		return ShopResponse.from(shopService.updateProfile(shop, request.name(), request.description(),
				request.logoUrl(), request.coverUrl(), request.faviconUrl()));
	}

	@PostMapping("/submit-verification")
	@PreAuthorize("hasRole('SHOP')")
	@Operation(summary = "Submit the shop for admin review",
			description = "The shop stays invisible in the gallery until an admin approves it.")
	public ShopResponse submitForVerification(@RequestBody(required = false) SubmitVerificationRequest request) {
		Shop shop = shopService.shopsOf(currentUser.require())
				.orElseThrow(() -> new ShopService.ShopNotFoundException("this account"));
		String note = request == null ? null : request.note();
		return ShopResponse.from(verification.submitForReview(shop, note));
	}

	// ---------------------------------------------------------------- requests

	public record SignUpRequest(
			@NotBlank @Size(max = 120) String name,
			@NotNull ShopType shopType,
			@Size(max = 1000) String description,
			/** Optional; a readable slug is generated from the name when absent. */
			@Size(max = 50) String slugPreference) {
	}

	public record UpdateProfileRequest(
			@Size(max = 120) String name,
			@Size(max = 1000) String description,
			@Size(max = 500) String logoUrl,
			@Size(max = 500) String coverUrl,
			@Size(max = 500) String faviconUrl) {
	}

	public record SubmitVerificationRequest(@Size(max = 1000) String note) {
	}

	// ---------------------------------------------------------------- response

	/**
	 * What the shop dashboard needs. Deliberately excludes {@code ownerUserId}, the commission
	 * plan's internals and anything else a shop should not be told about.
	 */
	public record ShopResponse(
			UUID id,
			String slug,
			String name,
			String description,
			ShopType shopType,
			Shop.Status status,
			String logoUrl,
			String coverUrl,
			String faviconUrl,
			/** Whether the shop pays commission. The owner needs to know this. */
			boolean paysCommission,
			int followerCount,
			/** Why it was rejected or suspended, when relevant. */
			String verificationNote) {

		static ShopResponse from(Shop shop) {
			return new ShopResponse(
					shop.getId(),
					shop.getSlug(),
					shop.getName(),
					shop.getDescription(),
					shop.getType(),
					shop.getStatus(),
					shop.getLogoUrl(),
					shop.getCoverUrl(),
					shop.getFaviconUrl(),
					shop.paysCommission(),
					shop.getFollowerCount(),
					shop.getVerificationNote());
		}
	}
}
