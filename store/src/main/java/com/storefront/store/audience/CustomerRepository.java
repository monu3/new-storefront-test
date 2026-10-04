package com.storefront.store.audience;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Audience queries.
 *
 * <p><b>Every method takes a {@code shopId}.</b> This table holds one shop's private view of its
 * own customers, including shop-private notes. An unscoped query here would be a cross-tenant
 * read of the most sensitive table in the system, so there is no "find all" method to misuse.
 *
 * <p>The sort variants are hand-written rather than derived from a {@code Sort} parameter. The
 * audience screen offers "most spent" and "most recent", and both need an index-friendly ORDER BY
 * ({@code total_spent_minor DESC}, {@code last_order_at DESC}); passing a client-chosen sort
 * through would let a caller ask for an ordering no index supports and table-scan the shop's whole
 * customer list.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

	Optional<Customer> findByShopIdAndEmailIgnoreCase(UUID shopId, String email);

	Page<Customer> findByShopIdOrderByTotalSpentMinorDesc(UUID shopId, Pageable pageable);

	/**
	 * Most recent order first, with customers who have never ordered last rather than first.
	 *
	 * <p>A derived query cannot express {@code NULLS LAST} — Spring Data reads {@code NullsLast} as
	 * a property name and fails at startup. This is why the sort that matters most on the audience
	 * screen is the one that needs a hand-written {@code @Query}.
	 */
	@Query("""
			select c from Customer c
			where c.shopId = :shopId
			order by c.lastOrderAt desc nulls last, c.createdAt desc
			""")
	Page<Customer> findMostRecentFirst(@Param("shopId") UUID shopId, Pageable pageable);

	Page<Customer> findByShopIdOrderByCreatedAtDesc(UUID shopId, Pageable pageable);

	/** Only people who have actually bought something. */
	Page<Customer> findByShopIdAndTotalOrdersGreaterThanOrderByTotalSpentMinorDesc(
			UUID shopId, int minimumOrders, Pageable pageable);

	long countByShopId(UUID shopId);

	long countByShopIdAndTotalOrdersGreaterThan(UUID shopId, int orders);

	/**
	 * Aggregate spend and order counts for a shop, in one round trip.
	 *
	 * <p>A {@code SUM} in the database rather than summing a page in Java. The dashboard needs the
	 * shop's lifetime figures while the list shows twenty rows, so the Java version would quietly
	 * report the first twenty customers' spend as the shop's total.
	 */
	@Query("""
			select coalesce(sum(c.totalSpentMinor), 0L),
			       coalesce(sum(c.totalOrders), 0L),
			       count(c)
			from Customer c where c.shopId = :shopId
			""")
	Totals totalsFor(@Param("shopId") UUID shopId);

	/** Repeat-buy rate: customers with two or more orders, over customers with any. */
	@Query("""
			select count(c) from Customer c
			where c.shopId = :shopId and c.totalOrders >= 2
			""")
	long repeatBuyers(@Param("shopId") UUID shopId);

	/**
	 * Three figures the audience header shows.
	 *
	 * <p>Every field is {@code long}. The aggregates behind them are {@code SUM} and {@code COUNT},
	 * which both return {@code Long}, and projecting them into {@code int} fields fails the moment
	 * the numbers get large — which is exactly the shop this screen matters most to. The
	 * {@code coalesce} fallbacks are {@code 0L} for the same reason: {@code 0} is an {@code int}
	 * literal and the sum it is coalescing is not.
	 */
	record Totals(long spentMinor, long orders, long customers) {
	}

	/** For the owner's bulk note edit, restricted to ids this shop owns. */
	List<Customer> findByShopIdAndIdIn(UUID shopId, List<UUID> ids);
}