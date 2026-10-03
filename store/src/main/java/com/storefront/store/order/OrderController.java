package com.storefront.store.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.Money;
import com.storefront.store.inventory.InventoryService;
import com.storefront.store.inventory.InventoryService.StockLine;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The shop's own order board.
 *
 * <p>No endpoint takes a shop id — the shop comes from the token (D35) — and
 * {@link OrderService} independently checks that the order belongs to it. Two layers, because
 * the service must be safe regardless of who calls it.
 *
 * <p>Status changes go through named endpoints ({@code /accept}, {@code /ship}) rather than a
 * {@code POST /status} taking a target state. A named endpoint cannot be called with an illegal
 * target, so the state machine's rules cannot be bypassed by a client that guesses a status name;
 * the console also gets the set of legal next states from {@code /{id}/transitions} and renders
 * exactly those buttons.
 */
@RestController
@RequestMapping("/api/v1/shop/orders")
@Tag(name = "Orders", description = "The shop's own orders: board, lifecycle, stock")
@PreAuthorize("hasRole('SHOP')")
@RequiredArgsConstructor
public class OrderController {

	private static final int MAX_PAGE_SIZE = 100;

	private final OrderService orders;
	private final OrderStateMachine stateMachine;
	private final InventoryService inventory;
	private final TenantGuard tenantGuard;
	private final CurrentUser currentUser;

	// ------------------------------------------------------------------ board

	@GetMapping
	@Operation(summary = "The shop's orders",
			description = "Newest first. Filter by status, or ask only for open ones.")
	public PageResponse<OrderSummary> list(
			@RequestParam(required = false) ShopOrder.Status status,
			@RequestParam(required = false) Boolean open,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		Shop shop = tenantGuard.requireOwnedShop();
		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
				Sort.by(Sort.Direction.DESC, "placedAt"));

		Page<ShopOrder> result = Boolean.TRUE.equals(open)
				? orders.listOpen(shop, pageable)
				: status == null
						? orders.listForShop(shop, pageable)
						: orders.listForShop(shop, status, pageable);

