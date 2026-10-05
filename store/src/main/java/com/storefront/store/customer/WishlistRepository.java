package com.storefront.store.customer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.storefront.store.catalog.Product;

import jakarta.persistence.Column;

/**
 * A customer's wishlist (Phase 5).
 *
 * <p>Deliberately stores only {@code (user_id, product_id)}. <b>No variant.</b> A wishlist holds
 * products, not variants: a customer saving "the red one" has usually saved the product and
 * picked a variant at checkout, and pinning the variant makes the item undeletable when that
 * variant is retired. The storefront shows each product's variants at the point of adding it.
 */
public interface WishlistRepository extends JpaRepository<WishlistItem, UUID> {

	Page<WishlistItem> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

	boolean existsByUserIdAndProductId(UUID userId, UUID productId);

	long countByUserId(UUID userId);

	void deleteByUserIdAndProductId(UUID userId, UUID productId);

	/**
	 * The wishlist joined to its products, in one query.
	 *
	 * <p>A projection rather than an entity load per item: a fifty-item wishlist fetched as
	 * fifty separate queries is the difference between a page that opens instantly and one that
	 * does not.
	 */
	/**
	 * The wishlist joined to its products, in one query.
	 *
	 * <p>A projection rather than loading a product per item: a fifty-item wishlist fetched as
	 * fifty separate queries is a page that takes seconds.
	 *
	 * <p><b>No image in the projection.</b> {@code product} has no image column — images are media
	 * rows — so the thumbnail is resolved afterwards with one batched media query for the page. The
	 * alternative, joining media here, would multiply the rows for a product with several images.
	 */
	@Query("""
			select new com.storefront.store.customer.WishlistEntry(
			    i.id, i.createdAt, p.id, p.name, p.slug, p.basePriceMinor, p.shortDescription
			)
			from WishlistItem i
			join Product p on p.id = i.productId
			where i.userId = :userId
			order by i.createdAt desc
			""")
	Page<WishlistEntry> browse(@Param("userId") UUID userId, Pageable pageable);

}
