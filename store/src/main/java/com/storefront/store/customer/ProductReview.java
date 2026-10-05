package com.storefront.store.customer;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer's rating and comment on a product (Phase 5).
 *
 * <p><b>{@code authorName} is copied, not joined.</b> It survives the user row being deleted. A
 * rating is a statement about a product, and removing it because somebody closed their account
 * deletes the shop's social proof for reasons that have nothing to do with the review.
 *
 * <p><b>One review per person per product</b>, by a partial unique index. For a guest the same
 * guarantee runs off the author name, because a guest cannot be trusted to be signed in to "be the
 * same person" — which is the whole reason a wishlist is not available to guests either.
 *
 * <p><b>Hidden, never deleted.</b> {@code isVisible = false} is moderation, not erasure: a shop that
 * changes its mind about hiding a review should get it back, and a customer who asked a question in
 * their review should not have it vanish without trace.
 */
@Entity
@Table(name = "product_review")
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReview extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	/** Null for a guest review. The row survives account deletion regardless. */
	@Column(name = "user_id")
	private UUID userId;

	/** Copied at write time, so the review outlives the account. */
	@Column(name = "author_name", nullable = false)
	private String authorName;

	/** 1-5, constrained in the database as well as the service. */
	@Column(name = "rating", nullable = false)
	private short rating;

	@Column(name = "body")
	private String body;

	/** From an order that actually contained this product. Not the same as "signed in". */
	@Column(name = "is_verified", nullable = false)
	private boolean verified;

	@Column(name = "is_visible", nullable = false)
	private boolean visible = true;

	public ProductReview(UUID productId, UUID userId, String authorName, int rating, String body) {
		if (productId == null) {
			throw new IllegalArgumentException("A review needs a product");
		}
		if (authorName == null || authorName.isBlank()) {
			throw new IllegalArgumentException("A review needs a name to show");
		}
		if (rating < 1 || rating > 5) {
			// Checked here as well as by a CHECK constraint: a bad rating distorts the average shown
			// on every product card, and it is cheaper to refuse than to average wrongly.
			throw new IllegalArgumentException("A rating must be between 1 and 5, not " + rating);
		}

		this.id = UUID.randomUUID();
		this.productId = productId;
		this.userId = userId;
		this.authorName = authorName.trim();
		this.rating = (short) rating;
		this.body = body == null || body.isBlank() ? null : body.trim();
		this.visible = true;
	}

	/** Marks this as coming from a real purchase. */
	public void markVerified() {
		this.verified = true;
	}

	/** A shop hides a review. The row stays. */
	public void hide() {
		this.visible = false;
	}

	public void show() {
		this.visible = true;
	}

	/** Edits the review, keeping the original timestamp. */
	public void revise(int newRating, String newBody) {
		if (newRating < 1 || newRating > 5) {
			throw new IllegalArgumentException("A rating must be between 1 and 5, not " + newRating);
		}
		this.rating = (short) newRating;
		this.body = newBody == null || newBody.isBlank() ? null : newBody.trim();
	}

	/** The name shown beside the stars. */
	public String displayAuthor() {
		return authorName;
	}
}