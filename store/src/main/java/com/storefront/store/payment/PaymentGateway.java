package com.storefront.store.payment;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.Money;

/**
 * A way for a customer to pay.
 *
 * <p>The interface exists so that adding eSewa later is an implementation rather than a change
 * to checkout. {@link CodGateway} and {@link BankTransferGateway} are live now;
 * {@link EsewaGateway} is a stub that refuses, so nothing in the platform pretends to take money
 * it cannot take.
 *
 * <p>Two methods, not one, because a payment has two distinct questions:
 * <ul>
 *   <li>{@link #initiate} — "what must the customer do, and what reference identifies this
 *       attempt?" For COD that is "nothing, we will collect it". For eSewa it is a redirect URL.
 *       For a bank transfer it is account details.</li>
 *   <li>{@link #verify} — "did the money actually arrive, or is someone claiming it did?" COD
 *       cannot answer this at checkout, so it defers; a bank transfer is confirmed later by a
 *       human; eSewa calls its verification API.</li>
 * </ul>
 *
 * <p>Collapsing these into one "pay" method is the mistake that produces a checkout which says
 * "paid" because the customer clicked a button.
 */
public interface PaymentGateway {

	/** The shop order method this gateway handles. */
	ShopOrderPaymentMethod method();

	/** Human name for the console: "Cash on delivery", "Bank transfer", "eSewa". */
	String displayName();

	/**
	 * Starts a payment attempt.
	 *
	 * @param amount  what the customer owes
	 * @param orderRef the order number, shown to the customer and included in the reference
	 * @return what the customer must do next
	 */
	Initiation initiate(Money amount, String orderRef, ShopContext shop);

	/**
	 * Checks whether money actually arrived.
	 *
	 * @return the verified state, never a guess
	 */
	Verification verify(String gatewayRef, ShopContext shop);

	/** Maps to the order's enum, so the database and the gateway agree on spelling. */
	enum ShopOrderPaymentMethod {
		ESAWA,
		COD,
		BANK_TRANSFER
	}

	/**
	 * What the shop's own settings contribute to a payment.
	 *
	 * <p>Carries the return urls as well as the bank details, because eSewa needs somewhere to send
	 * the customer back to. They are the shop's storefront checkout, never a value from the
	 * customer's browser — a redirect target chosen by whoever clicks "pay" is an open redirect,
	 * and an open redirect on a payment page is a phishing page.
	 */
	record ShopContext(
			UUID shopId,
			String bankName,
			String bankAccountName,
			String accountNumber,
			String instructions,
			/** Where eSewa sends the customer after a successful payment. */
			String successUrl,
			/** Where eSewa sends the customer after a failure or a cancellation. */
			String failureUrl) {

		/** The bank-transfer shape, for gateways that need no return url. */
		public static ShopContext bankTransfer(UUID shopId, String bankName,
				String bankAccountName, String accountNumber, String instructions) {
			return new ShopContext(shopId, bankName, bankAccountName, accountNumber,
					instructions, null, null);
		}
	}

	/**
	 * What the customer must do.
	 *
	 * @param redirectUrl  where to send them, null for COD or a bank transfer
	 * @param reference    the gateway's own identifier for this attempt
	 * @param instructions text to show: account details, or "pay the courier"
	 * @param requiresManualVerification true when a human must confirm the money arrived
	 */
	record Initiation(
			String redirectUrl,
			String reference,
			String instructions,
			boolean requiresManualVerification) {

		/**
		 * eSewa's variant: a POST-only endpoint, so a link will not start a payment.
		 *
		 * <p>{@link #fields()} is the signed form eSewa requires and {@link #autoPostHtml()} is the
		 * page that submits it. Both are eSewa-specific and deliberately not on the shared record —
		 * COD and bank transfer have no such thing, and putting them here would put an always-null
		 * pair of fields on every gateway.
		 */
		public record Redirect(
				String formUrl,
				java.util.Map<String, String> fields,
				String autoPostHtml) {
		}
	}

	/** The outcome of a verification attempt. */
	record Verification(
			boolean paid,
			String gatewayRef,
			String message,
			Instant verifiedAt) {

		public static Verification notYetPaid(String message) {
			return new Verification(false, null, message, null);
		}
	}
}
