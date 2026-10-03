package com.storefront.store.payment;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.storefront.store.common.Money;

/**
 * A manual bank transfer.
 *
 * <p>Live, and deliberately manual. There is no bank webhook to trust and no instant settlement
 * in Nepal, so the flow is: the customer is shown the account details and a reference, they
 * transfer, and a human in the shop's dashboard marks it verified. That human step is the honest
 * design — an "automatic" bank verification would be a guess.
 *
 * <p><b>{@link #verify} cannot confirm anything</b>, and returns the reason rather than a false
 * negative. The shop owner marks the order paid; {@code OrderService.markDelivered} refuses to
 * deliver an unverified bank-transfer order, so money that never arrived cannot quietly become
 * an order marked delivered.
 */
@Component
public class BankTransferGateway implements PaymentGateway {

	@Override
	public ShopOrderPaymentMethod method() {
		return ShopOrderPaymentMethod.BANK_TRANSFER;
	}

	@Override
	public String displayName() {
		return "Bank transfer";
	}

	@Override
	public Initiation initiate(Money amount, String orderRef, ShopContext shop) {
		if (shop.bankName() == null || shop.bankAccountName() == null || shop.accountNumber() == null) {
			// Failing here, at checkout, is far better than accepting an order the shop cannot
			// be paid for and the customer cannot act on.
			throw new PaymentConfigurationException(
					"This shop has not set up bank transfer details yet. Please choose another payment method.");
		}

		String instructions = """
				Transfer %s to:
				  Bank: %s
				  Account name: %s
				  Account number: %s

				Use reference %s so we can match your payment.
				%s"""
				.formatted(
						amount.toDisplay(),
						shop.bankName(),
						shop.bankAccountName(),
						shop.accountNumber(),
						orderRef,
						shop.instructions() == null ? "" : "\n" + shop.instructions());

		// The reference is what the shop matches the incoming payment against. It is the order
		// number because that is what the customer will write on the transfer form.
		return new Initiation(null, "BANK-" + orderRef, instructions, true);
	}

	@Override
	public Verification verify(String gatewayRef, ShopContext shop) {
		return new Verification(false, gatewayRef,
				"Waiting for the shop to confirm the transfer.",
				// Not yet verified, so deliberately no timestamp: a verifiedAt on an unverified
				// payment would let a report claim it was checked.
				null);
	}

	/** The shop enabled bank transfer without providing the details it needs. */
	public static class PaymentConfigurationException extends RuntimeException {
		public PaymentConfigurationException(String message) {
			super(message);
		}
	}
}
