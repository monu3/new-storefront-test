package com.storefront.store.checkout;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.storefront.store.common.Money;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The customer-facing checkout: quote, then place.
 *
 * <p>Two endpoints, and the split is the point. {@link #quote} prices a basket as often as the
 * customer likes; {@link #place} creates an order exactly once. A single "checkout" endpoint that
 * created an order on every call would consume an order number and reserve stock each time the
 * customer refreshed — which is how a shop ends up with reservations for orders nobody placed.
 *
 * <p><b>The client sends no prices.</b> It sends variant ids and quantities; every amount in the
 * response is computed by {@link QuoteService}. That is not a validation nicety — it is the only
 * arrangement in which the server's arithmetic is authoritative.
 *
 * <p>{@link #place} accepts an optional customer token. With one, the order is attached to the
 * account and appears in their history; without one it is a guest order identified by email,
 * which is why the email field is required either way.
 */
@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout", description = "Server-side quoting and order placement")
@RequiredArgsConstructor
public class CheckoutController {

	private final CheckoutService checkout;
	private final ShopRepository shops;
	private final CurrentUser currentUser;
	private final ObjectMapper objectMapper;

	/**
	 * Prices a basket. Creates nothing.
	 *
	 * <p>Read-only, so a customer refreshing the cart cannot consume a discount's usage count or
	 * allocate an order number.
	 */
	@PostMapping("/quote")
	@Operation(summary = "Price a basket",
			description = "Server-authoritative. Send variant ids and quantities only; all amounts are computed here.")
	public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
		Shop shop = requireShop(request.shopSlug());
		UUID customerId = currentUser.optionalUserId();

		Quote quote = checkout.quote(shop, toRequestedLines(request.lines()), request.city(),
				request.discountCode(), customerId);

		return QuoteResponse.from(quote, request.city());
	}

	/** Creates the order. Idempotent per idempotency key, so a retry is a second attempt, not a second order. */
	@PostMapping("/orders")
	@Operation(summary = "Place an order",
			description = "Quotes server-side, reserves stock, and records the payment attempt.")
	public ResponseEntity<OrderPlacedResponse> place(@Valid @RequestBody PlaceRequest request) {
		UUID customerId = currentUser.optionalUserId();

		CheckoutService.Placement placement = checkout.place(new CheckoutService.PlaceRequest(
				request.shopSlug(),
				toRequestedLines(request.lines()),
				request.email(),
				request.phone(),
				customerId,
				request.city(),
				request.discountCode(),
				toAddressJson(request.shippingAddress()),
				request.billingAddress() == null ? null : toAddressJson(request.billingAddress()),
				request.note(),
				request.paymentMethod()));

		// 201: an order now exists that did not before.
		return ResponseEntity.status(201)
				.body(OrderPlacedResponse.from(placement, request.shippingAddress()));
	}

	/**
	 * The payment methods a customer may choose right now.
	 *
	 * <p>eSewa's presence is decided by whether the gateway can actually take a payment, not by a
	 * hardcoded list. Offering a method that will be refused at checkout is worse than not offering
	 * it: the customer has entered their details by the time they find out.
	 */
	@GetMapping("/payment-methods")
	@Operation(summary = "Available payment methods")
	public List<PaymentMethodView> paymentMethods() {
		List<PaymentMethodView> methods = new java.util.ArrayList<>();

		if (esewa != null && esewa.isAvailable()) {
			methods.add(new PaymentMethodView("ESAWA", "eSewa",
					"Pay with your eSewa wallet."));
		}
		methods.add(new PaymentMethodView("COD", "Cash on delivery",
				"Pay the courier when your order arrives."));
		methods.add(new PaymentMethodView("BANK_TRANSFER", "Bank transfer",
				"Transfer to the shop's account; the shop confirms it."));
		return methods;
	}

	/**
	 * The signed page that starts an eSewa payment for an order.
	 *
	 * <p>Returned as HTML rather than as a redirect because eSewa's checkout takes a POST: there is
	 * no URL to send the customer to. The storefront opens this in a frame or a tab and lets the
	 * form submit itself.
	 *
	 * <p><b>Looked up by order number, not by id, and it is not authenticated.</b> It has to be
	 * reachable from eSewa's return, which arrives in the customer's browser with no session on our
	 * side. That makes this a public endpoint, so it is scoped as tightly as it can be: it exposes
	 * only a payment page for a specific order, the amount is the order's own recorded total rather
	 * than anything supplied here, and it reveals no customer detail.
	 */
	@GetMapping(value = "/shops/{shopSlug}/orders/{orderNumber}/pay", produces = "text/html; charset=UTF-8")
	@Operation(summary = "The signed eSewa payment page for an order",
			description = "Public: reached from eSewa's return, which has no session with us.")
	public ResponseEntity<String> payForOrder(@PathVariable String shopSlug,
			@PathVariable String orderNumber) {
		if (esewa == null || !esewa.isAvailable()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body("<!doctype html><p>eSewa is not available. Please choose another payment method.</p>");
		}

		ShopOrder order = findOrder(shopSlug, orderNumber);
		if (order == null || order.getPaymentMethod() != com.storefront.store.order.ShopOrder.PaymentMethod.ESAWA) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body("<!doctype html><p>No eSewa payment is outstanding for that order.</p>");
		}

		com.storefront.store.shop.Shop shop = shops.findById(order.getShopId()).orElse(null);
		if (shop == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body("<!doctype html><p>That shop no longer exists.</p>");
		}

		var context = new com.storefront.store.payment.PaymentGateway.ShopContext(
				shop.getId(), null, null, null, null,
				storefront.baseUrl() + "/checkout?order=" + orderNumber,
				storefront.baseUrl() + "/checkout?order=" + orderNumber + "&payment=failed");

		String html = esewa.redirectFor(
				com.storefront.store.common.Money.ofMinor(order.getTotalMinor()),
				order.getOrderNumber(), context).autoPostHtml();

		// A payment page must never be cached: a stale one carries a spent transaction uuid.
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.header("Cache-Control", "no-store")
				.body(html);
	}

	/**
	 * What actually happened to an order's payment.
	 *
	 * <p>Public for the same reason {@code /pay} is: it is read from the return page. It queries
	 * eSewa server to server, so the answer comes from eSewa rather than from the browser.
	 */
	@GetMapping("/orders/{orderNumber}/payment-status")
	@Operation(summary = "The authoritative payment state for an order")
	public PaymentStatusView paymentStatus(@PathVariable String shopSlug,
			@PathVariable String orderNumber) {
		ShopOrder order = findOrder(shopSlug, orderNumber);
		if (order == null) {
			return new PaymentStatusView("UNKNOWN", "That order does not exist.", false);
		}
		return PaymentStatusView.from(payments.stateOf(order));
	}

	/**
	 * The payment state, flattened for the storefront.
	 *
	 * <p>A projection of {@link PaymentService.State} rather than a second definition of it: two
	 * types saying what "paid" means is how a status page and the payment service start telling
	 * a customer different things about the same order.
	 */
	public record PaymentStatusView(String status, String message, boolean paid) {

		static PaymentStatusView from(PaymentService.State state) {
			return new PaymentStatusView(state.status(), state.message(), state.paid());
		}
	}

	/** The shops a customer can buy from, for the storefront's shop picker. */
	@GetMapping("/shops")
	@Operation(summary = "Verified shops that can be ordered from")
	public List<ShopView> orderableShops() {
		return shops.findByStatusOrderByNameAsc(Shop.Status.VERIFIED,
						org.springframework.data.domain.Pageable.ofSize(200))
				.getContent().stream()
				.map(s -> new ShopView(s.getId().toString(), s.getSlug(), s.getName(), s.getType().name()))
				.toList();
	}

	private final com.storefront.store.payment.EsewaGateway esewa;
	private final PaymentService payments;
	private final StorefrontProperties storefront;
	private final com.storefront.store.order.ShopOrderRepository orderRepository;

	// ------------------------------------------------------------------ helpers

	/**
	 * Finds one of a shop's orders by its number.
	 *
	 * <p>Scoped by shop because order numbers are allocated per shop, so {@code ORD-0001} exists in
	 * every shop and a global lookup would either be ambiguous or need a full scan of the order
	 * table. The repository already has the exact query for this pair.
	 *
	 * <p><b>The slug is not a credential, and what it discloses is narrow.</b> These endpoints are
	 * reached from eSewa's return, where the customer has no session with us, so there is no caller
	 * to resolve a shop from. But {@code /pay} returns a payment page for one order priced from
	 * that order's own recorded total, and {@code /payment-status} returns a state and a sentence.
	 * Neither exposes a customer, an address, or an order list.
	 *
	 * <p>The storefront already knows the slug — it is the shop the customer is standing in — so
	 * nothing about the flow is compromised by requiring it.
	 */
	private ShopOrder findOrder(String shopSlug, String orderNumber) {
		return shops.findBySlugIgnoringCase(slugOrEmpty(shopSlug))
				.flatMap(shop -> orderRepository.findByShopIdAndOrderNumber(
						shop.getId(), orderNumber))
				.orElse(null);
	}

	/** A null or blank slug matches nothing rather than throwing. */
	private String slugOrEmpty(String slug) {
		return slug == null ? "" : slug.trim();
	}


	/**
	 * The shop being bought from.
	 *
	 * <p>An unverified or suspended shop reads as absent rather than forbidden, so a customer
	 * cannot discover that a shop exists but is hidden by the error.
	 */
	/**
	 * The request's lines as the service wants them.
	 *
	 * <p>A mapping rather than having the controller's record <em>be</em> the service's type. The
	 * request record carries Bean Validation annotations and is part of the HTTP contract; the
	 * service's record is the domain's. Merging them would mean adding a validation annotation to
	 * the domain type, or the reverse.
	 */
	private List<QuoteService.RequestedLine> toRequestedLines(List<LineRequest> lines) {
		return lines.stream()
				.map(l -> new QuoteService.RequestedLine(l.variantId(), l.quantity()))
				.toList();
	}

	private Shop requireShop(String slug) {
		return shops.findBySlugIgnoringCase(slug)
				.filter(Shop::isVerified)
				.orElseThrow(() -> new CheckoutService.ShopUnavailableException(slug));
	}

	/**
	 * Serialises an address for the order's snapshot column.
	 *
	 * <p>Snapshotted as one JSON blob rather than columns: the shape varies by country, and an
	 * edited customer address must not rewrite where a shipped parcel went.
	 *
	 * <p><b>Serialised with Jackson, not {@code Map.toString()}.</b> That looks like an obvious
	 * thing not to do and it was done anyway, producing {@code {name=Ravi, city=Kathmandu}} —
	 * which is not JSON, and which Postgres rejected with "invalid input syntax for type json" and
	 * an unhelpful 401 from the security filter in front of it. An explicit field list is used
	 * rather than serialising the request record directly, so the stored shape is fixed: adding a
	 * field to the API must not silently change what is stored on every future order.
	 */
	private String toAddressJson(Address address) {
		Map<String, Object> json = new java.util.LinkedHashMap<>();
		json.put("name", address.name());
		json.put("phone", address.phone());
		json.put("line1", address.line1());
		json.put("line2", address.line2());
		json.put("city", address.city());
		json.put("state", address.state());
		json.put("postalCode", address.postalCode());
		json.put("country", address.country() == null ? "NP" : address.country());
		json.put("notes", address.notes());

		try {
			return objectMapper.writeValueAsString(json);
		} catch (JsonProcessingException e) {
			// Unreachable for a map of strings and nulls, but it must not surface as a 500 if
			// that ever stops being true.
			throw new IllegalStateException("Could not serialise the delivery address", e);
		}
	}

	// ------------------------------------------------------------------ requests

	/** One basket line. No price — the server supplies it. */
	public record LineRequest(@NotNull UUID variantId, @Min(1) int quantity) {
	}

	public record QuoteRequest(
			@NotBlank String shopSlug,
			@NotEmpty List<@NotNull LineRequest> lines,
			String city,
			@Size(max = 60) String discountCode) {
	}

	public record Address(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Size(max = 40) String phone,
			@NotBlank @Size(max = 200) String line1,
			@Size(max = 200) String line2,
			@NotBlank @Size(max = 80) String city,
			@Size(max = 80) String state,
			@Size(max = 20) String postalCode,
			@Size(max = 2) String country,
			@Size(max = 300) String notes) {
	}

	public record PlaceRequest(
			@NotBlank String shopSlug,
			@NotEmpty List<@NotNull LineRequest> lines,
			/** Required for a guest order; optional when a customer is signed in. */
			@Email @Size(max = 200) String email,
			@Size(max = 40) String phone,
			@NotNull Address shippingAddress,
			Address billingAddress,
			@Size(max = 80) String city,
			@Size(max = 60) String discountCode,
			@Size(max = 500) String note,
			@NotNull ShopOrder.PaymentMethod paymentMethod) {
	}

	// ------------------------------------------------------------------ responses

	/** The priced basket, exactly as the server computed it. */
	public record QuoteResponse(
			List<Quote.QuoteLine> lines,
			long subtotal,
			long discount,
			long tax,
			long shipping,
			long total,
			String totalDisplay,
			String currency,
			String discountCode,
			String discountName,
			/** Why a supplied code did not apply. Null when there was none, or it worked. */
			String discountMessage,
			String deliveryEstimate,
			/** True when every line can actually be bought. */
			boolean purchasable,
			String deliveryCity) {

		static QuoteResponse from(Quote quote, String deliveryCity) {
			return new QuoteResponse(
					quote.lines(),
					quote.subtotalMinor(),
					quote.discountMinor(),
					quote.taxMinor(),
					quote.shippingMinor(),
					quote.totalMinor(),
					Money.ofMinor(quote.totalMinor()).toDisplay(),
					quote.currency(),
					quote.appliedDiscount() == null ? null : quote.appliedDiscount().getCode(),
					quote.appliedDiscount() == null ? null : quote.appliedDiscount().getName(),
					quote.discountMessage(),
					quote.deliveryEstimateOrDefault(),
					quote.lines().stream().allMatch(Quote.QuoteLine::purchasable),
					deliveryCity);
		}
	}

	/** Confirmation. Enough for a "thank you" page and an order lookup. */
	public record OrderPlacedResponse(
			UUID orderId,
			String orderNumber,
			String status,
			String paymentMethod,
			long total,
			String totalDisplay,
			/** What the customer must do next, e.g. bank details. Null for cash. */
			String paymentInstructions,
			String paymentReference,
			String customerEmail,
			String deliveryName,
			String deliveryCity,
			List<Quote.QuoteLine> lines) {

		static OrderPlacedResponse from(CheckoutService.Placement placement, Address address) {
			ShopOrder order = placement.order();
			return new OrderPlacedResponse(
					order.getId(),
					order.getOrderNumber(),
					order.getStatus().name(),
					order.getPaymentMethod().name(),
					order.getTotalMinor(),
					Money.ofMinor(order.getTotalMinor()).toDisplay(),
					instructionsFor(placement),
					placement.payment() == null ? null : placement.payment().getGatewayRef(),
					order.getGuestEmail(),
					address.name(),
					address.city(),
					placement.quote().lines());
		}

		/** The payment instructions, straight from the gateway that produced them. */
		private static String instructionsFor(CheckoutService.Placement placement) {
			if (placement.order().getPaymentMethod() == ShopOrder.PaymentMethod.COD) {
				return "Pay in cash when your order arrives.";
			}
			return "Check your email for payment instructions.";
		}
	}

	public record PaymentMethodView(String code, String name, String description) {
	}

	public record ShopView(String id, String slug, String name, String shopType) {
	}
}
