package com.storefront.store.customer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.Money;
import com.storefront.store.order.OrderItem;
import com.storefront.store.order.OrderItemRepository;
import com.storefront.store.order.OrderService;
import com.storefront.store.order.OrderStatusHistory;
import com.storefront.store.order.OrderStatusHistoryRepository;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.order.ShopOrderRepository;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.shop.ShopRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * The signed-in customer's own orders (Phase 5).
 *
 * <p>Class-level {@code @PreAuthorize} for the customers realm, so a shop owner cannot read
 * somebody else's order history and an admin cannot either.
 *
 * <p><b>Every lookup goes through {@link CustomerOrderService}, which scopes by customer and shop.</b>
 * Nothing here fetches an order by id and returns it: order numbers are per shop, so
 * {@code ORD-0001} exists in every shop, and an id-only lookup is how one customer's order ends up
 * on another's screen.
 *
 * <p><b>The tracking timeline is the order's own history</b>, not a re-derived guess. The history is
 * written by the same transition that moved the order, so it cannot disagree with the status.
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "Customer account", description = "The caller's own orders, tracking and reorder")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CustomerOrderController {

	private final CustomerOrderService customerOrders;
	private final OrderStatusHistoryRepository history;
	private final OrderItemRepository items;
	private final OrderService orders;
	private final ShopRepository shops;
	private final CurrentUser currentUser;

	/** Everything this customer has ordered at one shop, newest first. */
	@GetMapping("/shops/{shopSlug}/orders")
	@Operation(summary = "This customer's orders at one shop")
	public PageResponse<OrderSummary> history(
			@PathVariable String shopSlug,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

		UUID me = currentUser.require().getId();
		Page<ShopOrder> found = customerOrders.history(me, shopSlug, page, size);

		return new PageResponse<>(
				found.getContent().stream().map(this::summary).toList(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	/** Orders still in play, for the "track your parcel" strip above the fold. */
	@GetMapping("/shops/{shopSlug}/orders/in-flight")
	@Operation(summary = "Orders that have not yet been delivered")
	public List<OrderSummary> inFlight(@PathVariable String shopSlug) {
		UUID me = currentUser.require().getId();
		return customerOrders.inFlight(me, shopSlug).stream().map(this::summary).toList();
	}

	/** One order with its lines and its full status timeline. */
	@GetMapping("/shops/{shopSlug}/orders/{orderNumber}")
	@Operation(summary = "One order, with its lines and tracking timeline")
	public ResponseEntity<OrderDetail> one(@PathVariable String shopSlug,
			@PathVariable String orderNumber) {

		UUID me = currentUser.require().getId();
		ShopOrder order = customerOrders.one(me, shopSlug, orderNumber);

		// 404 rather than 403 for another customer's order. Saying "forbidden" would confirm that
		// the order number exists, which is the only thing an enumeration attempt needs.
		if (order == null) {
			return ResponseEntity.notFound().build();
		}

		var timeline = history.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
				.map(this::step)
				.toList();

		var lines = items.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
				.map(this::line)
				.toList();

		return ResponseEntity.ok(new OrderDetail(
				order.getId().toString(),
				order.getOrderNumber(),
				order.getStatus().name(),
				order.getPaymentMethod().name(),
				order.getPaymentStatus().name(),
				order.getTotalMinor(),
				Money.ofMinor(order.getTotalMinor()).toDisplay(),
				order.getPlacedAt(),
				timeline,
				lines));
	}

	/**
	 * Rebuilds a basket from a past order.
	 *
	 * <p>A preview, not an order: placing it still goes through checkout, which re-prices and
	 * re-checks stock authoritatively. Replaying an old order's stored totals would let a customer
	 * buy at a price the shop no longer charges.
	 */
	@GetMapping("/shops/{shopSlug}/orders/{orderNumber}/reorder")
	@Operation(summary = "The lines of a past order that can be bought again")
	public CustomerOrderService.ReorderPreview reorder(@PathVariable String shopSlug,
			@PathVariable String orderNumber) {

		return customerOrders.reorder(currentUser.require().getId(), shopSlug, orderNumber);
	}

	// ------------------------------------------------------------------ mapping

	private OrderSummary summary(ShopOrder order) {
		return new OrderSummary(
				order.getOrderNumber(),
				order.getStatus().name(),
				order.getPaymentMethod().name(),
				order.getPaymentStatus().name(),
				order.getTotalMinor(),
				Money.ofMinor(order.getTotalMinor()).toDisplay(),
				order.getPlacedAt());
	}

	private OrderLine line(OrderItem item) {
		return new OrderLine(
				item.getId(),
				item.getProductName(),
				item.getVariantName(),
				item.getQuantity(),
				item.getUnitPriceMinor(),
				Money.ofMinor(item.getUnitPriceMinor()).toDisplay(),
				item.getLineTotalMinor(),
				Money.ofMinor(item.getLineTotalMinor()).toDisplay(),
				item.getImageUrl());
	}

	private Step step(OrderStatusHistory entry) {
		// toStatus, not fromStatus: a history row records the state the order moved *into*, and the
		// status an owner reads is the one the order is in now.
		return new Step(entry.getToStatus().name(), entry.getNote(), entry.getCreatedAt());
	}

	// ------------------------------------------------------------------ types

	/**
	 * An order in a list.
	 *
	 * <p><b>No address, no email, no shop id.</b> This is the customer's own list of their own
	 * orders, so those are redundant — and a response shape that carries a delivery address to a
	 * list endpoint is a response shape that eventually leaks one.
	 */
	public record OrderSummary(
			String orderNumber,
			String status,
			String paymentMethod,
			String paymentStatus,
			long totalMinor,
			String totalDisplay,
			java.time.Instant placedAt) {
	}

	public record OrderDetail(
			String id,
			String orderNumber,
			String status,
			String paymentMethod,
			String paymentStatus,
			long totalMinor,
			String totalDisplay,
			java.time.Instant placedAt,
			List<Step> timeline,
			List<OrderLine> lines) {
	}

	/**
	 * One step of the tracking timeline.
	 *
	 * <p>Read from {@code order_status_history}, which is written by the same transition that moved
	 * the order. A timeline re-derived from the current status would show one row and could not say
	 * when anything happened.
	 */
	public record Step(String status, String note, java.time.Instant at) {
	}

	/**
	 * One line of an order, as the customer sees it.
	 *
	 * <p>{@code id} added in Phase 8: a return request names the lines it covers by id, and without
	 * one the customer screen could only ask "return everything", which is the one thing a return
	 * workflow is not for.
	 */
	public record OrderLine(
			java.util.UUID id,
			String productName,
			String variantName,
			int quantity,
			long unitPriceMinor,
			String unitPriceDisplay,
			long lineTotalMinor,
			String lineTotalDisplay,
			String imageUrl) {
	}

	public record PageResponse<T>(
			List<T> content,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}
}