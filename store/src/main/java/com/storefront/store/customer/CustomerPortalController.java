package com.storefront.store.customer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.Money;
import com.storefront.store.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The customer portal's remaining surfaces: addresses, wishlist, reviews (Phase 5).
 *
 * <p>One controller for three small features rather than three files, because they share a
 * guarantee that is worth stating once: <b>everything here is scoped to the caller</b>. None of it
 * takes a user id, because there is no such thing as asking for somebody else's addresses.
 *
 * <p>Class-level {@code @PreAuthorize} for the customers realm.
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "Customer account", description = "Addresses, wishlist and reviews")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CustomerPortalController {

	private final CustomerAddressService addresses;
	private final WishlistService wishlist;
	private final ReviewService reviews;
	private final CurrentUser currentUser;

	// ------------------------------------------------------------------ addresses

	@GetMapping("/addresses")
	@Operation(summary = "This customer's saved addresses, default first")
	public List<CustomerAddressService.AddressView> listAddresses() {
		return addresses.list().stream().map(CustomerAddressService.AddressView::from).toList();
	}

	/** The default address, for the checkout picker. Null when they have saved none. */
	@GetMapping("/addresses/default")
	@Operation(summary = "The default address, or the newest one")
	public ResponseEntity<CustomerAddressService.AddressView> defaultAddress() {
		CustomerAddress address = addresses.defaultAddress();
		return address == null
				? ResponseEntity.noContent().build()
				: ResponseEntity.ok(CustomerAddressService.AddressView.from(address));
	}

	@PostMapping("/addresses")
	@Operation(summary = "Save an address")
	public ResponseEntity<CustomerAddressService.AddressView> createAddress(
			@Valid @RequestBody AddressRequest body) {

		CustomerAddress created = addresses.create(body.toDraft());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(CustomerAddressService.AddressView.from(created));
	}

	@PutMapping("/addresses/{addressId}")
	@Operation(summary = "Update an address",
			description = "Partial: a field left out keeps its current value.")
	public CustomerAddressService.AddressView updateAddress(
			@PathVariable UUID addressId, @Valid @RequestBody AddressRequest body) {

		return CustomerAddressService.AddressView.from(addresses.update(addressId, body.toDraft()));
	}

	/** Promotes an address to be the default. Separate, because it is the one that changes others. */
	@PatchMapping("/addresses/{addressId}/default")
	@Operation(summary = "Make this the default address")
	public CustomerAddressService.AddressView makeDefault(@PathVariable UUID addressId) {
		return CustomerAddressService.AddressView.from(addresses.makeDefault(addressId));
	}

	@DeleteMapping("/addresses/{addressId}")
	@Operation(summary = "Remove an address",
			description = "Refuses to remove the last one.")
	public void deleteAddress(@PathVariable UUID addressId) {
		addresses.delete(addressId);
	}

	// ------------------------------------------------------------------ wishlist

	@GetMapping("/wishlist")
	@Operation(summary = "This customer's saved products")
	public PageResponse<WishlistView> listWishlist(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

		Page<WishlistRow> found = wishlist.list(page, size);
		return new PageResponse<>(
				found.getContent().stream().map(WishlistView::from).toList(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	/**
	 * Saves a product.
	 *
	 * <p>Always 200, whether it was newly saved or already there. "Already saved" is not a mistake
	 * the customer made — it is what they asked for by tapping again — so it is not an error
	 * status.
	 */
	@PostMapping("/wishlist/{productId}")
	@Operation(summary = "Save a product to the wishlist")
	public WishlistResult addToWishlist(@PathVariable UUID productId) {
		boolean added = wishlist.add(productId);
		return new WishlistResult(added, wishlist.count(),
				added ? "Saved to your wishlist." : "That was already on your wishlist.");
	}

	@DeleteMapping("/wishlist/{productId}")
	@Operation(summary = "Remove a product from the wishlist",
			description = "Removing something that is not there is not an error.")
	public WishlistResult removeFromWishlist(@PathVariable UUID productId) {
		wishlist.remove(productId);
		return new WishlistResult(true, wishlist.count(), "Removed from your wishlist.");
	}

	/**
	 * Which of these products are already saved.
	 *
	 * <p>Exists because every product card shows a wishlist heart, and asking per card would be one
	 * query per card. One request per page instead.
	 */
	@GetMapping("/wishlist/which")
	@Operation(summary = "Which of these products are on the wishlist")
	public java.util.Set<UUID> whichSaved(@RequestParam List<UUID> productIds) {
		return wishlist.savedProductIds(productIds);
	}

	// ------------------------------------------------------------------ reviews

	@GetMapping("/reviews/{productId}")
	@Operation(summary = "A product's visible reviews, newest first")
	public PageResponse<ReviewView> listReviews(
			@PathVariable UUID productId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

		Page<ProductReview> found = reviews.listFor(productId, page, size);
		return new PageResponse<>(
				found.getContent().stream().map(ReviewView::from).toList(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	@GetMapping("/reviews/{productId}/summary")
	@Operation(summary = "A product's average rating and review count")
	public RatingView reviewSummary(@PathVariable UUID productId) {
		return RatingView.from(reviews.summaryFor(productId));
	}

	/** The caller's own review of a product, or null when they have not left one. */
	@GetMapping("/reviews/{productId}/mine")
	@Operation(summary = "This customer's own review of a product")
	public ResponseEntity<ReviewView> myReview(@PathVariable UUID productId) {
		ProductReview review = reviews.ownReview(productId);
		return review == null
				? ResponseEntity.noContent().build()
				: ResponseEntity.ok(ReviewView.from(review));
	}

	/**
	 * Leaves a review, or edits the one already there.
	 *
	 * <p>201 either way is wrong for an edit, so this returns 200 with the review: the caller can
	 * tell what it has by whether the review existed, and the storefront shows it immediately.
	 */
	@PostMapping("/reviews/{productId}")
	@Operation(summary = "Leave or edit a review",
			description = "Marked 'verified purchase' only if this customer actually bought the product.")
	public ReviewView writeReview(@PathVariable UUID productId,
			@Valid @RequestBody ReviewRequest body) {

		return ReviewView.from(reviews.write(productId, body.rating(), body.body(), body.authorName()));
	}

	// ------------------------------------------------------------------ types

	public record AddressRequest(
			@Size(max = 40) String label,
			@Size(max = 120) String recipient,
			@Size(max = 40) String phone,
			@Size(max = 200) String line1,
			@Size(max = 200) String line2,
			@Size(max = 100) String city,
			@Size(max = 100) String state,
			@Size(max = 20) String postalCode,
			@Size(max = 80) String country,
			Boolean isDefault) {

		CustomerAddressService.AddressDraft toDraft() {
			return new CustomerAddressService.AddressDraft(label, recipient, phone, line1, line2,
					city, state, postalCode, country, isDefault);
		}
	}

	public record ReviewRequest(
			@Min(1) @Max(5) int rating,
			@Size(max = 2000) String body,
			/** Used for a guest, or when a signed-in customer wants a different display name. */
			@Size(max = 80) String authorName) {
	}

	public record WishlistView(String productId, String productName, String productSlug,
			long priceMinor, String priceDisplay, String shortDescription,
			String imageUrl, java.time.Instant savedAt) {

		static WishlistView from(WishlistRow entry) {
			return new WishlistView(
					entry.productId().toString(),
					entry.productName(),
					entry.productSlug(),
					entry.priceMinor(),
					Money.ofMinor(entry.priceMinor()).toDisplay(),
					entry.shortDescription(),
					entry.imageUrl(),
					entry.savedAt());
		}
	}

	public record WishlistResult(boolean added, long count, String message) {
	}

	public record ReviewView(String id, String productId, String authorName, int rating,
			String body, boolean verified, java.time.Instant createdAt) {

		static ReviewView from(ProductReview review) {
			return new ReviewView(
					review.getId().toString(),
					review.getProductId().toString(),
					review.displayAuthor(),
					review.getRating(),
					review.getBody(),
					review.isVerified(),
					review.getCreatedAt());
		}
	}

	public record RatingView(String productId, double average, long count, String display) {

		static RatingView from(RatingSummary summary) {
			// One decimal place, because "4.3" is a rating and "4.3333" is a spreadsheet.
			String display = summary.count() == 0
					? "No reviews yet"
					: String.format(java.util.Locale.ROOT, "%.1f", summary.average());
			return new RatingView(summary.productId().toString(), summary.average(),
					summary.count(), display);
		}
	}

	public record PageResponse<T>(
			List<T> content,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}
}