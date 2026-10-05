package com.storefront.store.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

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
	/** A gateway with no configuration: enough to test refusal and registration. */
	private final EsewaGateway esewa = esewaGateway(offline(""));
	private final PaymentGatewayRegistry registry =
			new PaymentGatewayRegistry(List.of(cod, bank, esewa));

	private static final Money HUNDRED = Money.ofMinor(10_000L);

	/**
	 * A gateway with no credential, for the properties that only need "is it wired up".
	 *
	 * <p>The two collaborators are null because neither the disabled path nor the signature path
	 * touches them: {@code isAvailable} short-circuits on the flag, and signing needs only the
	 * configured fallback secret.
	 */
	private static EsewaGateway esewaGateway(EsewaProperties properties) {
		return new EsewaGateway(properties, null, null, new ObjectMapper());
	}

	/** eSewa's sandbox identity, pointed at an unroutable host so nothing leaves the machine. */
	private static EsewaProperties offline(String secret) {
		return new EsewaProperties(false, "EPAYTEST", secret,
				"https://example.invalid", "https://example.invalid", 20);
	}

	private static PaymentGateway.ShopContext bareShop() {
		return new PaymentGateway.ShopContext(UUID.randomUUID(), null, null, null, null,
				"http://localhost:3000/checkout", "http://localhost:3000/checkout?failed=1");
	}

	private static PaymentGateway.ShopContext configuredBank() {
		return new PaymentGateway.ShopContext(UUID.randomUUID(),
				"Nabil Bank", "Ravi KC", "0102030405", "Send the receipt on WhatsApp.",
				"http://localhost:3000/checkout", "http://localhost:3000/checkout?failed=1");
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
	@DisplayName("eSewa")
	class EsewaGatewayBehaviour {

		@Test
		@DisplayName("reports itself unavailable when no credential is configured")
		void reportsUnavailableWithoutConfiguration() {
			// Constructed with no merchant secret and a shop with no configured row, so the honest
			// answer is "unavailable" and CheckoutService hides the option. The property under test
			// is the original stub's: refuse rather than pretend.
		EsewaGateway unconfigured = esewaGateway(offline(""));

			assertThat(unconfigured.isAvailable()).isFalse();
		}

		@Test
		@DisplayName("is a registered gateway, so nothing in checkout had to be rewritten")
		void isRegistered() {
			// The point of Phase 1 having it at all: when eSewa went live in Phase 5, nothing in
			// CheckoutService changed. The gateway only had to stop refusing.
			assertThat(registry.forMethod(PaymentGateway.ShopOrderPaymentMethod.ESAWA))
					.isSameAs(esewa);
		}

		@Test
		@DisplayName("signs a form rather than returning a bare link")
		void buildsASignedForm() {
			// eSewa's checkout is POST-only, so a redirect url cannot start a payment. This is the
			// real signature path; the message format itself is pinned in EsewaSignatureTest.
			EsewaGateway gateway = esewaGateway(new EsewaProperties(true, "EPAYTEST",
					"8gBm/:&EnhH.1/q",
					"https://rc-epay.esewa.com.np/api/epay/main/v2/form",
					"https://rc.esewa.com.np/api/epay/transaction/status/", 20));

			// No shop configuration and no shop row: the fallback credential is used, which is
			// what makes the sandbox work with no setup.
			java.util.Map<String, String> fields = gateway.redirectFor(HUNDRED, "ORD-0042",
					configuredBank()).fields();

			assertThat(fields).containsEntry("product_code", "EPAYTEST");
			assertThat(fields.get("signature")).isNotBlank();
			assertThat(gateway.redirectFor(HUNDRED, "ORD-0042", configuredBank()).autoPostHtml())
					.contains("rc-epay.esewa.com.np");
		}

		@Test
		@DisplayName("reports a disabled eSewa as unavailable")
		void disabledIsUnavailable() {
			EsewaGateway off = esewaGateway(offline("secret"));

			assertThat(off.isAvailable()).isFalse();
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