		// One grouped query for the whole page, not a count per row.
		Map<UUID, Integer> lineCounts = orders.lineCounts(result.getContent());
		return PageResponse.from(result, order -> OrderSummary.from(order, lineCounts));
	}

	@GetMapping("/{orderId}")
	@Operation(summary = "One order, with its lines and full timeline")
	public OrderDetail get(@PathVariable UUID orderId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getOwned(shop, orderId);
		return OrderDetail.from(order, orders.itemsOf(orderId), orders.historyOf(orderId),
				orders.nextStatuses(order));
	}

	/** By the number the customer quotes on the phone. Scoped by shop, so numbers need not be secret. */
	@GetMapping("/by-number/{orderNumber}")
	@Operation(summary = "Look an order up by its number")
	public OrderDetail byNumber(@PathVariable String orderNumber) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getByNumber(shop, orderNumber);
		return OrderDetail.from(order, orders.itemsOf(order.getId()),
				orders.historyOf(order.getId()), orders.nextStatuses(order));
	}

	/**
	 * The statuses this order may move to next.
	 *
	 * <p>So the console renders only valid buttons. The server still refuses an illegal
	 * transition — this is for the UI, not for authorisation.
	 */
	@GetMapping("/{orderId}/transitions")
	@Operation(summary = "Statuses this order can move to now")
	public Transitions transitions(@PathVariable UUID orderId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getOwned(shop, orderId);
		return new Transitions(order.getStatus().name(), orders.nextStatuses(order),
				stateMachine.isCancellable(order.getStatus()));
	}

	// ------------------------------------------------------------------ lifecycle

	/**
	 * Accepts the order: commission is debited and stock leaves the shelf.
	 *
	 * <p>409 when the shop's wallet cannot cover the commission — the shop owner is told exactly
	 * how much is short, which is the whole point of the prepaid model (D2).
	 */
	@PostMapping("/{orderId}/accept")
	@Operation(summary = "Accept an order",
			description = "Debits commission from the shop's wallet and commits reserved stock.")
	public OrderSummary accept(@PathVariable UUID orderId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getOwned(shop, orderId);
		ShopOrder accepted = orders.accept(shop, order, currentUser.optionalUserId());
		return OrderSummary.from(accepted, Map.of());
	}

	@PostMapping("/{orderId}/process")
	@Operation(summary = "Mark as being packed")
	public OrderSummary process(@PathVariable UUID orderId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getOwned(shop, orderId);
		return OrderSummary.from(orders.markProcessing(shop, order, currentUser.optionalUserId()), Map.of());
	}

	/** For COD this is also where the order becomes paid: the shop has the cash in hand. */
	@PostMapping("/{orderId}/ship")
	@Operation(summary = "Mark as handed to the courier")
	public OrderSummary ship(@PathVariable UUID orderId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getOwned(shop, orderId);
		return OrderSummary.from(orders.markShipped(shop, order, currentUser.optionalUserId()), Map.of());
	}

	@PostMapping("/{orderId}/deliver")
	@Operation(summary = "Mark as delivered")
	public OrderSummary deliver(@PathVariable UUID orderId) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getOwned(shop, orderId);
		return OrderSummary.from(orders.markDelivered(shop, order, currentUser.optionalUserId()), Map.of());
	}

	/**
	 * Cancels an order.
	 *
	 * <p>Only possible while PENDING. Once accepted, cancelling is a return, and Phase 1 does not
	 * do returns — a 409 saying so is far better than a cancellation that quietly skips reversing
	 * commission.
	 */
	@PostMapping("/{orderId}/cancel")
	@Operation(summary = "Cancel a pending order",
			description = "Releases reserved stock. Reverses commission if any was taken.")
	public OrderSummary cancel(@PathVariable UUID orderId, @RequestBody CancelRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		ShopOrder order = orders.getOwned(shop, orderId);
		return OrderSummary.from(
				orders.cancel(shop, order, request == null ? null : request.reason(),
						currentUser.optionalUserId()),
				Map.of());
	}

	// ------------------------------------------------------------------ stock

	/**
	 * The stock movements behind one order.
	 *
	 * <p>Shown beside the order so that "you say I ordered 40 and you say I only got 30" can be
	 * settled from the record rather than from memory.
	 */
	@GetMapping("/{orderId}/stock-movements")
	@Operation(summary = "Stock movements this order caused")
	public List<StockMovementView> stockMovements(@PathVariable UUID orderId) {
		Shop shop = tenantGuard.requireOwnedShop();
		orders.getOwned(shop, orderId);
		return inventory.movementsForOrder(orderId).stream().map(StockMovementView::from).toList();
	}

	// ------------------------------------------------------------------ requests

	public record CancelRequest(@Size(max = 500) String reason) {
	}

	// ------------------------------------------------------------------ responses

	/** A row on the order board: enough to decide what to do, not enough to open the order. */
	public record OrderSummary(
			UUID id,
			String orderNumber,
			ShopOrder.Status status,
			ShopOrder.PaymentStatus paymentStatus,
			ShopOrder.PaymentMethod paymentMethod,
			String customerEmail,
			String customerName,
			int lineCount,
			long subtotal,
			long discount,
			long tax,
			long shipping,
			long total,
			String totalDisplay,
			long commission,
			boolean commissionWaived,
			String city,
			Instant placedAt) {

		static OrderSummary from(ShopOrder order, Map<UUID, Integer> lineCounts) {
			// City is parsed from the address snapshot rather than stored twice: an address that
			// is edited after the order must not change where the parcel went.
			String city = CityOf.addressCity(order.getShippingAddress());

			return new OrderSummary(
					order.getId(),
					order.getOrderNumber(),
					order.getStatus(),
					order.getPaymentStatus(),
					order.getPaymentMethod(),
					order.getGuestEmail(),
					CityOf.name(order.getShippingAddress()),
					lineCounts.getOrDefault(order.getId(), 0),
					order.getSubtotalMinor(),
					order.getDiscountMinor(),
					order.getTaxMinor(),
					order.getShippingMinor(),
					order.getTotalMinor(),
					Money.ofMinor(order.getTotalMinor()).toDisplay(),
					order.getCommissionMinor() == null ? 0 : order.getCommissionMinor(),
					order.isCommissionWaived(),
					city,
					order.getPlacedAt());
		}
	}

	/** The full order, as the shop's print view and the customer's tracker both need it. */
	public record OrderDetail(
			UUID id,
			String orderNumber,
			ShopOrder.Status status,
			ShopOrder.PaymentStatus paymentStatus,
			ShopOrder.PaymentMethod paymentMethod,
			String guestEmail,
			String guestPhone,
			String customerNote,
			String adminNote,
			String shippingAddress,
			String billingAddress,
			long subtotal,
			long discount,
			long tax,
			long shipping,
			long total,
			String totalDisplay,
			Long commission,
			boolean commissionWaived,
			Integer commissionRateBp,
			String cancelReason,
			Instant placedAt,
			Instant acceptedAt,
			Instant shippedAt,
			Instant deliveredAt,
			Instant cancelledAt,
			List<OrderItem.OrderItemView> lines,
			List<OrderStatusHistory.HistoryView> timeline,
			Set<ShopOrder.Status> nextStatuses) {

		static OrderDetail from(ShopOrder order, List<OrderItem> lines,
				List<OrderStatusHistory> timeline, Set<ShopOrder.Status> nextStatuses) {

			return new OrderDetail(
					order.getId(),
					order.getOrderNumber(),
					order.getStatus(),
					order.getPaymentStatus(),
					order.getPaymentMethod(),
					order.getGuestEmail(),
					order.getGuestPhone(),
					order.getCustomerNote(),
					order.getAdminNote(),
					order.getShippingAddress(),
					order.getBillingAddress(),
					order.getSubtotalMinor(),
					order.getDiscountMinor(),
					order.getTaxMinor(),
					order.getShippingMinor(),
					order.getTotalMinor(),
					Money.ofMinor(order.getTotalMinor()).toDisplay(),
					order.getCommissionMinor(),
					order.isCommissionWaived(),
					order.getCommissionRateBp(),
					order.getCancelReason(),
					order.getPlacedAt(),
					order.getAcceptedAt(),
					order.getShippedAt(),
					order.getDeliveredAt(),
					order.getCancelledAt(),
					lines.stream().map(OrderItem.OrderItemView::from).toList(),
					timeline.stream().map(OrderStatusHistory.HistoryView::from).toList(),
					nextStatuses);
		}
	}

	public record Transitions(String current, Set<ShopOrder.Status> next, boolean cancellable) {
	}

	public record StockMovementView(
			UUID productId,
			UUID variantId,
			int delta,
			String reason,
			String note,
			Instant at) {

		static StockMovementView from(
				com.storefront.store.inventory.InventoryMovement movement) {
			return new StockMovementView(
					movement.getProductId(),
					movement.getVariantId(),
					movement.getDelta(),
					movement.getReason().name(),
					movement.getNote(),
					movement.getCreatedAt());
		}
	}

	/**
	 * A page of results.
	 *
	 * <p>A record rather than Spring's {@code Page}, which serialises internal pagination state
	 * into the response and would make a repository detail part of the public contract.
	 */
	public record PageResponse<T>(List<T> content, int page, int size, long totalElements,
			int totalPages, boolean last) {

		static <E, T> PageResponse<T> from(Page<E> page,
				java.util.function.Function<E, T> mapper) {
			return new PageResponse<>(
					page.getContent().stream().map(mapper).toList(),
					page.getNumber(),
					page.getSize(),
					page.getTotalElements(),
					page.getTotalPages(),
					page.isLast());
		}
	}
}
