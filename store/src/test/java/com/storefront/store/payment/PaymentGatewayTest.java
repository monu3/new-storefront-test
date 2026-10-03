package com.storefront.store.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.storefront.store.common.Money;

/**
 * The payment gateways.
 *
 * <p>A pure unit test: no Spring, no database, no network. The gateway contract is small and its
 * interesting cases are all about what each method is allowed to claim.
 *
 * <p>The property that matters most: <b>a stubbed gateway refuses rather than pretending</b>. An
 * eSewa implementation that returned a plausible-looking redirect URL would send a customer who
 * has already entered their details to nowhere — strictly worse than being told the method is
 * unavailable.
 */
class PaymentGatewayTest {

	private final CodGateway cod = new CodGateway();
	private final BankTransferGateway bank = new BankTransferGateway();
	private final EsewaGateway esewa = new EsewaGateway();
	private final PaymentGatewayRegistry registry =
			new PaymentGatewayRegistry(List.of(cod, bank, esewa));

	private static final Money HUNDRED = Money.ofMinor(10_000L);

	private static PaymentGateway.ShopContext bareShop() {
		return new PaymentGateway.ShopContext(UUID.randomUUID(), null, null, null, null);
	}

	private static PaymentGateway.ShopContext configuredBank() {
		return new PaymentGateway.ShopContext(UUID.randomUUID(),
				"Nabil Bank", "Ravi KC", "0102030405", "Send the receipt on WhatsApp.");
	}

	@Nested
	@DisplayName("cash on delivery")
	class Cod {

		@Test
		@DisplayName("takes nothing at checkout, because there is nothing to take")
		void initiatesNothing() {
			PaymentGateway.Initiation initiation = cod.initiate(HUNDRED, "ORD-0042", bareShop());

			assertThat(initiation.redirectUrl()).isNull();
			assertThat(initiation.instructions())
					.as("the customer still needs to know they will pay the courier")
					.contains("cash");
			assertThat(initiation.requiresManualVerification())
					.as("nothing to verify remotely")
					.isFalse();
		}

		@Test
		@DisplayName("cannot verify, and says so rather than returning false")
		void cannotVerify() {
			// Saying "not paid" would be indistinguishable from a failed payment. Saying why it
			// cannot know is the honest answer, and it is why the order is marked paid at dispatch.
			PaymentGateway.Verification verification = cod.verify("ORD-0042", bareShop());

			assertThat(verification.paid()).isFalse();
			assertThat(verification.message()).contains("dispatched");
			assertThat(verification.verifiedAt())
					.as("an unverified payment must carry no timestamp, or a report claims it was checked")
					.isNull();
		}

		@Test
		@DisplayName("the reference is the order number, not an invented gateway id")
		void referenceIsTheOrderNumber() {
			// Inventing a reference would suggest to a shop owner that a payment had been
			// registered somewhere.
			assertThat(cod.initiate(HUNDRED, "ORD-0042", bareShop()).reference())
					.isEqualTo("ORD-0042");
		}
	}

	@Nested
	@DisplayName("bank transfer")
	class Bank {

		@Test
		@DisplayName("shows the account details the customer has to transfer to")
		void showsDetails() {
			PaymentGateway.Initiation initiation =
					bank.initiate(HUNDRED, "ORD-0042", configuredBank());

			assertThat(initiation.instructions())
					.contains("Nabil Bank")
					.contains("Ravi KC")
					.contains("0102030405")
					.contains("ORD-0042");
			assertThat(initiation.requiresManualVerification())
					.as("a human in the shop's dashboard confirms the money arrived")
					.isTrue();
			assertThat(initiation.reference()).isEqualTo("BANK-ORD-0042");
		}

		@Test
		@DisplayName("refuses at checkout when the shop has not configured its details")
		void refusesWithoutConfiguration() {
			// Failing here is far better than accepting an order the customer cannot pay for and
			// the shop cannot be paid on.
			assertThatThrownBy(() -> bank.initiate(HUNDRED, "ORD-0042", bareShop()))
					.isInstanceOf(BankTransferGateway.PaymentConfigurationException.class)
					.hasMessageContaining("not set up bank transfer");
		}

