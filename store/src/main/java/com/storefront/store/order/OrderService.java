package com.storefront.store.order;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;
import com.storefront.store.inventory.InventoryService;
import com.storefront.store.inventory.InventoryService.NamedStockLine;
import com.storefront.store.inventory.InventoryService.StockLine;
import com.storefront.store.shop.Shop;
import com.storefront.store.wallet.CommissionCalculator;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * The order lifecycle, and the money that moves with it.
 *
 * <p>This is the highest-risk class in the system, so its rules are worth stating plainly:
 *
 * <ul>
 *   <li><b>Commission is debited at ACCEPT, not at place.</b> A placed order can be abandoned,
 *       and charging a shop commission for an order nobody intends to fulfil is how a marketplace
 *       loses its shops. (D2)</li>
 *   <li><b>The rate and amount are frozen onto the order at accept</b>, so changing the platform
 *       rate later cannot retroactively alter what a shop was charged.</li>
 *   <li><b>A shop must be able to pay.</b> {@link WalletService} refuses a debit the balance
 *       cannot cover, which is what makes "shops credit money before accepting any order" real
 *       rather than advisory.</li>
 *   <li><b>Cancelling after acceptance reverses the commission exactly once.</b> Before
 *       acceptance there is nothing to reverse, and crediting anyway would be a bug that quietly
 *       inflates balances.</li>
 * </ul>
 *
 * <p>The idempotency key for the debit is derived from the order id, so a retried accept is a
 * no-op rather than a second charge. That is not defensive programming — it is required, because
 * a shop owner will double-click and a network retry will happen.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private final ShopOrderRepository orders;
	private final OrderItemRepository items;
	private final OrderStatusHistoryRepository history;
	private final OrderStateMachine stateMachine;
	private final OrderNumberService orderNumbers;
	private final InventoryService inventory;
	private final WalletService wallet;
	private final com.storefront.store.notification.OutboxService outbox;
	private final CommissionCalculator commissions = new CommissionCalculator();

	// ------------------------------------------------------------------ placement

	/**
	 * Records a new order as PENDING.
	 *
	 * <p>Called by {@code CheckoutService} once the quote is agreed. Stock is <em>reserved</em>
	 * here, not committed: the units are held for this customer, but they are still on the shelf
	 * until the shop accepts.
	 *
	 * <p>The order number is allocated by {@link OrderNumberService} in its own transaction
	 * before this one, so a failure here consumes a number. That is intentional — a shop's
	 * sequence should have no visible gaps, and a gap looks to an owner like a lost order.
	 */
	@Transactional
	public ShopOrder place(Shop shop, PlacedOrder placed, UUID actorUserId) {

		ShopOrder order = new ShopOrder(
				shop.getId(),
				shop.getType(),
				placed.orderNumber(),
				placed.paymentMethod(),
				placed.subtotalMinor(),
				placed.discountMinor(),
				placed.taxMinor(),
				placed.shippingMinor(),
				placed.shippingAddressJson());

		order.setGuestEmail(placed.guestEmail());
		order.setGuestPhone(placed.guestPhone());
		order.setCustomerId(placed.customerId());
		order.setCustomerNote(placed.customerNote());
		order.setBillingAddress(placed.billingAddressJson());

		ShopOrder saved = orders.saveAndFlush(order);

		for (PlacedLine line : placed.lines()) {
			items.saveAndFlush(new OrderItem(saved.getId(), line.productId(), line.variantId(),
					line.productName(), line.variantName(), line.category(), line.unitPriceMinor(),
					line.quantity(), line.sku(), line.imageUrl()));
		}

		// Reserve inside this transaction (MANDATORY): if the order insert fails above, the
		// reservation rolls back with it. There is no window where stock is held for an order
		// that does not exist.
		inventory.reserveAll(placed.stockLines(), saved.getId(), placed.backorderAllowed());

		history.save(OrderStatusHistory.placed(saved.getId(), actorUserId));

		// Recorded in this same transaction (MANDATORY), so the event cannot survive an order that
		// rolled back and cannot be lost after one that committed. See OutboxService.
		recordEvent(saved, "ORDER_PLACED",
				java.util.Map.of(
						"orderId", saved.getId().toString(),
						"orderNumber", saved.getOrderNumber(),
						"shopId", shop.getId().toString(),
						"guestEmail", saved.getGuestEmail() == null ? "" : saved.getGuestEmail(),
						"totalMinor", saved.getTotalMinor()));

		log.info("Order {} ({}) placed in shop {} for {}",
				saved.getOrderNumber(), saved.getId(), shop.getId(), Money.ofMinor(saved.getTotalMinor()));
		return saved;
	}

	// ------------------------------------------------------------------ transitions

	/**
	 * The shop accepts: commission is debited here, and the reservation becomes a sale.
	 *
	 * <p>This is the single most consequential method in the codebase, so the order of operations
	 * is deliberate:
	 *
	 * <ol>
	 *   <li>Check the transition is legal.</li>
	 *   <li>Compute and freeze the commission — the rate that applies <em>now</em> is the rate this
	 *       order is judged by, forever.</li>
	 *   <li>Debit the wallet. If the shop cannot pay, nothing else has changed yet, so the order
	 *       stays PENDING and the shop owner sees why.</li>
	 *   <li>Commit stock. After this the units are gone.</li>
	 *   <li>Move the status and record the history entry.</li>
	 * </ol>
	 *
	 * <p>Everything is in one transaction, so a failure at any step leaves the order untouched
	 * rather than half-accepted.
	 */
	@Transactional
	public ShopOrder accept(Shop shop, ShopOrder order, UUID actorUserId) {
		stateMachine.require(order.getStatus(), ShopOrder.Status.ACCEPTED);

		Money subtotal = Money.ofMinor(order.getSubtotalMinor());
		Money discount = Money.ofMinor(order.getDiscountMinor());
		Money shipping = Money.ofMinor(order.getShippingMinor());
		Money tax = Money.ofMinor(order.getTaxMinor());

		CommissionCalculator.Commission commission = commissions.calculate(
				subtotal, discount, shipping, tax,
				CommissionCalculator.DEFAULT_RATE_BP, !shop.paysCommission());

		if (commission.isChargeable()) {
			wallet.debit(shop.getId(),
					WalletLedgerEntry.EntryType.COMMISSION_DEBIT,
					commission.amount().minor(),
					commissionKey(order),
					("Commission %.2f%% on order " + order.getOrderNumber())
							.formatted(commission.rateBp() / 100.0),
					order.getId(),
					actorUserId);
			log.info("Commission {} debited from shop {} for order {}",
					commission.amount().minor(), shop.getId(), order.getOrderNumber());
		}

		// Frozen only once the money has moved. The rate that applies now is the rate this order is
		// judged by forever, and writing it after the debit means a refused accept leaves the order
		// completely untouched rather than merely rolled back. See the class-level step list.
		order.freezeCommission(commission.rateBp(), commission.amount().minor(), commission.waived());

		// Stock leaves the shelf only now, when the shop has committed to fulfilling.
		// Commit and warn in one step: this is where stock actually leaves the shelf, so it is
		// the first moment a low-stock warning is true rather than hypothetical.
		inventory.commitAllAndWarn(shop.getId(), namedStockLinesOf(order), order.getId());

		ShopOrder.Status from = order.getStatus();
		stateMachine.apply(order, ShopOrder.Status.ACCEPTED);
		record(order, from, ShopOrder.Status.ACCEPTED, actorUserId, null);
		orders.saveAndFlush(order);
		recordEvent(order, "ORDER_ACCEPTED", eventPayload(order, shop));
		return order;
	}

	/** Packing. No money moves. */
	@Transactional
	public ShopOrder markProcessing(Shop shop, ShopOrder order, UUID actorUserId) {
		stateMachine.require(order.getStatus(), ShopOrder.Status.PROCESSING);
		ShopOrder.Status from = order.getStatus();
		stateMachine.apply(order, ShopOrder.Status.PROCESSING);
		record(order, from, ShopOrder.Status.PROCESSING, actorUserId, null);
		return orders.saveAndFlush(order);
	}

	/**
	 * Handed to a courier.
	 *
	 * <p>For COD the money changes hands here: the shop has collected cash, so the order is
	 * marked paid. For eSewa and bank transfer it was already settled at checkout.
	 */
	@Transactional
	public ShopOrder markShipped(Shop shop, ShopOrder order, UUID actorUserId) {
		stateMachine.require(order.getStatus(), ShopOrder.Status.SHIPPED);

		if (order.getPaymentMethod() == ShopOrder.PaymentMethod.COD
				&& order.getPaymentStatus() == ShopOrder.PaymentStatus.PENDING) {
			order.markPaid();
		}

		ShopOrder.Status from = order.getStatus();
		stateMachine.apply(order, ShopOrder.Status.SHIPPED);
		record(order, from, ShopOrder.Status.SHIPPED, actorUserId, null);
		return orders.saveAndFlush(order);
	}

	/** Received by the customer. The order is now final unless Phase 8 returns are enabled. */
	@Transactional
	public ShopOrder markDelivered(Shop shop, ShopOrder order, UUID actorUserId) {
		stateMachine.require(order.getStatus(), ShopOrder.Status.DELIVERED);

		// A bank transfer that was never verified cannot be marked delivered as though it were
		// paid: the shop would be told the money is in when it is not.
		if (order.getPaymentMethod() == ShopOrder.PaymentMethod.BANK_TRANSFER
				&& order.getPaymentStatus() == ShopOrder.PaymentStatus.PENDING) {
			throw new IllegalStateException(
					"Mark the bank transfer as verified before delivering this order.");
		}

		ShopOrder.Status from = order.getStatus();
		stateMachine.apply(order, ShopOrder.Status.DELIVERED);
		record(order, from, ShopOrder.Status.DELIVERED, actorUserId, null);
		return orders.saveAndFlush(order);
	}

	/**
	 * Cancels an order and reverses commission if any was taken.
	 *
	 * <p>Only ever called for a PENDING order, because {@link OrderStateMachine} forbids
	 * cancelling anything else. That single rule removes the need to reason about a cancellation
	 * after the goods have shipped — which is a return, in Phase 8, with a different and much
	 * more careful flow.
	 *
	 * <p>Cancelling before acceptance releases the reservation and takes no money, because no
	 * commission was taken. That is the common case and the one that must not inflate a balance.
	 */
	@Transactional
	public ShopOrder cancel(Shop shop, ShopOrder order, String reason, UUID actorUserId) {
		stateMachine.require(order.getStatus(), ShopOrder.Status.CANCELLED);

		boolean hadCommission = stateMachine.reversesCommission(order.getStatus())
				&& order.getCommissionMinor() != null && order.getCommissionMinor() > 0;

		if (hadCommission) {
			wallet.reverseDebit(shop.getId(), commissionKey(order),
					("Commission reversed for cancelled order " + order.getOrderNumber()),
					order.getId(), actorUserId)
					.ifPresent(entry -> log.info("Reversed commission {} for cancelled order {}",
							entry.getAmountMinor(), order.getOrderNumber()));
		}

		// Only meaningful when the order was never accepted; a cancelled-before-accept order
		// still holds its reservation and must give it back.
		if (!hadCommission) {
			inventory.releaseAll(stockLinesOf(order), order.getId());
		}

		order.cancel(reason);
		ShopOrder.Status from = order.getStatus();
		stateMachine.apply(order, ShopOrder.Status.CANCELLED);
		record(order, from, ShopOrder.Status.CANCELLED, actorUserId, reason);
		orders.saveAndFlush(order);
		recordEvent(order, "ORDER_CANCELLED", eventPayload(order, shop));
		return order;
	}

	// ------------------------------------------------------------------ reads

	/** An order the shop owns. 404 for another shop's order, never 403 — see the note on ids. */
	@Transactional(readOnly = true)
	public ShopOrder getOwned(Shop shop, UUID orderId) {
		ShopOrder order = orders.findById(orderId)
				.orElseThrow(() -> new OrderNotFoundException(orderId));
		if (!order.getShopId().equals(shop.getId())) {
			// Deliberately indistinguishable from "does not exist": a 403 would confirm the id
			// is real, which turns the endpoint into an existence oracle for other shops' orders.
			throw new OrderNotFoundException(orderId);
		}
		return order;
	}

	/** An order a customer placed. Same 404-not-403 rule, for the same reason. */
	@Transactional(readOnly = true)
	public ShopOrder getForCustomer(UUID customerId, UUID orderId) {
		ShopOrder order = orders.findById(orderId)
				.filter(o -> customerId.equals(o.getCustomerId()))
				.orElseThrow(() -> new OrderNotFoundException(orderId));
		return order;
	}

	/**
	 * An order by its public number, for the shop looking one up from an invoice.
	 *
	 * <p>Order numbers are per shop, so the shop must be part of the lookup. A customer uses
	 * {@link #getForCustomer} instead — an order number is not a secret, and treating it as one
	 * would just push shop owners into support tickets.
	 */
	@Transactional(readOnly = true)
	public ShopOrder getByNumber(Shop shop, String orderNumber) {
		return orders.findByShopIdAndOrderNumber(shop.getId(), orderNumber)
				.orElseThrow(() -> new OrderNotFoundException(orderNumber));
	}

	// ------------------------------------------------------------------ listings

	/**
	 * The shop's orders, newest first.
	 *
	 * <p>Paged: order lists grow without bound, and offset pagination degrades on them, so the
	 * console pages by {@code placedAt}.
	 */
	@Transactional(readOnly = true)
	public Page<ShopOrder> listForShop(Shop shop, Pageable pageable) {
		return orders.findByShopIdOrderByPlacedAtDesc(shop.getId(), pageable);
	}

	@Transactional(readOnly = true)
	public Page<ShopOrder> listForShop(Shop shop, ShopOrder.Status status, Pageable pageable) {
		return orders.findByShopIdAndStatusOrderByPlacedAtDesc(shop.getId(), status, pageable);
	}

	/**
	 * Orders that still need something done.
	 *
	 * <p>The default board view, because "what needs my attention" is the question a shop owner
	 * actually opens the board to answer.
	 */
	@Transactional(readOnly = true)
	public Page<ShopOrder> listOpen(Shop shop, Pageable pageable) {
		return orders.findOpenForShop(shop.getId(),
				List.copyOf(com.storefront.store.order.OrderStateMachine.OPEN_STATUSES), pageable);
	}

	/**
	 * How many lines each of these orders has.
	 *
	 * <p>One grouped query for the whole page rather than a count per order. The board renders
	 * this on every row, and a per-order count would be an N+1 on the screen a shop owner looks
	 * at all day.
	 *
	 * @return order id to line count; orders with no lines are absent, so callers treat a missing
	 *         key as zero
	 */
	@Transactional(readOnly = true)
	public Map<UUID, Integer> lineCounts(List<ShopOrder> page) {
		if (page == null || page.isEmpty()) {
			return Map.of();
		}
		List<UUID> ids = page.stream().map(ShopOrder::getId).toList();
		Map<UUID, Integer> counts = new HashMap<>();
		for (Object[] row : items.countLinesForOrders(ids)) {
			counts.put((UUID) row[0], ((Number) row[1]).intValue());
		}
		return counts;
	}

	@Transactional(readOnly = true)
	public List<OrderItem> itemsOf(UUID orderId) {
		return items.findByOrderIdOrderByCreatedAtAsc(orderId);
	}

	@Transactional(readOnly = true)
	public List<OrderStatusHistory> historyOf(UUID orderId) {
		return history.findByOrderIdOrderByCreatedAtAsc(orderId);
	}

	/** What statuses the shop may move this order to next. The console binds buttons to this. */
	@Transactional(readOnly = true)
	public java.util.Set<ShopOrder.Status> nextStatuses(ShopOrder order) {
		return stateMachine.successors(order.getStatus());
	}

	// ------------------------------------------------------------------ internals

	/**
	 * Appends one history entry.
	 *
	 * <p>{@code from} is passed in rather than read from the order because the order has already
	 * moved by this point — {@code apply} set the new status and stamped its timestamp. Reading
	 * it here would record {@code from == to} for every transition, producing a timeline where
	 * nothing appears to have changed.
	 */
	private void record(ShopOrder order, ShopOrder.Status from, ShopOrder.Status to,
			UUID actorUserId, String note) {
		history.save(new OrderStatusHistory(order.getId(), from, to, actorUserId, note));
	}

	/** The minimum a consumer needs to tell a customer about their order. */
	private java.util.Map<String, Object> eventPayload(ShopOrder order, Shop shop) {
		java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("orderId", order.getId().toString());
		payload.put("orderNumber", order.getOrderNumber());
		payload.put("shopId", shop.getId().toString());
		payload.put("totalMinor", order.getTotalMinor());
		payload.put("status", order.getStatus().name());
		return payload;
	}

	private void recordEvent(ShopOrder order, String eventType, java.util.Map<String, Object> payload) {
		outbox.record(com.storefront.store.notification.OutboxEvent.ORDER, order.getId(),
				eventType, payload);
	}

	/**
	 * The default board view: everything still in play.
	 *
	 * <p>Delivered, cancelled, refunded and returned orders are excluded. An owner opening the
	 * board is asking "what needs me", and last month's delivered orders are not what needs them.
	 * The tabs that need history expose it through the status filter.
	 */
	@Transactional(readOnly = true)
	public Page<ShopOrder> listActive(Shop shop, Pageable pageable) {
		return orders.findActiveForShop(shop.getId(), pageable);
	}

	/**
	 * Searches a shop's orders by number, customer name, phone or email (Phase 4).
	 *
	 * <p>Written as one native query because the four fields live in different places: the order
	 * number and the guest contact details on the order, and the customer's own name on
	 * {@code app_user} when the order belongs to a signed-in account. Four derived queries would
	 * be four round trips and would each need a separate result merge.
	 *
	 * <p>Every predicate is a bound parameter. The order number column is {@code citext}, so a
	 * customer typing {@code ord-0001} matches {@code ORD-0001} without the search lower-casing
	 * anything itself.
	 */
	@Transactional(readOnly = true)
	public Page<ShopOrder> searchOrders(Shop shop, String term, Pageable pageable) {
		String cleaned = term == null ? "" : term.trim();
		if (cleaned.isEmpty()) {
			return orders.findByShopIdOrderByPlacedAtDesc(shop.getId(), pageable);
		}

		String like = "%" + cleaned.toLowerCase(java.util.Locale.ROOT) + "%";
		return orders.searchInShop(
				shop.getId(),
				like,
				pageable);
	}

	/**
	 * How many of the shop's orders sit in each status (Phase 4).
	 *
	 * <p>Drives the count badge on each filter tab. One grouped query, because the tabs are seven
	 * counts and an owner opens this screen to answer "what needs me".
	 *
	 * <p>Counts every non-terminal order, which is what a tab should show: a delivered order last
	 * month is not in anybody's way and counting it makes the queue look worse than it is.
	 */
	@Transactional(readOnly = true)
	public java.util.Map<ShopOrder.Status, Long> countsByStatus(Shop shop) {
		java.util.Map<ShopOrder.Status, Long> counts = new java.util.EnumMap<>(ShopOrder.Status.class);
		for (Object[] row : orders.countByStatusForShop(shop.getId())) {
			ShopOrder.Status status = ShopOrder.Status.valueOf(String.valueOf(row[0]));
			counts.put(status, ((Number) row[1]).longValue());
		}
		return counts;
	}

	/** Stock coordinates for every line of an order. */
	private List<StockLine> stockLinesOf(ShopOrder order) {
		return items.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
				.filter(item -> item.getVariantId() != null)
				.map(item -> new StockLine(item.getProductId(), item.getVariantId(), item.getQuantity()))
				.toList();
	}

	/**
	 * The same lines, carrying names.
	 *
	 * <p>Read from the order's own frozen snapshot rather than the catalogue. The item names on an
	 * order are what the customer was shown; a low-stock warning about "Product 3f2a…" would be
	 * useless, and a warning using today's renamed product would contradict the order it came from.
	 */
	private List<NamedStockLine> namedStockLinesOf(ShopOrder order) {
		return items.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
				.filter(item -> item.getVariantId() != null)
				.map(item -> new NamedStockLine(item.getProductId(), item.getVariantId(),
						item.getQuantity(), item.getProductName(), item.getVariantName()))
				.toList();
	}

	/**
	 * The commission idempotency key for an order.
	 *
	 * <p>Derived from the order id, so two accepts of the same order debit once. Without this a
	 * double-click, or a retried request from a flaky mobile connection, charges the shop twice
	 * for one order.
	 */
	private String commissionKey(ShopOrder order) {
		return "commission:" + order.getId();
	}

	// ------------------------------------------------------------------ inputs

	/** One order line, already priced and snapshotted by the checkout quote. */
	public record PlacedLine(
			UUID productId,
			UUID variantId,
			String productName,
			String variantName,
			com.storefront.store.catalog.ProductCategory category,
			long unitPriceMinor,
			int quantity,
			String sku,
			String imageUrl) {
	}

	/** Everything needed to create an order. Produced by the checkout quote, never by the client. */
	public record PlacedOrder(
			String orderNumber,
			ShopOrder.PaymentMethod paymentMethod,
			long subtotalMinor,
			long discountMinor,
			long taxMinor,
			long shippingMinor,
			String shippingAddressJson,
			String billingAddressJson,
			String guestEmail,
			String guestPhone,
			UUID customerId,
			String customerNote,
			boolean backorderAllowed,
			List<PlacedLine> lines) {

		public List<StockLine> stockLines() {
			return lines.stream()
					.filter(l -> l.variantId() != null)
					.map(l -> new StockLine(l.productId(), l.variantId(), l.quantity()))
					.toList();
		}
	}

	/** Maps to HTTP 404, including for an order that exists but belongs to another shop. */
	public static class OrderNotFoundException extends RuntimeException {
		public OrderNotFoundException(Object id) {
			super("No order " + id);
		}
	}
}
