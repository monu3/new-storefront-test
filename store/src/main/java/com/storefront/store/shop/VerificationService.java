package com.storefront.store.shop;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The verification gate that keeps unverified shops out of the public gallery.
 *
 * <p>This is the plan's answer to "limit fake shops". A shop is created in {@code DRAFT} the
 * moment its owner signs in, and stays invisible until an admin approves it. Publishing is the
 * gate: {@link Shop#verify()} sets both the status and {@code publishedAt}, and the gallery only
 * ever queries {@code VERIFIED} shops.
 *
 * <p>Deliberately not automatic. Auto-approving on signup would make the gate decorative, and a
 * marketplace where anyone can appear as a verified shop loses the trust the badge represents.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationService {

	private final ShopRepository shops;
	private final com.storefront.store.audit.AuditTrail audit;

	/**
	 * Submits a shop for review.
	 *
	 * <p>Refuses a shop that is not in a state that can be submitted, so a rejected shop does
	 * not silently re-enter the queue on every button press.
	 */
	@Transactional
	public Shop submitForReview(Shop shop, String note) {
		if (shop.getStatus() != Shop.Status.DRAFT && shop.getStatus() != Shop.Status.REJECTED) {
			throw new IllegalStateException(
					"A shop in state " + shop.getStatus() + " cannot be submitted for review");
		}
		shop.setVerificationNote(note);
		shop.submitForReview();
		Shop saved = shops.saveAndFlush(shop);
		log.info("Shop {} submitted for review", saved.getId());
		return saved;
	}

	/**
	 * Admin approval. The shop becomes visible in the gallery.
	 *
	 * <p><b>Audited here rather than in the controller.</b> A service is the only layer every caller
	 * passes through. Recording in {@code AdminShopController} would make the log complete for HTTP
	 * and silent for anything else, so Phase 7's exit criterion -- "every privileged action lands in
	 * the audit log" -- would be true of the admin UI and false of the platform.
	 *
	 * <p>It also cannot be bypassed by forgetting: there is no second path to this state change.
	 */
	@Transactional
	public Shop verify(Shop shop) {
		Shop.Status before = shop.getStatus();

		shop.verify();
		Shop saved = shops.saveAndFlush(shop);

		audit.record(com.storefront.store.audit.AuditAction.SHOP_VERIFY, "Shop", saved.getId(),
				"Verified " + saved.getName() + ", making it visible in the gallery",
				null,
				com.storefront.store.audit.AuditTrail.field("status", before),
				com.storefront.store.audit.AuditTrail.field("status", saved.getStatus()));

		log.info("Shop {} verified and published at /s/{}", saved.getId(), saved.getSlug());
		return saved;
	}

	/** Admin rejection, with a reason the owner can act on. */
	@Transactional
	public Shop reject(Shop shop, String reason) {
		Shop.Status before = shop.getStatus();

		shop.reject(reason);
		Shop saved = shops.saveAndFlush(shop);

		audit.record(com.storefront.store.audit.AuditAction.SHOP_REJECT, "Shop", saved.getId(),
				"Rejected " + saved.getName(), reason,
				com.storefront.store.audit.AuditTrail.field("status", before),
				com.storefront.store.audit.AuditTrail.field("status", saved.getStatus()));

		log.info("Shop {} rejected: {}", saved.getId(), reason);
		return saved;
	}

	/**
	 * Administrative suspension, e.g. for fraud. Hides the shop without deleting its data.
	 *
	 * <p><b>The reason is required, and checked here rather than at the endpoint</b>, because "suspend
	 * without a reason" is a mistake made in every caller rather than one, and the rule belongs to
	 * the action rather than to the route.
	 */
	@Transactional
	public Shop suspend(Shop shop, String reason) {
		com.storefront.store.audit.AuditTrail.requireReason(
				com.storefront.store.audit.AuditAction.SHOP_SUSPEND, reason);

		Shop.Status before = shop.getStatus();

		shop.suspend(reason);
		Shop saved = shops.saveAndFlush(shop);

		audit.record(com.storefront.store.audit.AuditAction.SHOP_SUSPEND, "Shop", saved.getId(),
				"Suspended " + saved.getName(), reason,
				com.storefront.store.audit.AuditTrail.field("status", before),
				com.storefront.store.audit.AuditTrail.field("status", saved.getStatus()));

		log.info("Shop {} suspended: {}", saved.getId(), reason);
		return saved;
	}

	/** The admin's review queue: shops waiting for a decision. */
	@Transactional(readOnly = true)
	public List<Shop> pendingReview() {
		return shops.findByStatus(Shop.Status.PENDING_REVIEW);
	}

	@Transactional(readOnly = true)
	public List<Shop> allByStatus(Shop.Status status) {
		return shops.findByStatus(status);
	}
}
