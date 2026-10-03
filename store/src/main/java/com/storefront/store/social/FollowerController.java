package com.storefront.store.social;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Following a shop, and seeing who has.
 *
 * <p>D13 scopes this to a number: follow, unfollow, and a count. No feed, no ranking, no rewards —
 * those are Phase 9, and building them now would mean migrating the follower table twice.
 *
 * <p>A CUSTOMER token is required. The ops realm is for shops and admins, so an ops token cannot
 * follow: following is a customer's relationship with a shop, not a shop's relationship with
 * itself.
 */
@RestController
@RequestMapping("/api/v1/shops")
@Tag(name = "Followers", description = "Follow a shop; see the count")
@RequiredArgsConstructor
public class FollowerController {

	private final FollowerService followers;
	private final ShopRepository shops;
	private final com.storefront.store.security.CurrentUser currentUser;

	/**
	 * Follows a shop. Idempotent — following twice is a no-op, not a second follow.
	 *
	 * <p>204 either way, because from the client's point of nothing changed on the second call.
	 * Returning 201 the first time and 204 after would be more informative but is a distinction
	 * almost no client needs, and an inconsistent status code is worse than a slightly coarse one.
	 */
	@PostMapping("/{shopId}/follow")
	@PreAuthorize("hasRole('CUSTOMER')")
	@Operation(summary = "Follow a shop",
			description = "Idempotent. Returns 404 for a shop that does not exist.")
	public org.springframework.http.ResponseEntity<Void> follow(@PathVariable UUID shopId) {
		UUID userId = currentUser.optionalUserId();
		Shop shop = shops.findById(shopId)
				// 404, not 403: an unverified shop must be indistinguishable from one that was
				// never created, or a customer can enumerate hidden shops by trying to follow them.
				.filter(Shop::isVerified)
				.orElseThrow(() -> new ShopNotFoundForFollowerException(shopId));

		followers.follow(shop.getId(), userId);
		return org.springframework.http.ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{shopId}/follow")
	@PreAuthorize("hasRole('CUSTOMER')")
	@Operation(summary = "Unfollow a shop. Idempotent.")
	public org.springframework.http.ResponseEntity<Void> unfollow(@PathVariable UUID shopId) {
		followers.unfollow(shopId, currentUser.optionalUserId());
		return org.springframework.http.ResponseEntity.noContent().build();
	}

	/**
	 * The follow state and count for a shop.
	 *
	 * <p>Public, so a storefront can render "142 followers · Follow" without a token. Whether the
	 * <em>caller</em> follows is only reported when there is one, since "following: false" is
	 * wrong for an anonymous visitor.
	 */
	@GetMapping("/{shopId}/follow")
	@Operation(summary = "Follower count, and whether the caller follows this shop")
	public FollowState state(@PathVariable UUID shopId) {
		Shop shop = shops.findById(shopId).filter(Shop::isVerified).orElseThrow();
		UUID userId = currentUser.optionalUserId();

		return new FollowState(
				shop.getFollowerCount(),
				followers.countOf(shopId),
				userId == null ? null : followers.isFollowing(shopId, userId));
	}

	/**
	 * @param count  the shop's cached counter, what the UI displays
	 * @param actual the true row count, so a drift is visible rather than invisible
	 * @param following null when the caller is anonymous
	 */
	public record FollowState(int count, long actual, Boolean following) {
	}

	/** Maps to HTTP 404. */
	public static class ShopNotFoundForFollowerException extends RuntimeException {
		public ShopNotFoundForFollowerException(UUID shopId) {
			super("No shop " + shopId);
		}
	}
}
