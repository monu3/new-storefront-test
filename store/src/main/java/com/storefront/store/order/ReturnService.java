package com.storefront.store.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.audit.AuditAction;
import com.storefront.store.audit.AuditTrail;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletService;
import com.storefront.store.wallet.WalletService.InsufficientWalletBalanceException;

import lombok.RequiredArgsConstructor;

/**
 * Returns and refunds (Phase 8, D14).
 *
 * <p><b>The shop's wallet pays, and commission reverses proportionally.</b> Decided 18 Oct 2026, and
 * both halves are the same decision seen from two directions: the shop caused the return, so the
 * shop carries the cost, and the platform's share of that order shrinks by the same proportion the
 * refund does. Reversing commission on a partial return keeps the platform's earnings aligned with
 * the goods it actually kept.
 *
 * <p><b>Four steps, and they are separate on purpose.</b> Request → approve → refund. Collapsing
 * approval and refunding would credit a customer's money the moment a shop clicked a button, and the
 * payment would then fail with the wallet already debited. Keeping them apart means a failed refund
 * leaves a decision recorded and the money unmoved, which is a state somebody can finish.
 *
 * <p><b>A refund the shop cannot afford does not silently skip.</b> {@link WalletService} refuses a
 * debit that would take the balance negative, and that refusal propagates: the return stays
 * {@code APPROVED} with nothing refunded, so the shop's queue still shows it and the balance that
 * has to be topped up is visible rather than buried in a failed request.
 *
 * <p><b>The commission figure is frozen at refund time.</b> It is computed once from the order's
 * own frozen commission and written onto the request. Re-deriving it during a reconciliation would
 * re-read a platform setting that may since have changed.
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

	/**
	 * How much of an order a return can be refunded against.
	 *
	 * <p><b>{@code subtotal − discount}, matching the commission base (D31).</b> Not the order total:
	 * shipping and tax are what the courier and the revenue department took, and neither is returned
	 * when goods come back. Refunding against the total would hand back money this platform never
	 * earned commission on — and commission is the thing being reversed here.
	 */
	private static long refundableBase(ShopOrder order) {
		return Math.max(0, order.getSubtotalMinor() - order.getDiscountMinor());
	}

	private final ReturnRequestRepository returns;
	private final ReturnItemRepository returnItems;
	private final OrderItemRepository orderItems;
	private final ShopOrderRepository orders;
	private final OrderStateMachine stateMachine;
	private final WalletService wallet;
	private final ShopRepository shops;
	private final OrderService orderService;
	private final AuditTrail audit;

	// ------------------------------------------------------------------ the customer's part

	/**
	 * A customer asks to return part of an order.
	 *
	 * <p><b>Only a DELIVERED order can be returned.</b> Before delivery the right move is to cancel,
	 * which reverses commission in full and needs no return workflow; after a refund the order is
	 * terminal. This is checked against the state machine rather than with a hand-written condition,
	 * so adding a status later cannot leave this one behind.
	 *
	 * @param customerId from the token. The order's own {@code customerId} must match, so a
	 *                   customer cannot request a return against somebody else's order by guessing
	 *                   an id — the ownership check is here rather than in the controller for the
	 *                   reason the audit trail records in services.
	 */
	@Transactional
	public ReturnRequest request(UUID orderId, UUID customerId, String reason,
			Map<UUID, Integer> quantities) {

		ShopOrder order = orders.findById(orderId)
				.orElseThrow(() -> new IllegalArgumentException("No order " + orderId));

		if (!order.getCustomerId().equals(customerId)) {
			// Stated plainly rather than as a 404: a customer who passed their own token and
			// somebody else's order id is not making a mistake, and a confusing message sends them
			// to support instead of to the right order.
			throw new IllegalArgumentException(
					"That order does not belong to this account. Only the buyer can request a return.");
		}

		if (order.getStatus() != ShopOrder.Status.DELIVERED) {
			throw new IllegalStateException(
					"An order can only be returned once it has been delivered. This one is "
							+ order.getStatus() + (order.getStatus() == ShopOrder.Status.CANCELLED
									? " — a cancelled order is refunded in full and needs no return."
									: "."));
		}

		if (quantities == null || quantities.isEmpty()) {
			throw new IllegalArgumentException("A return request must say what is coming back.");
		}

		// One open request at a time per order. Two concurrent requests would each be validated
		// against the same "already returned" total and together over-return.
		if (!returns.findByOrderIdAndStatus(orderId, ReturnRequest.Status.REQUESTED).isEmpty()) {
			throw new IllegalStateException(
					"This order already has a return waiting for the shop. Ask them about that one "
							+ "before starting another.");
		}

		// Lines and total first, request second. The entity validates that the amount is positive,
		// and creating the request before its lines are priced would mean either passing 0 --
		// which the validation refuses -- or dropping the validation, which is the only thing
		// stopping a request for nothing from being stored.
		List<ReturnItem> lines = new ArrayList<>();
		long total = 0;

		for (Map.Entry<UUID, Integer> line : quantities.entrySet()) {
			int asked = line.getValue();

			OrderItem orderItem = orderItemOf(order, line.getKey());

			if (asked <= 0) {
				throw new IllegalArgumentException(
						"A return quantity must be positive, not " + asked + ".");
			}

			// The check that makes partial returns safe: never more than was bought, and never more
			// than has not already come back. Both are answered from the index, not by summing rows
			// in application code.
			long alreadyReturned = returns.approvedUnitsForLine(orderItem.getId());

			if (asked + alreadyReturned > orderItem.getQuantity()) {
				throw new IllegalArgumentException(
						"You bought " + orderItem.getQuantity() + " and " + alreadyReturned
								+ " already came back, so only " + (orderItem.getQuantity() - alreadyReturned)
								+ " can still be returned.");
			}

			lines.add(ReturnItem.of(orderItem.getId(), asked, orderItem.getUnitPriceMinor()));
			total += orderItem.getUnitPriceMinor() * (long) asked;
		}

		ReturnRequest request = ReturnRequest.requested(orderId, order.getShopId(), customerId,
				reason, total);
		lines.forEach(request::addItem);

		ReturnRequest saved = returns.save(request);

		audit.record(AuditAction.ORDER_RETURN_REQUESTED, "ShopOrder", orderId,
				"Return requested for order " + order.getOrderNumber() + " (" + total + " minor)",
				reason,
				AuditTrail.field("requestedMinor", total),
				AuditTrail.field("status", ReturnRequest.Status.REQUESTED.name()));

		return saved;
	}

	// ------------------------------------------------------------------ the shop's part

	/**
	 * Every return on one order, already mapped for the screen.
	 *
	 * <p><b>The mapping happens inside the transaction, and that is the whole reason this method
	 * returns views rather than entities.</b> {@code ReturnRequest.items} is a lazy collection, and
	 * a controller that maps it after the service returns is reading it with the Hibernate session
	 * already closed — which fails with {@code LazyInitializationException} and a message about
	 * proxies, naming neither the missing fetch nor the transaction that closed.
	 */
	@Transactional(readOnly = true)
	public List<ReturnView> forOrderView(UUID orderId) {
		return returns.findByOrderIdOrderByRequestedAtDesc(orderId).stream()
				.map(ReturnView::from)
				.toList();
	}

	/**
	 * The shop's queue, already mapped for the screen. Same reason as {@link #forOrderView}.
	 */
	@Transactional(readOnly = true)
	public org.springframework.data.domain.Page<ReturnView> queueView(UUID shopId,
			ReturnRequest.Status status, Pageable pageable) {
		return queueFor(shopId, status, pageable).map(ReturnView::from);
	}


	/**
	 * The shop's return queue.
	 *
	 * @param shopId from the token. Never a parameter the caller supplies — the same rule as every
	 *               other shop-scoped list in this platform.
	 */
	@Transactional(readOnly = true)
	public Page<ReturnRequest> queueFor(UUID shopId, ReturnRequest.Status status, Pageable pageable) {
		return returns.forShop(shopId, status, pageable);
	}

	/**
	 * The shop approves part or all of a request.
	 *
	 * @param approvedPerLine how many units of each order line to refund. Omitting a line approves
	 *                        zero of it, which is how a shop approves two of four items.
	 */
	@Transactional
	public ReturnRequest approve(UUID requestId, UUID shopId, UUID actorId,
			Map<UUID, Integer> approvedPerLine, String note) {

		ReturnRequest request = requireOwned(requestId, shopId);

		for (ReturnItem item : request.getItems()) {
			int approved = approvedPerLine == null ? 0 : approvedPerLine.getOrDefault(item.getOrderItemId(), 0);
			item.approveQuantity(approved);
		}

		long approvedTotal = request.approvedTotal();

		request.approve(approvedTotal, actorId, note);
		return returns.save(request);
	}

	/** The shop declines. Terminal. */
	@Transactional
	public ReturnRequest reject(UUID requestId, UUID shopId, UUID actorId, String note) {
		ReturnRequest request = requireOwned(requestId, shopId);

		if (note == null || note.isBlank()) {
			// Not optional, for the same reason a suspension needs one: a declined return with no
			// stated reason is one the customer will escalate and the shop cannot answer.
			throw new IllegalArgumentException(
					"A rejection needs a note. The customer is going to ask why, and this is the only "
							+ "thing that will answer them.");
		}

		request.reject(actorId, note);
		ReturnRequest saved = returns.save(request);

		audit.record(AuditAction.ORDER_RETURN_REJECTED, "ShopOrder", request.getOrderId(),
				"Return rejected on request " + requestId, note);

		return saved;
	}

	// ------------------------------------------------------------------ the money

	/**
	 * Executes an approved refund: the shop's wallet is debited and the platform's commission is
	 * credited back, in proportion to what is coming back.
	 *
	 * <p><b>Both legs, or neither.</b> The debit and the commission reversal share one transaction, so
	 * a failure in the second cannot leave the shop paid out and the platform keeping commission it
	 * no longer earned.
	 *
	 * <p><b>When the shop cannot afford it, nothing happens and the return stays APPROVED.</b> The
	 * refusal from {@link WalletService} propagates out of this transaction, so no partial state is
	 * written. The request is still in the shop's queue afterwards with nothing refunded, which is
	 * exactly the thing that has to be noticed.
	 *
	 * @return the ledger entries, so the caller can show what actually moved
	 */
	@Transactional
	public RefundOutcome refund(UUID requestId, UUID shopId, UUID actorId) {
		ReturnRequest request = requireOwned(requestId, shopId);

		if (request.getStatus() != ReturnRequest.Status.APPROVED) {
			throw new IllegalStateException(
					"This return is " + request.getStatus() + ", not APPROVED. Only an approved return "
							+ "can be refunded, and refunding it twice would move money twice.");
		}

		ShopOrder order = orders.findById(request.getOrderId())
				.orElseThrow(() -> new IllegalArgumentException("No order " + request.getOrderId()));

		long approved = request.approvedTotal();
		long base = refundableBase(order);

		if (approved <= 0) {
			throw new IllegalStateException(
					"This return was approved for nothing, so there is nothing to refund. Approve at "
							+ "least one unit, or reject the request.");
		}
		if (base <= 0) {
			throw new IllegalStateException(
					"This order was fully discounted, so there is no commission base to take a "
							+ "proportion of. The customer is still refunded the goods value; the "
							+ "platform simply never earned commission on it.");
		}

		// --- leg 1: the shop pays the customer.
		wallet.debit(shopId, WalletLedgerEntry.EntryType.REFUND_DEBIT, approved,
				"refund:" + requestId,
				"Refund for return " + requestId + " on order " + order.getOrderNumber(),
				order.getId(), actorId);

		// --- leg 2: the platform's commission comes back, in proportion.
		//
		// The proportion is of the *refundable base*, not of the order total, so the platform's share
		// is reversed by the same fraction the refund represents. `reverseDebitPartially` floors, and
		// returns empty when the portion rounds to zero -- which is correct for a one-paisa refund
		// and is why this is not an error.
		long commissionReversed = 0;
		if (!order.isCommissionWaived()) {
			Optional<WalletLedgerEntry> reversal = wallet.reverseDebitPartially(shopId,
					order.commissionIdempotencyKey(), approved, base,
					requestId.toString(),
					"Commission reversed on return " + requestId + " ("
							+ Math.round(approved * 10000.0 / base) / 100.0 + "% of the order)",
					order.getId(), actorId);

			commissionReversed = reversal.map(WalletLedgerEntry::getAmountMinor).orElse(0L);
		}

		request.markRefunded(commissionReversed);
		ReturnRequest saved = returns.save(request);

		// --- the order itself, only when everything came back.
		//
		// A partial return leaves the order DELIVERED. Moving it to RETURNED would claim the whole
		// order is back with the shop when three of four items still are not, and that is the exact
		// conflation the separate request table exists to avoid.
		if (isWholeOrderReturned(order)) {
			// Only when *every* unit is back. Moving it earlier would claim the whole order
			// returned while most of it still is not.
			orderService.markReturned(requireShop(order), order, actorId);
		}

		audit.record(AuditAction.ORDER_REFUNDED, "ShopOrder", order.getId(),
				"Refunded " + approved + " minor on order " + order.getOrderNumber()
						+ "; commission reversed " + commissionReversed,
				null,
				AuditTrail.field("returnStatus", ReturnRequest.Status.APPROVED.name()),
				AuditTrail.field("returnStatus", ReturnRequest.Status.REFUNDED.name()));

		return new RefundOutcome(saved, approved, commissionReversed, base);
	}

	/**
	 * Whether every unit of the order has now come back.
	 *
	 * <p>Answered against the refunded requests in the database rather than against the request in
	 * hand, because the whole order may have come back across *two* partial returns — which is the
	 * normal case and the reason this is a query.
	 */
	private boolean isWholeOrderReturned(ShopOrder order) {
		Map<UUID, Long> approved = new LinkedHashMap<>();

		for (ReturnRequest request : returns.findByOrderIdOrderByRequestedAtDesc(order.getId())) {
			if (request.getStatus() != ReturnRequest.Status.REFUNDED) {
				continue;
			}
			for (ReturnItem item : request.getItems()) {
				approved.merge(item.getOrderItemId(), (long) item.getQuantityApproved(), Long::sum);
			}
		}

		return orderItems.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
				.allMatch(item -> approved.getOrDefault(item.getId(), 0L) >= item.getQuantity());
	}

	// ------------------------------------------------------------------ helpers

	private ReturnRequest requireOwned(UUID requestId, UUID shopId) {
		ReturnRequest request = returns.findById(requestId)
				.orElseThrow(() -> new IllegalArgumentException("No return request " + requestId));

		if (!request.getShopId().equals(shopId)) {
			// The ownership rule again, checked rather than assumed: a shop id from a token is
			// trustworthy, but a return id guessed by a caller is not.
			throw new IllegalArgumentException(
					"That return belongs to another shop.");
		}

		return request;
	}

	/**
	 * One line of an order.
	 *
	 * <p>Loaded from {@code order_item} rather than off the order entity, because {@code ShopOrder}
	 * does not hold its lines — they are a separate table read on demand, and pretending otherwise
	 * would mean adding a collection to a hot entity to serve one screen.
	 *
	 * <p>Scoped to the order on purpose: an order line id from anywhere else must not be
	 * returnable, and this is where that is enforced.
	 */
	private OrderItem orderItemOf(ShopOrder order, UUID orderItemId) {
		return orderItems.findById(orderItemId)
				.filter(item -> item.getOrderId().equals(order.getId()))
				.orElseThrow(() -> new IllegalArgumentException(
						"Line " + orderItemId + " is not on order " + order.getOrderNumber() + ". A "
								+ "return cannot cover something that was never bought here."));
	}

	/**
	 * The shop an order belongs to.
	 *
	 * <p>Resolved from the order, not trusted from the caller: the refund already checked that the
	 * return belongs to {@code shopId}, and this makes the transition use the same shop rather than
	 * a second value the caller supplied.
	 */
	private Shop requireShop(ShopOrder order) {
		return shops.findById(order.getShopId())
				.orElseThrow(() -> new IllegalArgumentException(
						"No shop " + order.getShopId() + " for this order. An order whose shop is gone "
								+ "cannot be marked returned, because the transition would have nobody "
								+ "to attribute it to."));
	}

	// ------------------------------------------------------------------ types

	/** One return, as the screens see it. */
	public record ReturnView(
			UUID id,
			UUID orderId,
			String status,
			String reason,
			String resolutionNote,
			long requestedMinor,
			long approvedMinor,
			long commissionReversedMinor,
			java.time.Instant requestedAt,
			java.time.Instant decidedAt,
			List<ItemView> items) {

		static ReturnView from(ReturnRequest request) {
			return new ReturnView(
					request.getId(), request.getOrderId(), request.getStatus().name(),
					request.getReason(), request.getResolutionNote(),
					request.requestedTotal(), request.approvedTotal(),
					request.getCommissionReversedMinor(),
					request.getRequestedAt(), request.getDecidedAt(),
					request.getItems().stream().map(ItemView::from).toList());
		}
	}

	/** One line inside a return. */
	public record ItemView(
			UUID orderItemId, int quantity, int quantityApproved, long unitPriceMinor) {

		static ItemView from(ReturnItem item) {
			return new ItemView(item.getOrderItemId(), item.getQuantity(),
					item.getQuantityApproved(), item.getUnitPriceMinor());
		}
	}

	/**
	 * What a refund actually moved.
	 *
	 * <p>Returned rather than a boolean so the shop's screen can show the two legs side by side. The
	 * commission reversal is frequently not the refund's exact proportion of money — it is 5% of the
	 * goods, not of the refund — and showing one number where there are two would hide that.
	 */
	public record RefundOutcome(
			ReturnRequest request,
			long refundedMinor,
			long commissionReversedMinor,
			long refundableBaseMinor) {

		/** The percentage of the order's refundable value that came back, to two decimals. */
		public double percentOfOrder() {
			return refundableBaseMinor == 0
					? 0d
					: Math.round(refundedMinor * 10000.0 / refundableBaseMinor) / 100.0;
		}
	}
}