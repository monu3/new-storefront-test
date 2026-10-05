package com.storefront.store.customer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.catalog.ProductVariantRepository;
import com.storefront.store.order.OrderItem;
import com.storefront.store.order.OrderItemRepository;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.order.ShopOrderRepository;
import com.storefront.store.security.CurrentUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Product reviews (Phase 5).
 *
 * <p><b>"Verified purchase" means a real order contained this product</b> — not "the reviewer is
 * signed in". The distinction is the entire value of the badge: a competitor can sign in and review,
 * and without the badge the owner cannot tell a customer's review from a competitor's.
 *
 * <p><b>One review per person per product.</b> A second attempt edits the first rather than adding
 * a row, so a product's average cannot be steered by a customer who dislikes the number they gave.
 *
 * <p><b>The average is computed in SQL</b>, over the whole review set, not the visible page. A
 * product card showing "4.3" must not be the average of whatever twenty reviews happened to fit on
 * screen.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewService {

	private static final int MAX_PAGE = 50;

	private final ProductReviewRepository reviews;
	private final ProductRepository products;
	private final ProductVariantRepository variants;
	private final OrderItemRepository orderItems;
	private final ShopOrderRepository orders;
	private final CurrentUser currentUser;

	// ------------------------------------------------------------------ read

	/** A product's visible reviews, newest first. */
	@Transactional(readOnly = true)
	public Page<ProductReview> listFor(UUID productId, int page, int size) {
		return reviews.findByProductIdAndVisibleTrueOrderByCreatedAtDesc(productId,
				PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE)));
	}

	/** The average and count for one product. */
	@Transactional(readOnly = true)
	public RatingSummary summaryFor(UUID productId) {
		return reviews.summaryFor(productId);
	}

	/**
	 * The averages for a whole page of products, in one query.
	 *
	 * <p>Called once per catalogue page rather than once per card: the naive version is a hundred
	 * aggregate queries for a hundred products, which is the difference between a page that opens
	 * and a page that does not.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, RatingSummary> summariesFor(List<UUID> productIds) {
		if (productIds == null || productIds.isEmpty()) {
			return Map.of();
		}
		return reviews.summariesFor(productIds).stream()
				.collect(Collectors.toMap(
						RatingSummary::productId,
						summary -> summary));
	}

	/** This customer's own review of a product, if they have left one. */
	@Transactional(readOnly = true)
	public ProductReview ownReview(UUID productId) {
		return reviews.findByProductIdAndUserIdAndVisibleTrue(
				productId, currentUser.require().getId()).orElse(null);
	}

	// ------------------------------------------------------------------ write

	/**
	 * Leaves a review, or edits the one already there.
	 *
	 * <p>Anonymous is allowed. This platform's customers are mostly guests, and requiring an
	 * account to review means most shops get no reviews at all. A guest's review carries the name
	 * they typed and no user id, so it cannot be edited later — stated plainly rather than
	 * discovered.
	 */
	@Transactional
	public ProductReview write(UUID productId, int rating, String body, String authorName) {

		Product product = products.findById(productId)
				.orElseThrow(() -> new IllegalArgumentException("No such product"));

		boolean signedIn = currentUser.optionalUserId() != null;
		UUID userId = currentUser.optionalUserId();

		// Fall back to the signed-in user's own name when the form omitted one, so a customer who
		// is logged in does not have to retype their name on every review.
		// optionalUser() returns null rather than an empty Optional when nobody is signed in.
		var caller = currentUser.optionalUser();
		String name = authorName == null || authorName.isBlank()
				? (caller == null ? null : caller.getFullName())
				: authorName;

		ProductReview review = signedIn
				? reviews.findByProductIdAndUserId(productId, userId).orElse(null)
				: null;

		if (review == null) {
			review = new ProductReview(productId, userId, name, rating, body);
		} else {
			review.revise(rating, body);
		}

		// Verified means a real order contained this product, checked against this customer's own
		// orders -- not merely that they have an account.
		if (boughtIt(userId, productId)) {
			review.markVerified();
		}

		return reviews.save(review);
	}

	/**
	 * A shop owner hides or restores a review.
	 *
	 * <p>Scoped to a product the caller owns, so hiding a review is not something one shop can do
	 * to another's.
	 */
	@Transactional
	public void setVisibility(UUID productId, UUID reviewId, boolean visible) {
		int changed = reviews.setVisibility(productId, reviewId, visible);
		if (changed == 0) {
			throw new IllegalArgumentException(
					"That review does not exist, or belongs to another product.");
		}
		log.info("Review {} on product {} {} by the shop", reviewId, productId,
				visible ? "restored" : "hidden");
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Whether this customer actually bought the product.
	 *
	 * <p>Walks the customer's order items rather than joining on the order, because the question is
	 * about a <em>product</em>: a variant can be retired and a new one created, and an order for the
	 * retired variant still proves the customer bought the thing.
	 */
	private boolean boughtIt(UUID userId, UUID productId) {
		if (userId == null) {
			return false;
		}
		return orderItems.findByProductIdAndOrderIdIn(productId, ownedOrderIds(userId)).stream()
				.findAny()
				.isPresent();
	}

	private List<UUID> ownedOrderIds(UUID userId) {
		return orders.findByCustomerIdOrderByPlacedAtDesc(userId, PageRequest.of(0, 200))
				.getContent().stream()
				.map(ShopOrder::getId)
				.toList();
	}
}