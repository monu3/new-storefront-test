package com.storefront.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.inventory.InventoryService;
import com.storefront.store.order.OrderService;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.payment.PaymentTransaction;
import com.storefront.store.payment.PaymentTransactionRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Checkout: quoting, then placing.
 *
 * <p>The properties worth pinning down, because each is a place where a plausible implementation
 * is quietly wrong:
 *
 * <ul>
 *   <li>quoting creates nothing — no order, no number, no reservation;</li>
 *   <li>placing uses the quote's own numbers, so what was shown is what is charged;</li>
 *   <li>a guest with no email is refused before an order exists;</li>
 *   <li>a bank transfer at a shop with no bank details fails at checkout, not after.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class CheckoutServiceIT {

	@Autowired
	private CheckoutService checkout;

	@Autowired
	private OrderService orders;

	@Autowired
	private ProductService products;

	@Autowired
	private PaymentTransactionRepository payments;

	@Autowired
	private InventoryService inventory;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private com.storefront.store.order.ShopOrderRepository orderRepository;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private Product product;
	private ProductVariant variant;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Checkout Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
		product = fixtures.createProduct(shop, "Rice", com.storefront.store.catalog.ProductCategory.RICE,
				10_000L);
		variant = fixtures.defaultVariantOf(product);
		fixtures.stock(variant, 100);
		products.publish(shop, product);
	}

	private List<QuoteService.RequestedLine> lines(int quantity) {
		return List.of(new QuoteService.RequestedLine(variant.getId(), quantity));
	}

	private CheckoutService.PlaceRequest request(ShopOrder.PaymentMethod method) {
		return new CheckoutService.PlaceRequest(shop.getSlug(), lines(2),
				"buyer-" + UUID.randomUUID() + "@example.test", "+9779800000000", null,
				"Kathmandu", null,
				"{\"city\":\"Kathmandu\"}", null, null, method);
	}

	@Nested
	@DisplayName("when the owner has closed the shop")
	class ClosedShop {

		@Test
		@DisplayName("placing an order is refused")
		void refusesToPlace() {
			closeShop();

			// Not a 404-shaped "this shop does not exist": the shop is browsable and will sell
			// again. The message has to say "closed for now" or the customer concludes it is gone.
			assertThatThrownBy(() -> checkout.place(request(ShopOrder.PaymentMethod.COD)))
					.isInstanceOf(CheckoutService.ShopClosedException.class)
					.hasMessageContaining("not accepting orders");
		}

		@Test
		@DisplayName("no order, no number, no stock reserved")
		void nothingIsWritten() {
			closeShop();

			assertThatThrownBy(() -> checkout.place(request(ShopOrder.PaymentMethod.COD)))
					.isInstanceOf(CheckoutService.ShopClosedException.class);

			assertThat(orderRepository.findByShopId(shop.getId())).isEmpty();
			assertThat(payments.findAll()).isEmpty();
			// Stock must be untouched: a refused checkout that still held units would strand them.
			assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow().available())
					.isEqualTo(100);
		}

		@Test
		@DisplayName("reopening lets orders through again")
		void reopens() {
			closeShop();

			closeShop(false);
			var placement = checkout.place(request(ShopOrder.PaymentMethod.COD));

			assertThat(placement.order().getStatus()).isEqualTo(ShopOrder.Status.PENDING);
		}

		@Test
		@DisplayName("a shop with no settings row accepts orders")
		void defaultIsOpen() {
			// Every shop has no settings row until its owner opens the settings screen. If the
			// default were closed, every shop in the platform would be shut.
			assertThat(settings.checkout(shop.getId()).acceptOrders()).isTrue();

			assertThat(checkout.place(request(ShopOrder.PaymentMethod.COD)).order())
					.isNotNull();
		}
	}

	/** Flips the shop's checkout setting, which lives in its settings row. */
	private void closeShop() {
		closeShop(true);
	}

	private void closeShop(boolean closed) {
		settings.writeSection(shop.getId(),
				com.storefront.store.settings.SettingsSections.Checkout.class,
				new com.storefront.store.settings.SettingsSections.Checkout(
						!closed, 0L, false, true, true, null),
				"checkout");
	}

	@Autowired
	private com.storefront.store.settings.ShopSettingService settings;

	// ------------------------------------------------------------------ quoting

	@Nested
	@DisplayName("quoting")
	class Quoting {

		@Test
		@DisplayName("creates nothing at all")
		void createsNothing() {
			// A customer refreshing the cart must not consume an order number, reserve stock, or
			// exhaust a discount's usage count.
			checkout.quote(shop, lines(5), "Kathmandu", null, null);
			checkout.quote(shop, lines(5), "Kathmandu", null, null);
			checkout.quote(shop, lines(5), "Kathmandu", null, null);

			// Scoped to this shop rather than counted globally: the committed CheckoutRollbackIT
			// suite legitimately leaves and removes rows of its own, and a global count would make
			// this test depend on which other suites ran.
			assertThat(orderRepository.findByShopId(shop.getId()))
					.as("quoting is read-only")
					.isEmpty();
			assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow()
					.getQuantityReserved()).isZero();
		}

		@Test
		@DisplayName("an unverified shop reads as absent, not forbidden")
		void unverifiedShopIsAbsent() {
			// Otherwise a customer can enumerate hidden shops by trying to buy from them.
			Shop draft = fixtures.createShop("Hidden " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);

			CheckoutService.PlaceRequest forDraft = new CheckoutService.PlaceRequest(
					draft.getSlug(), lines(1), "a@b.test", null, null, null, null, "{}", null, null,
					ShopOrder.PaymentMethod.COD);

			assertThatThrownBy(() -> checkout.place(forDraft))
					.isInstanceOf(CheckoutService.ShopUnavailableException.class);
		}

		@Test
		@DisplayName("an unknown shop slug reads as absent")
		void unknownShopIsAbsent() {
			CheckoutService.PlaceRequest bogus = new CheckoutService.PlaceRequest(
					"no-such-shop-" + UUID.randomUUID(), lines(1), "a@b.test", null, null, null, null,
					"{}", null, null, ShopOrder.PaymentMethod.COD);

			assertThatThrownBy(() -> checkout.place(bogus))
					.isInstanceOf(CheckoutService.ShopUnavailableException.class);
		}
	}

	// ------------------------------------------------------------------ placing

	@Nested
	@DisplayName("placing an order")
	class Placing {

		@Test
		@DisplayName("creates a PENDING order priced exactly as quoted")
		void matchesTheQuote() {
			Quote quote = checkout.quote(shop, lines(2), "Kathmandu", null, null);

			CheckoutService.Placement placement = checkout.place(request(ShopOrder.PaymentMethod.COD));

			ShopOrder order = placement.order();
			assertThat(order.getStatus()).isEqualTo(ShopOrder.Status.PENDING);
			assertThat(order.getSubtotalMinor()).isEqualTo(quote.subtotalMinor());
			assertThat(order.getTaxMinor()).isEqualTo(quote.taxMinor());
			assertThat(order.getShippingMinor()).isEqualTo(quote.shippingMinor());
			assertThat(order.getTotalMinor())
					.as("what the customer was shown is what they are charged")
					.isEqualTo(quote.totalMinor());
			assertThat(order.getShopId()).isEqualTo(shop.getId());
		}

		@Test
		@DisplayName("snapshots the shop type, so a later type change cannot rewrite history")
		void snapshotsShopType() {
			ShopOrder order = checkout.place(request(ShopOrder.PaymentMethod.COD)).order();

			assertThat(order.getShopTypeSnapshot()).isEqualTo(ShopType.GROCERY);
		}

		@Test
		@DisplayName("reserves stock")
		void reservesStock() {
			checkout.place(request(ShopOrder.PaymentMethod.COD));

			assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow()
					.getQuantityReserved()).isEqualTo(2);
		}

		@Test
		@DisplayName("records a payment attempt even for cash")
		void recordsCashAttempt() {
			// The trail should show COD was chosen, not merely that nothing happened.
			CheckoutService.Placement placement = checkout.place(request(ShopOrder.PaymentMethod.COD));

			assertThat(placement.payment()).isNotNull();
			assertThat(placement.payment().getMethod())
					.isEqualTo(com.storefront.store.payment.PaymentGateway.ShopOrderPaymentMethod.COD);
			assertThat(placement.payment().getGateway())
					.isEqualTo(PaymentTransaction.Gateway.COD);
			assertThat(placement.payment().getAmountMinor())
					.isEqualTo(placement.order().getTotalMinor());
		}

		@Test
		@DisplayName("gives consecutive order numbers")
		void numbersInSequence() {
			assertThat(checkout.place(request(ShopOrder.PaymentMethod.COD)).order().getOrderNumber())
					.isEqualTo("ORD-0001");
			assertThat(checkout.place(request(ShopOrder.PaymentMethod.COD)).order().getOrderNumber())
					.isEqualTo("ORD-0002");
		}

		@Test
		@DisplayName("a guest order needs an email")
		void guestNeedsEmail() {
			// The database would refuse it too, but here the message is about the checkout rather
			// than a constraint name.
			CheckoutService.PlaceRequest noEmail = new CheckoutService.PlaceRequest(
					shop.getSlug(), lines(1), "   ", null, null, null, null, "{}", null, null,
					ShopOrder.PaymentMethod.COD);

			assertThatThrownBy(() -> checkout.place(noEmail))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("email address");
		}

		@Test
		@DisplayName("refuses an email that does not look like one")
		void refusesBadEmail() {
			CheckoutService.PlaceRequest bad = new CheckoutService.PlaceRequest(
					shop.getSlug(), lines(1), "not-an-email", null, null, null, null, "{}", null, null,
					ShopOrder.PaymentMethod.COD);

			assertThatThrownBy(() -> checkout.place(bad))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("does not look right");
		}

		@Test
		@DisplayName("a signed-in customer's order is attached to their account")
		void attachesToCustomer() {
			// customer_id is a genuine foreign key to app_user, so a fabricated UUID fails the
			// constraint before the assertion is reached.
			UUID customerId = fixtures.owner().getId();
			CheckoutService.PlaceRequest forCustomer = new CheckoutService.PlaceRequest(
					shop.getSlug(), lines(1), null, null, customerId, null, null, "{}", null, null,
					ShopOrder.PaymentMethod.COD);

			ShopOrder order = checkout.place(forCustomer).order();

			assertThat(order.getCustomerId()).isEqualTo(customerId);
			assertThat(order.getGuestEmail())
					.as("a customer order needs no guest email")
					.isNull();
		}

		@Test
		@DisplayName("refuses a basket containing something out of stock")
		void refusesOutOfStock() {
			CheckoutService.PlaceRequest tooMany = new CheckoutService.PlaceRequest(
					shop.getSlug(), lines(999), "a@b.test", null, null, null, null, "{}", null, null,
					ShopOrder.PaymentMethod.COD);

			assertThatThrownBy(() -> checkout.place(tooMany))
					.isInstanceOf(CheckoutService.OutOfStockException.class)
					.hasMessageContaining("out of stock");
		}
	}

	// ------------------------------------------------------------------ payments

	@Nested
	@DisplayName("payment methods at checkout")
	class Payments {

		@Test
		@DisplayName("eSewa places an order and records a gateway reference")
		void esewaPlacesTheOrder() {
			// Phase 5. This used to assert the opposite -- that eSewa was refused because the
			// gateway was a stub -- and the stub is gone.
			//
			// The order is placed PENDING, never paid: the only thing that may mark an order paid is
			// a server-to-server status query, and a form submission is not that.
			var placement = checkout.place(request(ShopOrder.PaymentMethod.ESAWA));

			assertThat(placement.order().getStatus()).isEqualTo(ShopOrder.Status.PENDING);
			assertThat(placement.order().getPaymentStatus())
					.isNotEqualTo(ShopOrder.PaymentStatus.PAID);

			// The reference is the transaction uuid eSewa will know the payment by, and it is
			// derived from the order number so a retry reuses it.
			var transaction = payments.findAll().stream()
					.filter(t -> t.getOrderId().equals(placement.order().getId()))
					.findFirst()
					.orElseThrow(() -> new AssertionError("no payment transaction recorded"));
			assertThat(transaction.getGatewayRef())
					.isNotNull()
					.contains(placement.order().getOrderNumber().replaceAll("[^A-Za-z0-9-]", ""));
		}

		@Test
		@DisplayName("bank transfer at a shop with no bank details fails before the order is useful")
		void bankTransferNeedsConfiguration() {
			// Phase 1 has no UI to configure shop_payment_method, so a bank transfer cannot
			// succeed yet. The failure must happen here, with an actionable message, rather than
			// producing an order nobody can be paid for.
			assertThatThrownBy(() -> checkout.place(request(ShopOrder.PaymentMethod.BANK_TRANSFER)))
					.isInstanceOf(com.storefront.store.payment.BankTransferGateway
							.PaymentConfigurationException.class)
					.hasMessageContaining("has not set up bank transfer");
		}

		@Test
		@DisplayName("a refused payment method still created the order rows in-transaction")
		void refusalThrowsBeforeCommit() {
			// Whether a refusal rolls the order back can only be asserted in a test that COMMITS:
			// inside @Transactional the insert has been flushed but not committed, so counting rows
			// here would prove nothing either way. See CheckoutRollbackIT for the real assertion.
			//
			// Bank transfer at a shop with no account details, because eSewa is live and no longer
			// refuses -- it used to be the convenient stub for exactly this.
			assertThatThrownBy(() -> checkout.place(request(ShopOrder.PaymentMethod.BANK_TRANSFER)))
				.isInstanceOf(com.storefront.store.payment.BankTransferGateway.PaymentConfigurationException.class);
		}
	}
}
