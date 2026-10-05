package com.storefront.store.checkout;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.order.OrderNumberService;
import com.storefront.store.order.OrderService;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.payment.PaymentGateway;
import com.storefront.store.payment.PaymentMethodService;
import com.storefront.store.payment.SecretCipher;
import com.storefront.store.payment.PaymentTransaction;
import com.storefront.store.payment.PaymentTransactionRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a basket into an order.
 *
 * <p><b>Everything is quoted, then placed from the quote.</b> The order is built from the numbers
 * {@link QuoteService} produced in this same call, never from a second calculation and never from
 * anything the client sent. So the figure the customer was shown and the figure they were charged
 * cannot disagree.
 *
 * <p><b>The order number is allocated in this same transaction.</b> A checkout that fails on
 * out-of-stock therefore leaves no gap in the shop's sequence, and the customer's retry gets the
 * number they would have got anyway. Numbers are never reused because orders are never hard
 * deleted — a cancelled order keeps its number, which is what its invoice needs.
 *
 * <p><b>A payment attempt is recorded for every order, including cash.</b> The audit trail
 * should show that COD was chosen, not merely that nothing happened — "we never asked for cash"
 * is exactly what a shop owner needs to be able to check.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CheckoutService {

	private final QuoteService quotes;
	private final OrderService orders;
	private final OrderNumberService orderNumbers;
	private final PaymentTransactionRepository payments;
	private final ShopRepository shops;
	private final com.storefront.store.settings.ShopSettingService settings;
	private final com.storefront.store.payment.PaymentGatewayRegistry gateways;

	// ------------------------------------------------------------------ quote

	/**
	 * Prices a basket without creating anything.
	 *
	 * <p>Read-only, and called on every cart page render. It cannot have side effects, so a
	 * customer refreshing the basket cannot consume a discount's usage count.
	 */
	@Transactional(readOnly = true)
	public Quote quote(Shop shop, List<QuoteService.RequestedLine> lines, String city, String code,
			UUID customerId) {
		return quotes.quote(shop, lines, city, code, customerId);
	}

	// ------------------------------------------------------------------ place

	/**
	 * Creates an order from a basket.
	 *
	 * @param request what the customer entered, plus the basket they chose
	 * @return the placed order and its payment instructions
	 */
	@Transactional
	public Placement place(PlaceRequest request) {

		Shop shop = shops.findBySlug(request.shopSlug())
				.filter(Shop::isVerified)
				// A shop that is not verified reads as absent, not as forbidden: an unverified shop
				// must be indistinguishable from one that was never created.
				.orElseThrow(() -> new ShopUnavailableException(request.shopSlug()));

		// The owner can close the shop. Read here rather than in the quote because a closed shop
		// must refuse the *order*, not merely stop quoting: a basket priced moments before closing
		// is still a basket the customer expects to be honoured.
		//
		// A shop with no settings row accepts orders, so this changes nothing until an owner
		// deliberately switches it off.
		if (!settings.checkout(shop.getId()).acceptOrders()) {
			throw new ShopClosedException(request.shopSlug());
		}

		Quote quote = quotes.quote(shop, request.lines(), request.city(), request.discountCode(),
				request.customerId());

		if (quote.lines().stream().anyMatch(l -> !l.purchasable())) {
			String name = quote.lines().stream().filter(l -> !l.purchasable())
					.map(Quote.QuoteLine::productName).findFirst().orElse("An item");
			throw new OutOfStockException(name);
		}

		validateContact(request);

		// Inside this transaction on purpose: a failed checkout must leave no gap in the shop's
		// numbering. See the class note on OrderNumberService.
		String orderNumber = orderNumbers.next(shop.getId());

		OrderService.PlacedOrder placed = new OrderService.PlacedOrder(
				orderNumber,
				request.paymentMethod(),
				quote.subtotalMinor(),
				quote.discountMinor(),
				quote.taxMinor(),
				quote.shippingMinor(),
				request.shippingAddressJson(),
				request.billingAddressJson(),
				request.email(),
				request.phone(),
				request.customerId(),
				request.customerNote(),
				allowsBackorder(quote),
				quote.lines().stream().map(this::toPlacedLine).toList());

		ShopOrder order = orders.place(shop, placed, request.customerId());

		if (quote.appliedDiscount() != null) {
			// Recorded at placement, not at quote: a customer refreshing the cart must not exhaust
			// a discount's usage limit.
			quote.appliedDiscount().recordUse();
		}

		PaymentTransaction payment = recordPayment(shop, order, quote, request.paymentMethod());

		log.info("Checkout complete: {} for {} ({} minor)", orderNumber, shop.getId(),
				quote.totalMinor());
		return new Placement(order, quote, payment);
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Whether any line in the basket allows a backorder.
	 *
	 * <p>Conservative: it is enough for one line to permit it, because the order is placed as a
	 * whole and the reservation is per line. Requiring every line to agree would fail a basket
	 * whose only made-to-order item was the one the customer cared about.
	 */
	private boolean allowsBackorder(Quote quote) {
		return quote.lines().stream().anyMatch(line -> line.availableStock() < line.quantity());
	}

	private OrderService.PlacedLine toPlacedLine(Quote.QuoteLine line) {
		return new OrderService.PlacedLine(
				UUID.fromString(line.productId()),
				UUID.fromString(line.variantId()),
				line.productName(),
				line.variantName(),
				ProductCategory.valueOf(line.category()),
				line.unitPriceMinor(),
				line.quantity(),
				null,
				line.imageUrl());
	}

	/**
	 * Records the payment attempt.
	 *
	 * <p>Written for every method including cash, so the trail shows what was chosen. The
	 * idempotency key makes a retried checkout request a no-op rather than a second charge.
	 */
	private PaymentTransaction recordPayment(Shop shop, ShopOrder order, Quote quote,
			ShopOrder.PaymentMethod method) {

		PaymentTransaction transaction = new PaymentTransaction(
				order.getId(), shop.getId(),
				toGatewayMethod(method),
				toGateway(method),
				quote.totalMinor(),
				order.getOrderNumber(),
				PaymentTransaction.keyFor(order.getId(), 1));

		if (method == ShopOrder.PaymentMethod.COD) {
			// The shop collects the cash, so nothing is verified now. The order is marked paid
			// at dispatch, when the money is actually in hand.
			return payments.saveAndFlush(transaction);
		}

		// The gateway validates the shop's own configuration before the order exists, so a shop
		// that enabled bank transfer without account details fails here rather than accepting an
		// order nobody can be paid for.
		PaymentGateway gateway = gateways.forMethod(toGatewayMethod(method));
		PaymentGateway.ShopContext context =
				shopContext(shop, order.getOrderNumber()).toGatewayContext();
		com.storefront.store.common.Money amount =
				com.storefront.store.common.Money.ofMinor(quote.totalMinor());

		// eSewa is a POST-only endpoint, so its signed form is produced separately from the shared
		// Initiation record. Every other gateway fills in the shared record and needs nothing extra.
		if (gateway instanceof com.storefront.store.payment.EsewaGateway esewa) {
			// The transaction id is derived from the order number, so a customer who retries the
			// payment reuses it. That is deliberate: eSewa treats a repeated uuid as the same
			// transaction rather than taking the money twice.
			transaction.attachGatewayRef(esewa.transactionUuid(order.getOrderNumber()));
		} else {
			PaymentGateway.Initiation initiation = gateway.initiate(
					amount, order.getOrderNumber(), context);
			if (initiation.reference() != null) {
				transaction.attachGatewayRef(initiation.reference());
			}
		}

		return payments.saveAndFlush(transaction);
	}

	/**
	 * The shop's own payment settings, plus where eSewa should send the customer back to.
	 *
	 * <p>Bank details are read from {@code shop_payment_method} now that Phase 4 configures them.
	 * A shop that has not configured bank transfer gets nulls here, and {@code BankTransferGateway}
	 * refuses at checkout with a message telling the customer to choose another method — which is
	 * the correct behaviour, and better than offering a transfer to an account that does not exist.
	 */
	private PaymentGatewayRequest shopContext(Shop shop, String orderNumber) {
		String bankName = null;
		String accountName = null;
		String accountNumber = null;
		String instructions = null;

		try {
			var bank = paymentMethods.get(PaymentGateway.ShopOrderPaymentMethod.BANK_TRANSFER);
			bankName = bank.getBankName();
			accountName = bank.getBankAccountName();
			// Decrypted only here, for the one call that needs to display it to the customer.
			accountNumber = bank.getBankAccountNumberEnc() == null
					? null
					: cipher.decrypt(bank.getBankAccountNumberEnc());
			instructions = bank.getInstructions();
		} catch (RuntimeException ex) {
			// No bank row, or a credential that will not decrypt. Nulls are the correct value:
			// BankTransferGateway then refuses rather than showing a blank account.
			log.debug("Shop {} has no usable bank transfer configuration: {}", shop.getId(),
					ex.getMessage());
		}

		return new PaymentGatewayRequest(shop.getId(), bankName, accountName, accountNumber,
				instructions, storefront.baseUrl(), orderNumber);
	}

	private final PaymentMethodService paymentMethods;
	private final SecretCipher cipher;
	private final StorefrontProperties storefront;

	private PaymentGateway.ShopOrderPaymentMethod toGatewayMethod(ShopOrder.PaymentMethod method) {
		return PaymentGateway.ShopOrderPaymentMethod.valueOf(method.name());
	}

	private PaymentTransaction.Gateway toGateway(ShopOrder.PaymentMethod method) {
		return switch (method) {
			case COD -> PaymentTransaction.Gateway.COD;
			case BANK_TRANSFER -> PaymentTransaction.Gateway.BANK;
			// Unreachable in Phase 1: EsewaGateway refuses at initiate, so no eSewa order exists
			// to record. Named explicitly rather than defaulted so Phase 5 has to look here.
			case ESAWA -> PaymentTransaction.Gateway.ESEWA;
		};
	}

	private void validateContact(PlaceRequest request) {
		if (request.customerId() == null
				&& (request.email() == null || request.email().isBlank())) {
			// The database enforces the same thing with order_has_contact; failing here produces
			// a message about the checkout rather than a constraint violation.
			throw new IllegalArgumentException("Enter an email address so we can send your order updates");
		}
		if (request.email() != null && !request.email().contains("@")) {
			throw new IllegalArgumentException("That email address does not look right");
		}
		if (request.shippingAddressJson() == null || request.shippingAddressJson().isBlank()) {
			throw new IllegalArgumentException("A delivery address is required");
		}
	}

	// ------------------------------------------------------------------ types

	/** What the customer submitted. Contains no prices — the server supplies every amount. */
	public record PlaceRequest(
			String shopSlug,
			List<QuoteService.RequestedLine> lines,
			String email,
			String phone,
			UUID customerId,
			String city,
			String discountCode,
			String shippingAddressJson,
			String billingAddressJson,
			String customerNote,
			ShopOrder.PaymentMethod paymentMethod) {
	}

	/** The result: an order, the quote that priced it, and the payment attempt. */
	public record Placement(ShopOrder order, Quote quote, PaymentTransaction payment) {
	}

	/**
	 * The shop's own payment settings.
	 *
	 * <p>A local type rather than the gateway's, so {@link PaymentGateway} does not depend on the
	 * order package and the shop package stays out of the payment package.
	 */
	public record PaymentGatewayRequest(
			UUID shopId,
			String bankName,
			String bankAccountName,
			String accountNumber,
			String instructions,
			/** The store's base url, e.g. {@code http://localhost:3000}. Server-configured. */
			String storefrontBaseUrl,
			/** The order the payment is for, used to build eSewa's return urls. */
			String orderNumber) {

		/** Adapt to what a gateway expects, so the gateway interface stays order-agnostic. */
		com.storefront.store.payment.PaymentGateway.ShopContext toGatewayContext() {
			// eSewa's return urls are the shop's storefront, never anything the customer's browser
			// supplies. A redirect target chosen by whoever clicks "pay" is an open redirect, and an
			// open redirect on a payment page is a phishing page.
			String checkout = storefrontBaseUrl + "/checkout?order="
					+ java.net.URLEncoder.encode(orderNumber, java.nio.charset.StandardCharsets.UTF_8);
			return new com.storefront.store.payment.PaymentGateway.ShopContext(
					shopId, bankName, bankAccountName, accountNumber, instructions,
					checkout, checkout + "&payment=failed");
		}
	}

	/** The shop does not exist, or is not verified. 404 for both. */
	/**
	 * The owner has switched off accepting orders.
	 *
	 * <p>Distinct from {@link ShopUnavailableException} because it is not a 404-shaped problem: the
	 * shop exists and is browsable, it just is not selling right now. The storefront shows "this
	 * shop is closed", which is a different message from "this shop does not exist" and one the
	 * customer can act on by coming back later.
	 */
	public static class ShopClosedException extends RuntimeException {
		public ShopClosedException(String slug) {
			super("This shop is not accepting orders at the moment. Please try again later.");
		}
	}

	public static class ShopUnavailableException extends RuntimeException {
		public ShopUnavailableException(String slug) {
			super("No shop available at '" + slug + "'");
		}
	}

	/** Something in the basket is no longer purchasable. 409: the basket changed under the customer. */
	public static class OutOfStockException extends RuntimeException {
		public OutOfStockException(String productName) {
			super(productName + " is out of stock");
		}
	}
}
