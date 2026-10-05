package com.storefront.store.customer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ProductVariantRepository;
import com.storefront.store.order.OrderItem;
import com.storefront.store.order.OrderItemRepository;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.order.ShopOrderRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The customer's own orders: history, tracking, and reorder (Phase 5).
 *
 * <p><b>Every query is scoped by customer <em>and</em> shop.</b> Order numbers are allocated per
 * shop, so {@code ORD-0001} exists in every shop on the platform. A lookup by customer alone would
 * let a crafted link to {@code /orders/ORD-0001} return an order from a shop the customer has never
 * used — a small leak that reveals another shop's pricing and, in the worst case, another
 * customer's address through a shared link.
 *
 * <p><b>Reorder re-quotes rather than re-orders.</b> It returns the lines that can still be bought
 * at today's prices, and says plainly which cannot. Reordering an old order at yesterday's price
 * would quietly undercharge the shop; reordering it at a price the product no longer sells at
 * would quietly overcharge the customer. Both are worse than a list that says "this changed".
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomerOrderService {

	/**
	 * Orders in flight, for the storefront's "track your parcel" strip.
	 *
	 * <p>Everything except delivered and cancelled: an order that has left the warehouse is the only
	 * one whose status changes, and a list of settled orders is history, not tracking.
	 */
	private static final List<ShopOrder.Status> IN_FLIGHT = List.of(
			ShopOrder.Status.PENDING,
			ShopOrder.Status.ACCEPTED,
			ShopOrder.Status.PROCESSING,
			ShopOrder.Status.SHIPPED);

	/** Reorderable: anything still buyable, which excludes only cancelled and delivered. */
	private static final List<ShopOrder.Status> REORDERABLE = List.of(
			ShopOrder.Status.PENDING,
			ShopOrder.Status.ACCEPTED,
			ShopOrder.Status.PROCESSING,
			ShopOrder.Status.SHIPPED);

	private static final int MAX_REORDER_LINES = 50;

	private final ShopOrderRepository orders;
	private final OrderItemRepository items;
	private final ProductVariantRepository variants;
	private final com.storefront.store.catalog.ProductRepository products;
	private final ShopRepository shops;

	// ------------------------------------------------------------------ read

	/** One customer's orders at one shop, newest first. */
	@Transactional(readOnly = true)
	public Page<ShopOrder> history(UUID customerId, String shopSlug, int page, int size) {
		Shop shop = requireShop(shopSlug);
		return orders.findByCustomerIdAndShopIdOrderByPlacedAtDesc(
				customerId, shop.getId(), PageRequest.of(Math.max(page, 0), clamp(size)));
	}

	/** One customer's orders at one shop that are still moving. */
	@Transactional(readOnly = true)
	public List<ShopOrder> inFlight(UUID customerId, String shopSlug) {
		Shop shop = requireShop(shopSlug);
		List<ShopOrder> found = orders.findByCustomerIdAndStatusInOrderByPlacedAtDesc(
				customerId, IN_FLIGHT);
		// Filtered in memory by shop because the derived query cannot express "any shop"; a customer
		// has orders at several shops and the strip is per-shop.
		return found.stream().filter(o -> o.getShopId().equals(shop.getId())).toList();
	}

	/** One order, by number, for the tracking page. Empty when it is not this customer's. */
	@Transactional(readOnly = true)
	public ShopOrder one(UUID customerId, String shopSlug, String orderNumber) {
		Shop shop = requireShop(shopSlug);
		return orders.findByCustomerIdAndShopIdAndOrderNumber(customerId, shop.getId(), orderNumber)
				.orElse(null);
	}

	// ------------------------------------------------------------------ reorder

	/**
	 * Rebuilds a basket from a past order.
	 *
	 * <p><b>Re-quoted, not replayed.</b> Each line is looked up again and priced now. A line whose
	 * product or variant no longer exists, or whose variant is now unavailable, comes back in
	 * {@code unavailable} with a reason rather than being silently dropped -- a reorder that quietly
	 * loses the milk the customer wanted is worse than one that says the milk is gone.
	 *
	 * <p>Nothing is written. This returns a quote-shaped preview the storefront turns into a basket;
	 * placing the order still goes through {@code CheckoutService}, which re-prices and re-checks
	 * stock authoritatively.
	 */
	@Transactional(readOnly = true)
	public ReorderPreview reorder(UUID customerId, String shopSlug, String orderNumber) {

		ShopOrder order = one(customerId, shopSlug, orderNumber);
		if (order == null) {
			return new ReorderPreview(orderNumber, List.of(), List.of(), 0, false,
					"That order is not yours, or does not exist.");
		}

		if (!REORDERABLE.contains(order.getStatus())) {
			String why = order.getStatus() == ShopOrder.Status.DELIVERED
					? "This order has already been delivered. Add the items again from the shop."
					: "This order was cancelled, so there is nothing to repeat.";
			return new ReorderPreview(orderNumber, List.of(), List.of(), 0, false, why);
		}

		List<ShopOrder> sameShop = orders
				.findByCustomerIdAndShopIdOrderByPlacedAtDesc(
						customerId, order.getShopId(), PageRequest.of(0, 1))
				.getContent();
		// Defensive: an order whose shop differs from the URL was already filtered by one(), so
		// reaching here means the ids agree.
		if (sameShop.isEmpty()) {
			return new ReorderPreview(orderNumber, List.of(), List.of(), 0, false,
					"That order is not yours, or does not exist.");
		}

		List<OrderItem> lines = items.findByOrderIdOrderByCreatedAtAsc(order.getId());
		List<ReorderLine> available = new ArrayList<>();
		List<UnavailableLine> unavailable = new ArrayList<>();

		for (OrderItem line : lines) {
			if (available.size() >= MAX_REORDER_LINES) {
				break;
			}

			ProductVariant variant = line.getVariantId() == null
					? null
					: variants.findById(line.getVariantId()).orElse(null);

			if (variant == null) {
				unavailable.add(new UnavailableLine(line.getProductName(), line.getVariantName(),
						"no longer available"));
				continue;
			}

			// Both halves matter. A deactivated variant is not buyable whatever its product's
			// status, and an archived product is not buyable however active its variant is.
			boolean variantLive = variant.isActive();
			boolean productLive = products.findById(variant.getProductId())
					.map(p -> p.getStatus() != Product.Status.ARCHIVED)
					.orElse(false);

			if (variantLive && productLive) {
				available.add(new ReorderLine(
						variant.getId().toString(),
						line.getProductName(),
						line.getVariantName(),
						line.getQuantity(),
						// Today's price, not the price paid then. Replaying yesterday's price would
						// quietly undercharge the shop and overcharge the customer's expectations;
						// the storefront re-quotes on add anyway, and sending it here means the
						// preview and the basket agree.
						variant.getPriceMinor()));
			} else if (!productLive) {
				unavailable.add(new UnavailableLine(line.getProductName(), line.getVariantName(),
						"this product has been retired"));
			} else {
				unavailable.add(new UnavailableLine(line.getProductName(), line.getVariantName(),
						"this option is no longer available"));
			}
		}

		String message = unavailable.isEmpty()
				? "Everything from this order is still available."
				: unavailable.size() + " item" + (unavailable.size() == 1 ? " is" : "s are")
						+ " no longer available. The rest can be reordered.";

		return new ReorderPreview(orderNumber, available, unavailable, available.size(),
				unavailable.isEmpty(), message);
	}

	// ------------------------------------------------------------------ helpers

	private Shop requireShop(String slug) {
		if (slug == null || slug.isBlank()) {
			throw new IllegalArgumentException("A shop slug is required");
		}
		return shops.findBySlugIgnoringCase(slug.trim())
				.orElseThrow(() -> new IllegalArgumentException("No shop called " + slug));
	}

	/** Bounded so a crafted {@code size} cannot ask for the whole table. */
	private static int clamp(int size) {
		return Math.min(Math.max(size, 1), 100);
	}

	// ------------------------------------------------------------------ types

	/**
	 * A reorder, as a preview. Nothing is written.
	 *
	 * @param complete whether <em>every</em> line came back available. A partial reorder is still
	 *        offered, but the storefront says what is missing rather than presenting three of four
	 *        items as the whole order.
	 */
	public record ReorderPreview(
			String orderNumber,
			List<ReorderLine> available,
			List<UnavailableLine> unavailable,
			int lineCount,
			boolean complete,
			String message) {

		public ReorderPreview {
			available = List.copyOf(available);
			unavailable = List.copyOf(unavailable);
		}
	}

	/** A line that can be bought again, at today's price. */
	public record ReorderLine(String variantId, String productName, String variantName,
			int quantity, long priceMinor) {
	}

	/** A line that cannot, and why. Shown rather than dropped: a reorder that quietly loses the
	 *  milk the customer wanted is worse than one that says the milk is gone. */
	public record UnavailableLine(String productName, String variantName, String reason) {
	}
}
