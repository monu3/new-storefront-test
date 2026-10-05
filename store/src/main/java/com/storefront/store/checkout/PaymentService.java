package com.storefront.store.checkout;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.order.ShopOrder;
import com.storefront.store.payment.PaymentGateway;
import com.storefront.store.payment.PaymentGatewayRegistry;
import com.storefront.store.payment.PaymentTransaction;
import com.storefront.store.payment.PaymentTransactionRepository;
import com.storefront.store.settings.ShopSettingService;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;

/**
 * The authoritative answer to "has this order been paid?", and the only place that may say yes.
 *
 * <p><b>Every "paid" answer comes from a gateway, never from the browser.</b> A customer can
 * navigate to any URL they like, including a success page for an order they never paid for, so the
 * return URL is a hint that lets the page render quickly and never proof.
 *
 * <p><b>A payment that is merely pending is not a failure.</b> eSewa settles asynchronously, and a
 * customer who reaches the return page a second before it does should be told "we are checking",
 * not "your payment failed". Marking a paid order unpaid because their gateway was briefly behind
 * is the more expensive mistake, so pending is its own answer.
 *
 * <p><b>Read-only, deliberately.</b> This class decides what to tell a customer; it does not move an
 * order's state. Marking an order paid is {@code OrderService}'s job, driven by an event, so a
 * status page can never be the thing that advances an order.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final PaymentGatewayRegistry gateways;
	private final PaymentTransactionRepository payments;
	private final ShopRepository shops;
	private final ShopSettingService settings;

	/** Reads the live state of an order's payment. */
	@Transactional(readOnly = true)
	public State stateOf(ShopOrder order) {
		if (order.getPaymentMethod() == ShopOrder.PaymentMethod.COD) {
			// The money changes hands at dispatch, so "paid" is a property of the order's own
			// status rather than of a gateway. Asking eSewa would be nonsense.
			boolean paid = order.getPaymentStatus() == ShopOrder.PaymentStatus.PAID;
			return new State(paid ? "PAID" : "PENDING",
					paid ? "Paid in cash on delivery."
							: "Pay the courier when your order arrives.",
					paid);
		}

		Optional<PaymentTransaction> attempt = latestAttempt(order);
		if (attempt.isEmpty() || attempt.get().getGatewayRef() == null) {
			return new State("PENDING", "This payment has not been started yet.", false);
		}

		String gatewayRef = attempt.get().getGatewayRef();
		Shop shop = shops.findById(order.getShopId()).orElse(null);
		if (shop == null) {
			return new State("UNKNOWN", "That shop no longer exists.", false);
		}

		try {
			PaymentGateway gateway = gateways.forMethod(PaymentGateway.ShopOrderPaymentMethod
					.valueOf(order.getPaymentMethod().name()));
			var verification = gateway.verify(gatewayRef, contextFor(shop, order));

			return describe(verification.message(), verification.paid());

		} catch (RuntimeException ex) {
			// A gateway that cannot be reached is not a payment that failed. Saying so would tell
			// a customer who has just paid that they have not.
			return new State("PENDING",
					"We could not reach the payment provider yet. Your order is safe, and nothing is "
							+ "charged unless they confirm it.",
					false);
		}
	}

	/** Maps a gateway's status word onto what the customer is told. */
	private State describe(String gatewayStatus, boolean gatewaySaysPaid) {
		// Only COMPLETE is proof. Every other state is either still settling or not a payment, and
		// treating "not found" as paid would ship goods for free.
		if ("COMPLETE".equals(gatewayStatus)) {
			return new State("PAID", "Payment received. Thank you.", true);
		}
		return switch (gatewayStatus == null ? "" : gatewayStatus) {
			case "PENDING" -> new State("PENDING",
					"We are confirming your payment with eSewa. This usually takes a few seconds.",
					false);
			case "AMBIGUOUS" -> new State("PENDING",
					"eSewa has not confirmed this payment yet. If you were charged it will appear "
							+ "on your statement shortly.",
					false);
			case "NOT_FOUND" -> new State("FAILED",
					"eSewa has no record of this payment, so you have not been charged.", false);
			case "CANCELED" -> new State("FAILED",
					"The payment was cancelled at eSewa, so you have not been charged.", false);
			case "FULL_REFUND", "PARTIAL_REFUND" -> new State("REFUNDED",
					"This payment has been refunded.", false);
			default -> new State("PENDING",
					"We could not confirm this payment yet. Nothing is charged unless eSewa confirms "
							+ "it.",
					false);
		};
	}

	/**
	 * The most recent payment attempt for an order.
	 *
	 * <p>Ordered by creation rather than assumed to be the only one: a customer who retries after a
	 * failed attempt has two, and checking the first would report a failure forever.
	 */
	private Optional<PaymentTransaction> latestAttempt(ShopOrder order) {
		return payments.findFirstByOrderIdOrderByCreatedAtDesc(order.getId());
	}

	/**
	 * The shop context for a gateway call.
	 *
	 * <p>Bank details and return urls come from the shop's own configuration. eSewa's return urls
	 * are built from the server's storefront setting, never from the request — a redirect target the
	 * browser can influence is an open redirect, and an open redirect on a payment page is a
	 * phishing page.
	 */
	private PaymentGateway.ShopContext contextFor(Shop shop, ShopOrder order) {
		String checkout = storefront.baseUrl() + "/checkout?order=" + order.getOrderNumber();
		return new PaymentGateway.ShopContext(shop.getId(), null, null, null, null,
				checkout, checkout + "&payment=failed");
	}

	private final StorefrontProperties storefront;

	/**
	 * A payment's live state.
	 *
	 * @param paid whether money has demonstrably arrived; the only field that may mark an order paid
	 */
	public record State(String status, String message, boolean paid) {
	}
}
