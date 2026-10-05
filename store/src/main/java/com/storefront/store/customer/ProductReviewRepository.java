package com.storefront.store.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Product reviews.
 *
 * <p><b>The average is computed in SQL.</b> A product card shows "4.3 from 128" on every page of
 * the catalogue, so an average summed from a page of reviews would be wrong for every product with
 * more reviews than fit on one page — which is exactly the products whose reputation matters.
 */
public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID> {

	Page<ProductReview> findByProductIdAndVisibleTrueOrderByCreatedAtDesc(
			UUID productId, Pageable pageable);

	Optional<ProductReview> findByProductIdAndUserIdAndVisibleTrue(UUID productId, UUID userId);

	/**
	 * A customer's own review whether or not the shop has hidden it.
	 *
	 * <p>Used when they try to write again. Editing must find the hidden row too: creating a second
	 * one would be refused by the unique index, and reporting "you have already reviewed this" is
	 * both true and unhelpful when they cannot see their review to fix it.
	 */
	Optional<ProductReview> findByProductIdAndUserId(UUID productId, UUID userId);

	long countByProductIdAndVisibleTrue(UUID productId);

	boolean existsByProductIdAndUserId(UUID productId, UUID userId);

	/**
	 * The visible average and count for a product.
	 *
	 * <p>{@code coalesce} because a product with no reviews has no {@code AVG} at all, and
	 * {@code count} returns {@code long} where {@code avg} returns {@code Double} — the two must be
	 * brought to the same shape or the projection will not map.
	 */
	@Query("""
			select coalesce(avg(r.rating), 0.0), count(r)
			from ProductReview r
			where r.productId = :productId and r.visible = true
			""")
	RatingSummary summaryFor(@Param("productId") UUID productId);

	/** The averages for a whole page of products, in one query rather than one per card. */
	@Query("""
			select r.productId, coalesce(avg(r.rating), 0.0), count(r)
			from ProductReview r
			where r.productId in :productIds and r.visible = true
			group by r.productId
			""")
	List<RatingSummary> summariesFor(@Param("productIds") List<UUID> productIds);

	/** A product's reviews hidden by the shop owner, for the moderation screen. */
	Page<ProductReview> findByProductIdAndVisibleFalseOrderByCreatedAtDesc(
			UUID productId, Pageable pageable);

	/** Marks reviews hidden or shown again, for a shop's moderation actions. */
	@org.springframework.data.jpa.repository.Modifying
	@Query("update ProductReview r set r.visible = :visible where r.id = :reviewId and r.productId = :productId")
	int setVisibility(@Param("productId") UUID productId, @Param("reviewId") UUID reviewId,
			@Param("visible") boolean visible);

}
