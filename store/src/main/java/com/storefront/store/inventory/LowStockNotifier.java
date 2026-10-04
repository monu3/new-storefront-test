package com.storefront.store.inventory;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.notification.OutboxEvent;
import com.storefront.store.notification.OutboxService;
import com.storefront.store.settings.ShopSettingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tells a shop owner when something is running out.
 *
 * <p><b>A warning, never a rule.</b> Nothing here decides a shop has "too little" of something it
 * intends to restock tomorrow, and nothing depletes stock automatically. The event carries the
 * numbers and the owner decides — the same stance {@code GET /stock/low} takes.
 *
 * <p><b>Emitted only on the transition into low.</b> The check is whether the variant was already
 * at or below its threshold before this change. A variant resting at zero would otherwise re-notify
 * on every single sale, and a shop with a permanently out-of-stock item would generate an unbounded
 * stream of identical emails.
 *
 * <p><b>Recorded in the outbox, inside the stock transaction.</b> A stock movement and its
 * notification commit together or not at all, so a shop cannot be warned about a restock that
 * rolled back — which would be the worst possible time to hear "you are running low" when in fact
 * they have just received forty boxes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LowStockNotifier {

	private final InventoryRepository inventory;
	private final ShopSettingService settings;
	private final OutboxService outbox;

	/**
	 * Emits {@code LOW_STOCK} for one variant if the change just took it below its threshold.
	 *
	 * <p>{@code wasAlreadyLow} is passed in rather than re-read: the caller has just changed the
	 * row, so reading it now would only ever see the new value and could never detect a crossing.
	 * The stock layer knows what it was a moment ago; this layer is told.
	 *
	 * @param availableNow on-hand minus reserved, after the change
	 * @param wasAlreadyLow whether the variant was already at or below its threshold before it
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void check(UUID shopId, UUID productId, UUID variantId, String productName,
			String variantName, int availableNow, int threshold, boolean wasAlreadyLow) {

		if (wasAlreadyLow || availableNow > threshold) {
			return;
		}

		// A shop that has turned low-stock notifications off gets silence, not a preference
		// setting that is stored and then ignored.
		if (!settings.notifications(shopId).lowStock()) {
			log.debug("Low stock on {} for shop {} but notifications are off", variantId, shopId);
			return;
		}

		outbox.record(OutboxEvent.SHOP, shopId, "LOW_STOCK", new java.util.LinkedHashMap<>(Map.of(
				"shopId", shopId.toString(),
				"productId", productId.toString(),
				"variantId", variantId.toString(),
				"productName", productName == null ? "A product" : productName,
				"variantName", variantName == null ? "" : variantName,
				"available", availableNow,
				"threshold", threshold)));

		log.info("Low stock: {} ({}) has {} left, threshold {}",
				productName, variantId, availableNow, threshold);
	}
}
