package com.storefront.store.checkout;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Shipping-zone queries.
 *
 * <p>{@link #findDefaultZone} is separate from the city lookup because the fallback is not a
 * "best match": a zone with no cities is <em>the</em> default and is used when nothing else
 * matches. Treating it as a low-priority candidate would mean an empty-city zone could silently
 * lose to a zone whose cities happen to contain a similarly-spelled name.
 *
 * <p><b>These two are native queries, and deliberately so.</b> "A zone with no cities" is
 * {@code cardinality(cities) = 0} on a Postgres {@code text[]}, and JPQL has no array functions
 * at all — there is no portable spelling of this predicate. The alternative, adding a boolean
 * {@code is_default} column, would mean two columns that can disagree with each other and a
 * migration to keep them in step; a database CHECK cannot tie them together either. Since the
 * column type is Postgres-specific anyway, the query is too.
 */
public interface ShippingZoneRepository extends JpaRepository<ShippingZone, UUID> {

	List<ShippingZone> findByShopIdAndActiveTrueOrderBySortOrderAsc(UUID shopId);

	@Query(value = """
			SELECT * FROM shipping_zone
			WHERE shop_id = :shopId AND is_active = true AND cardinality(cities) = 0
			ORDER BY sort_order ASC
			LIMIT 1
			""", nativeQuery = true)
	java.util.Optional<ShippingZone> findDefaultZone(@Param("shopId") UUID shopId);

	@Query(value = """
			SELECT * FROM shipping_zone
			WHERE shop_id = :shopId AND is_active = true AND cardinality(cities) > 0
			ORDER BY sort_order ASC
			""", nativeQuery = true)
	List<ShippingZone> findCityZones(@Param("shopId") UUID shopId);

	long countByShopId(UUID shopId);
}