		@Test
		@DisplayName("cannot confirm a transfer remotely, and reports no verification time")
		void cannotVerifyRemotely() {
			// "Automatic" bank verification would be a guess. OrderService refuses to deliver an
			// unverified transfer so the guess never becomes a consequence.
			PaymentGateway.Verification verification = bank.verify("BANK-ORD-0042", configuredBank());

			assertThat(verification.paid()).isFalse();
			assertThat(verification.message()).contains("confirm");
			assertThat(verification.verifiedAt()).isNull();
		}
	}

	@Nested
	@DisplayName("eSewa, stubbed")
	class Stubbed {

		@Test
		@DisplayName("refuses to initiate rather than returning a URL that goes nowhere")
		void refusesToInitiate() {
			// A redirect to nowhere produces a customer who has entered their details and then
			// seen nothing happen. That is worse than an honest refusal.
			assertThatThrownBy(() -> esewa.initiate(HUNDRED, "ORD-0042", bareShop()))
					.isInstanceOf(UnsupportedOperationException.class)
					.hasMessageContaining("not available yet");
		}

		@Test
		@DisplayName("reports itself unavailable, so checkout can hide the option")
		void reportsUnavailable() {
			assertThat(esewa.isAvailable()).isFalse();
		}

		@Test
		@DisplayName("is still a registered gateway, so nothing in checkout has to be rewritten later")
		void isRegistered() {
			// The point of having it at all: when eSewa goes live in Phase 5, nothing in
			// CheckoutService changes. It only has to stop refusing.
			assertThat(registry.forMethod(PaymentGateway.ShopOrderPaymentMethod.ESAWA))
					.isSameAs(esewa);
		}

		@Test
		@DisplayName("refuses to verify")
		void refusesToVerify() {
			assertThatThrownBy(() -> esewa.verify("ESW-1", bareShop()))
					.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	@DisplayName("the registry")
	class Registry_ {

		@Test
		@DisplayName("resolves each method to its gateway")
		void resolves() {
			assertThat(registry.forMethod(PaymentGateway.ShopOrderPaymentMethod.COD)).isSameAs(cod);
			assertThat(registry.forMethod(PaymentGateway.ShopOrderPaymentMethod.BANK_TRANSFER))
					.isSameAs(bank);
		}

		@Test
		@DisplayName("excludes eSewa from the customer's choices until it is live")
		void availableExcludesStub() {
			assertThat(registry.available())
					.as("offering a method that then refuses wastes the customer's time")
					.containsExactlyInAnyOrder(cod, bank);
		}

		@Test
		@DisplayName("fails loudly for a method with no gateway")
		void failsForMissingGateway() {
			// Silently treating an unknown method as cash would take an order for money that will
			// never arrive.
			PaymentGatewayRegistry incomplete = new PaymentGatewayRegistry(List.of(cod));

			assertThatThrownBy(() ->
					incomplete.forMethod(PaymentGateway.ShopOrderPaymentMethod.BANK_TRANSFER))
					.isInstanceOf(PaymentGatewayRegistry.NoSuchGatewayException.class);
		}

		@Test
		@DisplayName("refuses to start with two gateways claiming one method")
		void refusesDuplicateRegistration() {
			// Otherwise the winner depends on bean order, which is not a decision anyone made.
			assertThatThrownBy(() -> new PaymentGatewayRegistry(List.of(cod, new CodGateway())))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Two payment gateways");
		}

		@Test
		@DisplayName("every gateway names its method and a display name")
		void gatewaysAreSelfDescribing() {
			for (PaymentGateway gateway : List.of(cod, bank, esewa)) {
				assertThat(gateway.method()).isNotNull();
				assertThat(gateway.displayName()).isNotBlank();
			}
		}
	}
}
