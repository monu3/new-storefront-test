package com.storefront.store.social;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Following a shop, and the counter that goes with it (D13).
 *
 * <p><b>The counter is incremented only when a row was actually inserted.</b> Following twice is
 * common — a customer double-taps, a retry arrives — and incrementing unconditionally is how a
 * follower count drifts above the number of real followers. A count that overstates a shop's
 * audience is worse than one that is imprecise, because the owner trusts it.
 *
 * <p>So: check, insert, and only on a genuinely new row increment. The insert's unique constraint
 * catches the race where two requests pass the check at the same moment, and the loser does not
 * increment — which is what makes the two stay in agreement under concurrency rather than only
 * in the sequential case.
 *
 * <p>The increment itself is an atomic UPDATE in SQL, not a read-modify-write on the entity,
 * because two customers following the same shop at once would otherwise lose a count.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FollowerService {

	private final ShopFollowerRepository followers;
	private final ShopRepository shops;

	/**
	 * Follows a shop. Idempotent.
	 *
	 * @return true when this call created the follow, false when they already followed
	 */
	@Transactional
	public boolean follow(UUID shopId, UUID userId) {
		if (shopId == null || userId == null) {
			throw new IllegalArgumentException("Following needs both a shop and a user");
		}
		if (!shops.existsById(shopId)) {
			throw new IllegalArgumentException("No such shop: " + shopId);
		}
		if (followers.existsByShopIdAndUserId(shopId, userId)) {
			return false;
		}

		try {
			// saveAndFlush, not save: the flush is what surfaces the unique-constraint violation,
			// which is how the concurrent double-follow is detected.
			followers.saveAndFlush(new ShopFollower(shopId, userId));
		} catch (DataIntegrityViolationException e) {
			// Another request inserted the same row between our check and our insert.
			log.debug("Follow of {} by {} already recorded concurrently", shopId, userId);
			return false;
		}

		shops.incrementFollowerCount(shopId);
		log.info("User {} followed shop {} (count now {})", userId, shopId, countOf(shopId));
		return true;
	}

	/**
	 * Unfollows. Idempotent.
	 *
	 * @return true when this call removed the follow, false when they had not followed
	 */
	@Transactional
	public boolean unfollow(UUID shopId, UUID userId) {
		if (shopId == null || userId == null) {
			throw new IllegalArgumentException("Unfollowing needs both a shop and a user");
		}
		if (followers.findByUserIdAndShopId(userId, shopId).isEmpty()) {
			return false;
		}
		followers.deleteById(new ShopFollower.Key(shopId, userId));
		followers.flush();

		// The repository's decrement is already clamped at zero, so a double unfollow cannot
		// drive the counter negative.
		shops.decrementFollowerCount(shopId);
		return true;
	}

	@Transactional(readOnly = true)
	public boolean isFollowing(UUID shopId, UUID userId) {
		return followers.existsByShopIdAndUserId(shopId, userId);
	}

	/**
	 * The authoritative count: the rows, not the cached column.
	 *
	 * <p>{@code shop.follower_count} exists so the gallery can sort by it without a subquery.
	 * This method counts the rows, so it is what a reconciliation compares the two — and it is
	 * what the tests assert against, because asserting a cache against itself proves nothing.
	 */
	@Transactional(readOnly = true)
	public long countOf(UUID shopId) {
		return followers.countByShopId(shopId);
	}

	/** The shop's denormalised count, for display. */
	@Transactional(readOnly = true)
	public int displayedCount(Shop shop) {
		return shop.getFollowerCount();
	}

	@Transactional(readOnly = true)
	public long countFollowersOf(UUID userId) {
		return followers.countByUserId(userId);
	}

	/**
	 * Repairs the cached counter from the rows.
	 *
	 * <p>Exists because the two are separate things that happen to be kept in step. A scheduled
	 * reconciliation would run this nightly, the same as the wallet's; in Phase 1 it is a method
	 * a test can call, which is enough to prove the two agree.
	 */
	@Transactional
	public int reconcileCounter(UUID shopId) {
		long actual = followers.countByShopId(shopId);
		shops.updateFollowerCount(shopId, (int) actual);
		Shop shop = shops.findById(shopId).orElseThrow();
		int previous = shop.getFollowerCount();
		log.info("Follower counter for shop {} reconciled: {} -> {}", shopId, previous, actual);
		return previous;
	}
}
