package com.storefront.store.order;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.shop.Shop;

import lombok.RequiredArgsConstructor;

/**
 * Applies one transition to one order, in its own transaction.
 *
 * <p><b>Split out from {@link BulkTransitionService} so the transaction boundary is a real bean
 * boundary.</b> Calling an annotated method on {@code this} bypasses the Spring proxy, the
 * propagation attribute is silently ignored, and every order in a batch joins one transaction — so
 * one refused order can undo the ones that succeeded.
 *
 * <p><b>{@code REQUIRES_NEW} was tried here and removed.</b> It looks correct and is wrong:
 * {@code REQUIRES_NEW} suspends the caller's transaction, so the inner one <b>cannot see the
 * caller's uncommitted writes</b>. In production the caller has no transaction, so it appeared to
 * work; under a test transaction every order came back "not found". This is the same trap
 * {@code OrderNumberService} documents at length, where the fix was to join the caller's
 * transaction rather than escape it.
 *
 * <p>So the batch method is deliberately <em>not</em> transactional ({@code @Transactional(propagation
 * = NOT_SUPPORTED)} on the service that calls this). Each call then starts and ends its own
 * transaction, which gives per-order commit without a suspended transaction, and holds one
 * connection at a time rather than one per order.
 */
@Service
@RequiredArgsConstructor
public class SingleOrderTransitionService {

	private final OrderService orders;

	/**
	 * Moves one order to {@code target}, committing or rolling back on its own.
	 *
	 * <p>Plain {@code REQUIRED}, and correct only because the caller is not in a transaction. See
	 * the class note.
	 *
	 * @throws RuntimeException when the state machine refuses, which the caller records as a
	 *         refusal for that order and continues
	 */
	@Transactional
	public void apply(Shop shop, UUID orderId, ShopOrder.Status target, String reason, UUID actorUserId) {
		ShopOrder order = orders.getOwned(shop, orderId);
		switch (target) {
			case ACCEPTED -> orders.accept(shop, order, actorUserId);
			case PROCESSING -> orders.markProcessing(shop, order, actorUserId);
			case SHIPPED -> orders.markShipped(shop, order, actorUserId);
			case DELIVERED -> orders.markDelivered(shop, order, actorUserId);
			case CANCELLED -> orders.cancel(shop, order, reason, actorUserId);
			// An order is created PENDING; there is no transition back to it, and pretending
			// otherwise would let a cancelled order be resurrected.
			case PENDING -> throw new IllegalStateException("An order cannot be moved back to PENDING");
		}
	}
}