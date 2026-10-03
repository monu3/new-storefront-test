package com.storefront.store.payment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Looks up the gateway for a payment method.
 *
 * <p>A registry rather than a {@code switch} in checkout, so that adding a gateway is a matter of
 * adding a bean. A switch would need a branch per method, and the branch that is forgotten when
 * a method is added is the one that falls through to "treat it as cash" — charging a customer who
 * chose a card.
 *
 * <p>Fails loudly for a method with no gateway. Silently treating an unknown method as COD would
 * take an order for money that will never arrive.
 */
@Component
public class PaymentGatewayRegistry {

	private final Map<PaymentGateway.ShopOrderPaymentMethod, PaymentGateway> byMethod =
			new EnumMap<>(PaymentGateway.ShopOrderPaymentMethod.class);

	public PaymentGatewayRegistry(List<PaymentGateway> gateways) {
		for (PaymentGateway gateway : gateways) {
			PaymentGateway clash = byMethod.put(gateway.method(), gateway);
			if (clash != null) {
				// Two beans for one method is a configuration error that would otherwise show up
				// as a non-deterministic choice depending on bean order.
				throw new IllegalStateException("Two payment gateways claim "
						+ gateway.method() + ": " + clash.getClass().getSimpleName()
						+ " and " + gateway.getClass().getSimpleName());
			}
		}
		logRegistered();
	}

	public PaymentGateway forMethod(PaymentGateway.ShopOrderPaymentMethod method) {
		PaymentGateway gateway = byMethod.get(method);
		if (gateway == null) {
			throw new NoSuchGatewayException(method);
		}
		return gateway;
	}

	/** The methods a customer may actually choose. eSewa is excluded until it is live. */
	public java.util.List<PaymentGateway> available() {
		return byMethod.values().stream()
				.filter(g -> !(g instanceof EsewaGateway esa) || esa.isAvailable())
				.toList();
	}

	private void logRegistered() {
		// Registration is silent otherwise, and a gateway missing from the registry is the kind
		// of thing nobody notices until a customer cannot pay.
		byMethod.forEach((method, gateway) ->
				java.util.logging.Logger.getLogger(PaymentGatewayRegistry.class.getName())
						.fine(() -> method + " -> " + gateway.getClass().getSimpleName()));
	}

	/** No gateway for that method. A configuration bug, not a user error. */
	public static class NoSuchGatewayException extends RuntimeException {
		public NoSuchGatewayException(PaymentGateway.ShopOrderPaymentMethod method) {
			super("No payment gateway registered for " + method);
		}
	}
}
