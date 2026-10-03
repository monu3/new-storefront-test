package com.storefront.store.shop;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.storefront.store.catalog.ShopType;

/**
 * Shop queries.
 *
 * <p>Every method that lists shops accepts a {@code Pageable}, because shop and order lists
 * grow without bound and offset pagination degrades badly on them. Callers page with a cursor.
 */
public interface ShopRepository extends JpaRepository<Shop, UUID> {

	Optional<Shop> findBySlug(String slug);

	Optional<Shop> findByOwnerUserId(UUID ownerUserId);

	boolean existsBySlug(String slug);

	/** Only verified shops appear in the public gallery. */
	Page<Shop> findByStatusOrderByNameAsc(Shop.Status status, Pageable pageable);

	Page<Shop> findByStatusAndShopTypeOrderByNameAsc(Shop.Status status, String shopType, Pageable pageable);

	Page<Shop> findByStatus(Shop.Status status, Pageable pageable);

	List<Shop> findByStatus(Shop.Status status);

	long countByStatus(Shop.Status status);

	long countByShopType(String shopType);

	/**
	 * Increments the follower counter in the database (D13).
	 *
	 * <p>An atomic UPDATE rather than a read-modify-write on the entity, because two customers
	 * following at the same moment would otherwise race and lose a count — and a follower count
	 * that silently drifts is worse than one that is never displayed precisely.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Shop s SET s.followerCount = s.followerCount + 1 WHERE s.id = :shopId")
	int incrementFollowerCount(@Param("shopId") UUID shopId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE Shop s
			SET s.followerCount = CASE WHEN s.followerCount > 0 THEN s.followerCount - 1 ELSE 0 END
			WHERE s.id = :shopId
			""")
	int decrementFollowerCount(@Param("shopId") UUID shopId);

	/**
	 * Sets the follower counter outright.
	 *
	 * <p>Used only by reconciliation, which writes the true row count over the cached column.
	 * Increments and decrements are the normal path; this exists so a drifted counter can be
	 * repaired without a pile of compensating adjustments.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Shop s SET s.followerCount = :count WHERE s.id = :shopId")
	int updateFollowerCount(@Param("shopId") UUID shopId, @Param("count") int count);

	default Optional<Shop> findBySlugIgnoringCase(String slug) {
		return findBySlug(slug == null ? null : slug.toLowerCase(java.util.Locale.ROOT));
	}

	Page<Shop> findByShopType(String shopType, Pageable pageable);
}
