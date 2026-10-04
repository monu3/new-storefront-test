package com.storefront.store.order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves several orders to the same status at once.
 *
 * <p><b>One order's refusal never fails the batch.</b> This is the whole reason the class exists.
 * An owner with twenty orders on the board ticks the four that are ready to pack and presses the
 * button. If one of them was cancelled by the customer thirty seconds earlier, the right outcome is
 * that three move and the owner is told which one did not — not that nothing happens because of one
 * row, leaving the owner to work out which three were safe.
 *
 * <p>Each order therefore runs in its own transaction, via
 * {@link SingleOrderTransitionService}. That is a separate bean on purpose: see the note there on
 * why a self-invocation would not produce the per-order boundary it looks like it does.
 *
 * <p><b>Every refusal is reported, with its id.</b> A response saying "3 of 4 succeeded" would not
 * tell the owner <em>which</em> order was left behind, and the one left behind is exactly the one
 * that needs attention.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BulkTransitionService {

	private final SingleOrderTransitionService transitions;
	private final TenantGuard tenants;
	private final com.storefront.store.security.CurrentUser currentUser;

	/** Enough to stop an owner accidentally posting the whole board fifty times. */
	public static final int MAX_BATCH = 100;

	/**
	 * Attempts the same transition on every id given.
	 *
	 * @param target the status to move to; must be legal for each order individually
	 */
	// NOT_SUPPORTED, not readOnly: a surrounding transaction must be suspended so that each
	// order's transition is its own commit. Leaving one open would let a single refusal roll the
	// whole batch back -- see SingleOrderTransitionService.
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public BulkResult transition(List<UUID> orderIds, ShopOrder.Status target, String reason) {
		if (target == ShopOrder.Status.PENDING) {
			// Refused up front rather than per order: it is illegal for every order, and reporting
			// it as 100 individual refusals would bury that.
			throw new IllegalArgumentException("An order cannot be moved back to PENDING");
		}

		Shop shop = tenants.requireOwnedShop();
		UUID actor = currentUser.optionalUserId();

		List<UUID> ids = orderIds == null ? List.of() : orderIds.stream()
				.distinct()
				.limit(MAX_BATCH)
				.toList();

		if (ids.isEmpty()) {
			return new BulkResult(List.of(), Map.of());
		}

		List<UUID> moved = new ArrayList<>();
		Map<UUID, String> refused = new LinkedHashMap<>();

		for (UUID id : ids) {
			try {
				transitions.apply(shop, id, target, reason, actor);
				moved.add(id);
			} catch (RuntimeException ex) {
				refused.put(id, message(ex));
				log.info("Bulk transition to {} skipped for order {} in shop {}: {}",
						target, id, shop.getId(), message(ex));
			}
		}

		log.info("Bulk transition to {} in shop {}: {} moved, {} refused",
				target, shop.getId(), moved.size(), refused.size());

		return new BulkResult(moved, refused);
	}

	/**
	 * A refusal reason an owner can act on.
	 *
	 * <p>Prefers the exception's message over its class name: "This order is already CANCELLED"
	 * tells an owner what to do, {@code IllegalStateException} does not.
	 */
	private static String message(RuntimeException ex) {
		String message = ex.getMessage();
		if (message == null || message.isBlank()) {
			return ex.getClass().getSimpleName();
		}
		return message;
	}

	/**
	 * What moved and what did not.
	 *
	 * @param moved ids that reached the target status
	 * @param refused ids that did not, each with the reason
	 */
	public record BulkResult(List<UUID> moved, Map<UUID, String> refused) {

		public int requested() {
			return moved.size() + refused.size();
		}

		public boolean allSucceeded() {
			return refused.isEmpty();
		}
	}
}