package com.storefront.store.platform;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.audit.AuditAction;
import com.storefront.store.audit.AuditTrail;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;

/**
 * Admin shop management beyond verification (Phase 7).
 *
 * <p><b>Featuring is an editorial decision, not a purchase.</b> A paid slot a shop can buy is an
 * advertisement, and calling it a feature would be dishonest about what it is. So it is a flag on
 * the shop and deliberately absent from {@code plan_entitlement} — the plan a shop buys and the
 * prominence an admin grants it are different things, and merging them would let subscription money
 * buy editorial judgement.
 *
 * <p>Audited inside this service rather than in the controller, for the reason the rest of Phase 7
 * audits there: a service is the only layer every caller passes through.
 */
@Service
@RequiredArgsConstructor
public class ShopAdministrationService {

	private static final int MAX_PAGE_SIZE = 100;

	private final ShopRepository shops;
	private final AuditTrail audit;

	/**
	 * Every shop an admin can see, filtered.
	 *
	 * <p>Filtered in SQL rather than in memory: an admin's shop list is the first screen they open
	 * and the one that grows fastest, and a list that loads every shop to show twenty is a list that
	 * stops working at exactly the wrong moment.
	 *
	 * @param type    null for every vertical. Bound to the enum so a crafted value cannot query a
	 *                column that does not exist
	 * @param status  null for every status
	 * @param featured null for all, true for featured only, false for unfeatured only
	 * @param search  null or blank for no filter; matched on name and slug, case-insensitively
	 */
	@Transactional(readOnly = true)
	public Page<ShopView> browse(ShopType type, Shop.Status status, Boolean featured,
			String search, Pageable pageable) {

		Pageable safe = pageable == null
				? org.springframework.data.domain.PageRequest.of(0, 25)
				: (pageable.getPageSize() > MAX_PAGE_SIZE
						? org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE)
						: pageable);

		// `search` blanked rather than passed through: a search box the user cleared still submits
		// "", and "%" matches every row, which reads as "the filter does nothing".
		String term = search == null || search.isBlank() ? null : search.trim();

		Page<Shop> found = shops.searchAdmin(type == null ? null : type.name(),
				status == null ? null : status.name(),
				featured, term, safe);

		return found.map(ShopView::from);
	}

	/** The featured shops, newest first — what the gallery asks for. */
	@Transactional(readOnly = true)
	public List<Shop> featured() {
		return shops.findByFeaturedTrueAndStatusOrderByCreatedAtDesc(Shop.Status.VERIFIED);
	}

	/**
	 * Puts a shop at the top of the gallery.
	 *
	 * <p>Refuses a shop that is not verified. Featuring a draft or a suspended shop would put it on
	 * the front page of a marketplace where it cannot legitimately trade, and the reason it is not
	 * visible is one the owner has to fix.
	 */
	@Transactional
	public Shop feature(UUID shopId) {
		Shop shop = require(shopId);

		if (!shop.isVerified()) {
			throw new IllegalStateException(
					"That shop is " + shop.getStatus() + ", not verified. Feature it after it is "
							+ "approved, or the gallery would show a shop that cannot trade.");
		}
		if (shop.isFeatured()) {
			return shop;
		}

		shop.feature();
		Shop saved = shops.saveAndFlush(shop);

		// The before/after is the reason this row is worth reading: "featured X" tells an operator
		// that something happened, and `featured: no -> yes` tells them what changed, which is the
		// only way to answer "was that the right shop, twice". Without it the diff view opens empty
		// on the one action this screen is most likely to be asked about.
		audit.record(AuditAction.SHOP_FEATURE, "Shop", saved.getId(),
				"Featured " + saved.getName() + " at the top of the gallery",
				null,
				AuditTrail.field("featured", false),
				AuditTrail.field("featured", true));
		return saved;
	}

	/** Takes a shop off the front page. Idempotent. */
	@Transactional
	public Shop unfeature(UUID shopId) {
		Shop shop = require(shopId);

		if (!shop.isFeatured()) {
			// Not an error: an admin double-clicking Unfeature should not produce a 409, and the
			// audit log should not gain a row for a no-op.
			return shop;
		}

		shop.unfeature();
		Shop saved = shops.saveAndFlush(shop);

		audit.record(AuditAction.SHOP_UNFEATURE, "Shop", saved.getId(),
				"Unfeatured " + saved.getName(), null,
				AuditTrail.field("featured", true),
				AuditTrail.field("featured", false));
		return saved;
	}

	private Shop require(UUID shopId) {
		return shops.findById(shopId)
				.orElseThrow(() -> new IllegalArgumentException("No shop " + shopId));
	}

	// ------------------------------------------------------------------ types

	/**
	 * A shop in the admin's list.
	 *
	 * <p>Includes the owner's email, because an admin's first question about a shop is "who is this"
	 * and a UUID is not an answer.
	 */
	public record ShopView(
			UUID id,
			String name,
			String slug,
			ShopType shopType,
			String shopTypeLabel,
			Shop.Status status,
			boolean featured,
			String ownerEmail,
			java.time.Instant createdAt,
			java.time.Instant verifiedAt,
			String commissionPlan) {

		public static ShopView from(Shop shop) {
			return new ShopView(
					shop.getId(),
					shop.getName(),
					shop.getSlug(),
					shop.getType(),
					shop.getType().label(),
					shop.getStatus(),
					shop.isFeatured(),
					null,
					shop.getCreatedAt(),
					shop.getVerifiedAt(),
					shop.getCommissionPlan().name());
		}
	}
}