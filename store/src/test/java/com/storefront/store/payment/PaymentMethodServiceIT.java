package com.storefront.store.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.payment.PaymentGateway.ShopOrderPaymentMethod;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Payment method configuration.
 *
 * <p>One rule carries the weight of this class: <b>a method is only offered when it can actually
 * take the payment.</b> An enabled eSewa with no merchant id would show on the storefront, take the
 * customer's details, and then be refused — so {@link ShopPaymentMethod#canBeEnabled()} gates both
 * enabling and offering.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class PaymentMethodServiceIT {

	@Autowired
	private PaymentMethodService service;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AppUserRepository users;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createShop("Payments " + UUID.randomUUID(), ShopType.GROCERY);
		signIn(shop);
	}

	private void signIn(Shop asShop) {
		String subject = users.findById(asShop.getOwnerUserId())
				.map(AppUser::getKeycloakSub)
				.orElseThrow(() -> new IllegalStateException("owner row missing"));
		Jwt jwt = new Jwt("t-" + UUID.randomUUID(), Instant.now().minusSeconds(60),
				Instant.now().plusSeconds(600), java.util.Map.of("alg", "none"),
				java.util.Map.of("sub", subject,
						"iss", "https://auth-master.yenyasoft.com/realms/ops",
						"email", "owner@example.test"));
		TestSecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
						jwt, null, java.util.List.of()));
	}

	private PaymentMethodService.Configuration config(boolean enabled) {
		return new PaymentMethodService.Configuration(null, null, null, null, null,
				0L, 0L, null, null, null, 0, enabled);
	}

	// ------------------------------------------------------------------ defaults

	@Test
	@DisplayName("a shop sees all three methods on its first visit")
	void defaultRows() {
		var methods = service.list();

		// The settings screen must never show an empty page. Three rows exist from the start so an
		// owner can turn cash on delivery without first having saved an eSewa row.
		assertThat(methods).hasSize(3);
		assertThat(methods).extracting(ShopPaymentMethod::getMethod)
				.containsExactlyInAnyOrder(ShopOrderPaymentMethod.COD, ShopOrderPaymentMethod.ESAWA,
						ShopOrderPaymentMethod.BANK_TRANSFER);
		assertThat(methods).allMatch(m -> !m.isEnabled());
	}

	// ------------------------------------------------------------------ enabling

	@Nested
	@DisplayName("enabling")
	class Enabling {

		@Test
		@DisplayName("cash on delivery needs nothing")
		void cod() {
			var cod = service.configure(ShopOrderPaymentMethod.COD, config(true));

			assertThat(cod.isEnabled()).isTrue();
			assertThat(cod.canBeEnabled()).isTrue();
		}

		@Test
		@DisplayName("eSewa is refused without a merchant id")
		void esewaWithoutMerchant() {
			assertThatThrownBy(() -> service.configure(ShopOrderPaymentMethod.ESAWA, config(true)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("merchant ID");
		}

		@Test
		@DisplayName("eSewa is refused with a merchant id but no secret")
		void esewaWithoutSecret() {
			PaymentMethodService.Configuration withId =
					new PaymentMethodService.Configuration("EPAY1234", null, null, null, null,
							0L, 0L, null, null, null, 0, true);

			assertThatThrownBy(() -> service.configure(ShopOrderPaymentMethod.ESAWA, withId))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("merchant ID and a merchant secret");
		}

		@Test
		@DisplayName("eSewa is accepted once both are set")
		void esewaComplete() {
			PaymentMethodService.Configuration complete =
					new PaymentMethodService.Configuration("EPAY1234", "the-secret", null, null, null,
							0L, 0L, null, null, null, 0, true);

			var esewa = service.configure(ShopOrderPaymentMethod.ESAWA, complete);

			assertThat(esewa.isEnabled()).isTrue();
			assertThat(esewa.canBeEnabled()).isTrue();
			assertThat(esewa.getEsewaMerchantId()).isEqualTo("EPAY1234");
		}

		@Test
		@DisplayName("a merchant id and enabling can be saved in one request")
		void oneRequest() {
			// The owner fills the form and ticks the toggle before the first save. Refusing that
			// would make them save twice for no reason.
			PaymentMethodService.Configuration both =
					new PaymentMethodService.Configuration("EPAY1234", "the-secret", null, null, null,
							0L, 0L, null, null, null, 0, true);

			assertThat(service.configure(ShopOrderPaymentMethod.ESAWA, both).isEnabled()).isTrue();
		}

		@Test
		@DisplayName("bank transfer needs an account name and number")
		void bankTransfer() {
			PaymentMethodService.Configuration half =
					new PaymentMethodService.Configuration(null, null, "Nabil Bank", "Ravi", null,
							0L, 0L, null, null, null, 0, true);

			assertThatThrownBy(() -> service.configure(ShopOrderPaymentMethod.BANK_TRANSFER, half))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("account name and an account number");
		}
	}

	// ------------------------------------------------------------------ secrets

	@Nested
	@DisplayName("secrets")
	class Secrets {

		@Test
		@DisplayName("saving without typing keeps the stored one")
		void blankKeepsStored() {
			// The console masks the field because it is never sent the value, so re-saving the
			// merchant id would otherwise delete a working credential.
			PaymentMethodService.Configuration first =
					new PaymentMethodService.Configuration("EPAY1", "secret-one", null, null, null,
							0L, 0L, null, null, null, 0, true);
			service.configure(ShopOrderPaymentMethod.ESAWA, first);

			PaymentMethodService.Configuration second =
					new PaymentMethodService.Configuration("EPAY2", "", null, null, null,
							0L, 0L, null, null, null, 0, true);
			var after = service.configure(ShopOrderPaymentMethod.ESAWA, second);

			assertThat(after.getEsewaMerchantId()).isEqualTo("EPAY2");
			assertThat(after.getEsewaSecretEnc()).isNotNull();
		}

		@Test
		@DisplayName("clearing is explicit and turns the method off")
		void clearSecret() {
			PaymentMethodService.Configuration complete =
					new PaymentMethodService.Configuration("EPAY1234", "the-secret", null, null, null,
							0L, 0L, null, null, null, 0, true);
			service.configure(ShopOrderPaymentMethod.ESAWA, complete);

			var cleared = service.clearSecret(ShopOrderPaymentMethod.ESAWA);

			assertThat(cleared.getEsewaSecretEnc()).isNull();
			// An eSewa row with no secret cannot take a payment, so it must stop being offered.
			assertThat(cleared.isEnabled()).isFalse();
		}

		@Test
		@DisplayName("is encrypted at rest, not stored as text")
		void encrypted() {
			PaymentMethodService.Configuration complete =
					new PaymentMethodService.Configuration("EPAY1234", "super-secret-value", null, null,
							null, 0L, 0L, null, null, null, 0, true);

			var esewa = service.configure(ShopOrderPaymentMethod.ESAWA, complete);

			assertThat(esewa.getEsewaSecretEnc()).isNotNull();
			assertThat(new String(esewa.getEsewaSecretEnc(), java.nio.charset.StandardCharsets.ISO_8859_1))
					.doesNotContain("super-secret-value");
		}
	}

	// ------------------------------------------------------------------ limits

	@Nested
	@DisplayName("offering")
	class Offering {

		@Test
		@DisplayName("respects the minimum order")
		void minimum() {
			PaymentMethodService.Configuration withMinimum =
					new PaymentMethodService.Configuration(null, null, null, null, null,
							0L, 50_000L, null, null, null, 0, true);
			service.configure(ShopOrderPaymentMethod.COD, withMinimum);

			assertThat(service.offeredFor(49_999L))
					.extracting(ShopPaymentMethod::getMethod)
					.doesNotContain(ShopOrderPaymentMethod.COD);
			assertThat(service.offeredFor(50_000L))
					.extracting(ShopPaymentMethod::getMethod)
					.contains(ShopOrderPaymentMethod.COD);
		}

		@Test
		@DisplayName("never offers a method that cannot be enabled")
		void halfConfiguredIsNotOffered() {
			// Belt and braces: the service refuses to enable one, and the offering query filters
			// again. Both, because a row written by a migration or a future code path should still
			// not reach a customer.
			PaymentMethodService.Configuration half =
					new PaymentMethodService.Configuration("EPAY1", null, null, null, null,
							0L, 0L, null, null, null, 0, false);
			service.configure(ShopOrderPaymentMethod.ESAWA, half);

			assertThat(service.offeredFor(1000L))
					.extracting(ShopPaymentMethod::getMethod)
					.doesNotContain(ShopOrderPaymentMethod.ESAWA);
		}
	}

	@Test
	@DisplayName("a window where min is above max is refused")
	void invertedLimits() {
		PaymentMethodService.Configuration inverted =
				new PaymentMethodService.Configuration(null, null, null, null, null,
						0L, 10_000L, 5_000L, null, null, 0, true);

		// Such a window offers the method for no order at all, which is almost certainly a typo.
		assertThatThrownBy(() -> service.configure(ShopOrderPaymentMethod.COD, inverted))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no order qualifies");
	}

	@Test
	@DisplayName("another shop's methods are not visible")
	void crossTenant() {
		Shop other = fixtures.createShop("Other " + UUID.randomUUID(), ShopType.GROCERY);
		signIn(other);
		service.configure(ShopOrderPaymentMethod.COD, config(true));

		signIn(shop);
		assertThat(service.offeredFor(1000L)).isEmpty();
	}
}