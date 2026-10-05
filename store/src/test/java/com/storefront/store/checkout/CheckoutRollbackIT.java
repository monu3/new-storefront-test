package com.storefront.store.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.inventory.InventoryService;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.payment.BankTransferGateway;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Checkout rollback, asserted in a test that actually commits.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> Every other suite rolls back, which is right
 * for almost everything — but a rolled-back test cannot answer "did this failure leave anything
 * behind?", because inside a transaction the insert has been flushed while not yet committed, and
 * the answer looks identical whether or not the rollback logic works. This is the plan's documented
 * exception: a suite that commits, and cleans up in {@code @AfterEach} instead.
 *
 * <p>It is therefore the only place the transactional guarantee is genuinely proven. If a failed
 * checkout committed a partial order, the row would still be here to count.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@com.storefront.store.support.ResetTestDatabase
class CheckoutRollbackIT {

	@Autowired
	private CheckoutService checkout;

	@Autowired
	private ProductService products;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private com.storefront.store.order.ShopOrderRepository orders;

	@Autowired
	private com.storefront.store.order.OrderNumberSequenceRepository sequences;

	@Autowired
	private InventoryService inventory;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private com.storefront.store.support.TestDatabaseCleaner cleaner;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	private Shop shop;
	private Product product;
	private ProductVariant variant;

	@AfterEach
	/**
	 * Remove what the test committed.
	 *
	 * <p>Orders first, then the shop — and that order is not incidental.
	 * {@code shop_order.shop_id} is {@code ON DELETE RESTRICT} precisely so an order can never be
	 * orphaned from the shop it was placed at, which means deleting the shop before its orders is
	 * refused by the database. Everything else (order lines, history, payments, stock, the number
	 * counter) cascades from the shop once the orders are gone.
	 */
	void cleanUp() {
		if (shop != null) {
			// Through the cleaner, which deletes in an order the RESTRICT constraints allow and
			// refuses to run against anything but a test database.
			cleaner.deleteShop(shop.getId());
			shop = null;
		}
	}

	@Test
	@DisplayName("a payment failure commits no order and no stock reservation")
	void paymentFailureCommitsNothing() {
		setUpShopWithStock();

		// eSewa was the convenient failure here while it was a stub. It is live now, so the failure
		// is bank transfer at a shop with no account details -- which fails at the same point in
		// the transaction, after the order and its reservation exist.
		assertThatThrownBy(() -> checkout.place(guestOrder(ShopOrder.PaymentMethod.BANK_TRANSFER)))
				.isInstanceOf(BankTransferGateway.PaymentConfigurationException.class);

		// Counted from the database, after the transaction has rolled back. This is the assertion
		// a @Transactional test cannot make.
		assertThat(orders.findByShopIdOrderByPlacedAtDesc(shop.getId(), Pageable.ofSize(10)))
				.as("a failed checkout must not leave a half-created order behind")
				.isEmpty();

		assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow().getQuantityReserved())
				.as("stock must not stay reserved for an order that does not exist")
				.isZero();
	}

	@Test
	@DisplayName("a failed checkout does not advance the shop's order numbering")
	void failureDoesNotConsumeANumber() {
		// The reason order numbers are allocated inside the checkout transaction rather than in
		// their own. A gap would make the customer's retry come back with ORD-0002 for what is
		// visibly their first order.
		setUpShopWithStock();

		assertThatThrownBy(() -> checkout.place(guestOrder(ShopOrder.PaymentMethod.BANK_TRANSFER)))
				.isInstanceOf(BankTransferGateway.PaymentConfigurationException.class);

		// Now a successful order: it must be the first number, not the second.
		ShopOrder order = checkout.place(guestOrder(ShopOrder.PaymentMethod.COD)).order();

		assertThat(order.getOrderNumber()).isEqualTo("ORD-0001");
		assertThat(sequences.findById(shop.getId())).isPresent();
		assertThat(sequences.findById(shop.getId()).orElseThrow().getLastNumber()).isEqualTo(1L);
	}

	@Test
	@DisplayName("a failed checkout commits no notification event")
	void failureCommitsNoEvent() {
		// The other half of the outbox guarantee, in the only suite able to prove it: an event
		// written outside the order's transaction would survive a rolled-back order, and the
		// customer would be emailed about an order that does not exist.
		setUpShopWithStock();
		UUID shopId = shop.getId();

		assertThatThrownBy(() -> checkout.place(guestOrder(ShopOrder.PaymentMethod.BANK_TRANSFER)))
				.isInstanceOf(BankTransferGateway.PaymentConfigurationException.class);

		Long eventsForShop = jdbc.queryForObject(
				"SELECT count(*) FROM outbox_event e "
						+ "JOIN shop_order o ON e.aggregate_id = o.id "
						+ "WHERE e.aggregate_type = 'ORDER' AND o.shop_id = ?",
				Long.class, shopId);

		assertThat(eventsForShop)
				.as("an ORDER_PLACED event that outlived its rolled-back order would email a "
						+ "customer about nothing")
				.isZero();
	}

	@Test
	@DisplayName("a successful checkout does commit its event")
	void successCommitsItsEvent() {
		setUpShopWithStock();
		ShopOrder order = checkout.place(guestOrder(ShopOrder.PaymentMethod.COD)).order();

		Long events = jdbc.queryForObject(
				"SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ORDER_PLACED'",
				Long.class, order.getId());

		assertThat(events).isEqualTo(1L);
	}

	@Test
	@DisplayName("a successful order does commit")
	void successCommits() {
		// The other half of the guarantee: proving rollback works is only meaningful if the
		// success path really does persist.
		setUpShopWithStock();

		ShopOrder order = checkout.place(guestOrder(ShopOrder.PaymentMethod.COD)).order();

		assertThat(orders.findById(order.getId()))
				.as("a successful checkout must be committed, not just flushed")
				.isPresent();
		assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow().getQuantityReserved())
				.isEqualTo(2);
	}

	// ------------------------------------------------------------------ helpers

	private void setUpShopWithStock() {
		shop = fixtures.createVerifiedShop(
				"Rollback Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
		product = fixtures.createProduct(shop, "Rice", ProductCategory.RICE, 10_000L);
		variant = fixtures.defaultVariantOf(product);
		fixtures.stock(variant, 50);
		products.publish(shop, product);
	}

	private CheckoutService.PlaceRequest guestOrder(ShopOrder.PaymentMethod method) {
		return new CheckoutService.PlaceRequest(
				shop.getSlug(),
				List.of(new QuoteService.RequestedLine(variant.getId(), 2)),
				"buyer-" + UUID.randomUUID() + "@example.test",
				"+9779800000000",
				null,
				"Kathmandu",
				null,
				"{\"city\":\"Kathmandu\"}",
				null,
				null,
				method);
	}
}
