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

	/**
	 * The admin's shop list, filtered in SQL (Phase 7).
	 *
	 * <p>Every filter nullable, all applied in one query. An admin's shop list is the first screen
	 * they open and the one that grows fastest; loading every shop to display twenty is a list that
	 * stops working at exactly the wrong moment.
	 *
	 * <p><b>{@code search} blanks rather than passes through</b>: a cleared search box still submits
	 * an empty string, and {@code '%'} matches every row — which reads as "the filter does nothing".
	 *
	 * <p>Owner email is fetched per shop by the service rather than joined here, because it lives on
	 * {@code app_user} and this query is on {@code shop}; a join for a column shown in one cell of a
	 * list is a join the database has to plan for every page.
	 *
	 * @param type     null for every vertical
	 * @param status   null for every status
	 * @param featured null for all, true for featured only, false for unfeatured only
	 * @param search   null or blank for no text filter, matched on name and slug
	 */
	//
	// NATIVE, not JPQL. `Shop.type` and `Shop.status` both carry converters, and JPQL compares a
	// converted attribute against the declared parameter type -- so `:type` bound as a String fails
	// query validation at startup with "validation failed for method ...", which names neither the
	// converter nor the parameter. The columns are text holding the enum name, so native compares
	// them directly and honestly.
	//
	@Query(value = """
			SELECT s.* FROM shop s
			WHERE (:type IS NULL OR s.shop_type = :type)
			  AND (:status IS NULL OR s.status = :status)
			  AND (:featured IS NULL OR s.is_featured = :featured)
			  AND (:search IS NULL
			       OR lower(s.name) LIKE lower('%' || :search || '%')
			       OR lower(s.slug::text) LIKE lower('%' || :search || '%'))
			ORDER BY s.created_at DESC
			""", nativeQuery = true)
	Page<Shop> searchAdmin(@Param("type") String type,
			@Param("status") String status,
			@Param("featured") Boolean featured,
			@Param("search") String search,
			Pageable pageable);

	/** The featured, verified shops the gallery shows first. */
	List<Shop> findByFeaturedTrueAndStatusOrderByCreatedAtDesc(Shop.Status status);
}
