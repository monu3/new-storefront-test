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

	/** Admin approval. The shop becomes visible in the gallery. */
	@Transactional
	public Shop verify(Shop shop) {
		shop.verify();
		Shop saved = shops.saveAndFlush(shop);
		log.info("Shop {} verified and published at /s/{}", saved.getId(), saved.getSlug());
		return saved;
	}

	/** Admin rejection, with a reason the owner can act on. */
	@Transactional
	public Shop reject(Shop shop, String reason) {
		shop.reject(reason);
		Shop saved = shops.saveAndFlush(shop);
		log.info("Shop {} rejected: {}", saved.getId(), reason);
		return saved;
	}

	/** Administrative suspension, e.g. for fraud. Hides the shop without deleting its data. */
	@Transactional
	public Shop suspend(Shop shop, String reason) {
		shop.suspend(reason);
		Shop saved = shops.saveAndFlush(shop);
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
