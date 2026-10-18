package com.storefront.store.campaign;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Campaigns (Phase 8).
 *
 * <p>No delete method. A campaign that ran and generated orders cannot be removed — the orders that
 * referenced its discount would have no explanation left.
 */
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

	/**
	 * What a storefront shows right now: the shop's own campaigns plus platform-wide ones.
	 *
	 * <p><b>The window is in the query, not filtered afterwards.</b> "What is running" is asked on
	 * every storefront render, so loading every campaign a shop has ever made and discarding the
	 * finished ones makes the busiest shop the slowest one to load.
	 *
	 * <p>Half-open, matching {@code Campaign.isLiveAt}: a campaign is over the instant it ends, not
	 * one instant after.
	 */
	@Query("""
			select c from Campaign c
			where (c.shopId is null or c.shopId = :shopId)
			  and c.startsAt <= :now
			  and (c.endsAt is null or c.endsAt > :now)
			order by case when c.shopId is null then 0 else 1 end, c.startsAt desc
			""")
	List<Campaign> liveFor(@Param("shopId") UUID shopId, @Param("now") Instant now);

	/** A shop's own campaigns, newest first. */
	Page<Campaign> findByShopIdOrderByStartsAtDesc(UUID shopId, Pageable pageable);

	/** Everything platform-wide, for the admin screen. */
	Page<Campaign> findByShopIdIsNullOrderByStartsAtDesc(Pageable pageable);
}
