package com.storefront.store.order;

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
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.inventory.InventoryService;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletService;

/**
 * The order lifecycle, and the money that moves with it.
 *
 * <p>This is the phase-1 exit criterion in test form: a verified shop publishes a product, a
 * customer orders, the shop accepts, <b>the wallet drops by exactly 5% of (subtotal − discount)</b>,
 * and cancelling puts it back. Commission excluding shipping and tax is the single number most
 * worth asserting precisely, because it is the one the plan confirmed explicitly.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class OrderLifecycleIT {

	@Autowired
	private OrderService orders;

	@Autowired
	private OrderItemRepository items;

	@Autowired
	private OrderStatusHistoryRepository history;

	@Autowired
	private OrderNumberService orderNumbers;

	@Autowired
	private InventoryService inventory;

	@Autowired
	private WalletService wallet;

	@Autowired
	private com.storefront.store.shop.ShopRepository shops;

	@Autowired
	private ProductService productService;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private Product product;
	private ProductVariant variant;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Order Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
		product = fixtures.createProduct(shop, "Rice", ProductCategory.RICE, 100_000L);
		variant = fixtures.defaultVariantOf(product);
		fixtures.stock(variant, 50);
	}

	// ------------------------------------------------------------------ helpers

	/** Places a PENDING order. {@code units} is the quantity of the stocked variant. */
	private ShopOrder place(int units) {
		return place(units, 0, 0, 0);
	}

	private ShopOrder place(int units, long discountMinor, long taxMinor, long shippingMinor) {
		ShopOrder order = orders.place(shop, new OrderService.PlacedOrder(
				orderNumbers.next(shop.getId()),
				ShopOrder.PaymentMethod.COD,
				100_000L * units, discountMinor, taxMinor, shippingMinor,
				"{\"city\":\"Kathmandu\"}",
				null,
				"buyer-" + UUID.randomUUID() + "@example.test",
				"+9779800000000",
				null, null, false,
				List.of(new OrderService.PlacedLine(product.getId(), variant.getId(),
						product.getName(), variant.getName(), ProductCategory.RICE,
						100_000L, units, null, null))),
				null);
		return order;
	}

	private void fund(long amountMinor) {
		wallet.credit(shop.getId(),
				com.storefront.store.wallet.WalletLedgerEntry.EntryType.TOPUP_APPROVED,
				amountMinor, "topup-" + UUID.randomUUID(), "test funding", null, null, null);
	}

	// ------------------------------------------------------------------ placement

	@Nested
	@DisplayName("placing an order")
	class Placing {

		@Test
		@DisplayName("computes the total from its parts and records a PENDING order")
		void computesTotal() {
			ShopOrder order = place(2, 5_000L, 13_000L, 10_000L);

			assertThat(order.getSubtotalMinor()).isEqualTo(200_000L);
			assertThat(order.getDiscountMinor()).isEqualTo(5_000L);
			assertThat(order.getTaxMinor()).isEqualTo(13_000L);
			assertThat(order.getShippingMinor()).isEqualTo(10_000L);
			assertThat(order.getTotalMinor()).isEqualTo(200_000 - 5_000 + 13_000 + 10_000);
			assertThat(order.getStatus()).isEqualTo(ShopOrder.Status.PENDING);
			assertThat(order.getPaymentStatus()).isEqualTo(ShopOrder.PaymentStatus.PENDING);
		}

		@Test
		@DisplayName("snapshots the product name and category, so a later edit changes nothing")
		void snapshotsTheLine() {
			ShopOrder order = place(1);

			// Rename the product through its own service, so this is a real catalogue change and
			// not a detached entity. The point is that the order line does not follow it.
			productService.update(shop, product, "Sona Masoori Premium", null, null,
					null, null, null, null, null, null, null, null);
			productRepository.flush();

			OrderItem line = items.findByOrderIdOrderByCreatedAtAsc(order.getId()).get(0);
			assertThat(line.getProductName())
					.as("a renamed product must not rewrite what the customer was charged")
					.isEqualTo("Rice");
			assertThat(line.getProductCategory()).isEqualTo(ProductCategory.RICE);
			assertThat(line.getProductId()).isEqualTo(product.getId());
		}

		@Test
		@DisplayName("reserves stock without removing it from the shelf")
		void reservesStock() {
			place(3);

			int onHand = inventory.find(product.getId(), variant.getId()).orElseThrow()
					.getQuantityOnHand();
			int reserved = inventory.find(product.getId(), variant.getId()).orElseThrow()
					.getQuantityReserved();

			assertThat(onHand)
					.as("reserving must not take the goods off the shelf until the shop accepts")
					.isEqualTo(50);
			assertThat(reserved).isEqualTo(3);
			assertThat(inventory.available(product.getId(), variant.getId())).isEqualTo(47);
		}

		@Test
		@DisplayName("refuses to oversell")
		void refusesToOversell() {
			// Selling more than exists is the most damaging thing a marketplace can do to a
			// customer who has already paid.
			assertThatThrownBy(() -> place(999))
					.isInstanceOf(com.storefront.store.inventory.Inventory.InsufficientStockException.class);
		}

		@Test
		@DisplayName("gives each order a number, and no two orders share one")
		void numbersAreUnique() {
			ShopOrder first = place(1);
			ShopOrder second = place(1);

			assertThat(first.getOrderNumber()).isEqualTo("ORD-0001");
			assertThat(second.getOrderNumber()).isEqualTo("ORD-0002");
			assertThat(first.getOrderNumber()).isNotEqualTo(second.getOrderNumber());
		}

		@Test
		@DisplayName("records a first history entry so the timeline starts at placement")
		void recordsPlacement() {
			ShopOrder order = place(1);

			List<OrderStatusHistory> timeline = history.findByOrderIdOrderByCreatedAtAsc(order.getId());
			assertThat(timeline).hasSize(1);
			assertThat(timeline.get(0).getFromStatus())
					.as("a timeline that starts one step in cannot answer 'when was this placed'")
					.isNull();
			assertThat(timeline.get(0).getToStatus()).isEqualTo(ShopOrder.Status.PENDING);
		}
	}

	// ------------------------------------------------------------------ commission

	@Nested
	@DisplayName("commission at accept")
	class Commission {

		@Test
		@DisplayName("charges 5% of subtotal minus discount, excluding shipping and tax")
		void chargesOnSubtotalLessDiscount() {
			// 200_000 subtotal − 20_000 discount = 180_000 commissionable.
			// 5% = 9_000. Shipping and tax are NOT in the base (D2).
			fund(1_000_000L);
			ShopOrder order = place(2, 20_000L, 23_400L /* tax */, 50_000L /* shipping */);

			ShopOrder accepted = orders.accept(shop, order, null);

			assertThat(accepted.getCommissionMinor())
					.as("shipping and tax must not be in the commissionable base")
					.isEqualTo(9_000L);
			assertThat(accepted.getCommissionRateBp()).isEqualTo(500);
		}

		@Test
		@DisplayName("debits the wallet by exactly the commission")
		void debitsTheWallet() {
			fund(1_000_000L);
			long before = wallet.balanceOf(shop.getId());

			ShopOrder order = place(2);
			ShopOrder accepted = orders.accept(shop, order, null);

			assertThat(wallet.balanceOf(shop.getId()))
					.as("200_000 x 5% = 10_000")
					.isEqualTo(before - 10_000L);
			assertThat(accepted.getCommissionMinor()).isEqualTo(10_000L);
		}

		@Test
		@DisplayName("rounds half up, consistently")
		void roundsHalfUp() {
			// An odd amount is where a rounding difference shows up. 333 x 5% = 16.65 -> 17 paisa
			// (1650 paisa x 5% = 82.5 -> 83). Fixed here so the ledger is predictable.
			fund(1_000_000L);
			Product odd = fixtures.createProduct(shop, "Odd", ProductCategory.RICE, 333L);
			fixtures.stock(fixtures.defaultVariantOf(odd), 10);

			ShopOrder order = orders.place(shop, new OrderService.PlacedOrder(
					orderNumbers.next(shop.getId()), ShopOrder.PaymentMethod.COD,
					1650L, 0, 0, 0, "{}", null,
					"odd@example.test", null, null, null, false,
					List.of(new OrderService.PlacedLine(odd.getId(),
							fixtures.defaultVariantOf(odd).getId(), "Odd", "Default",
							ProductCategory.RICE, 333L, 5, null, null))),
					null);

			assertThat(orders.accept(shop, order, null).getCommissionMinor())
					.as("1650 paisa x 5% = 82.5, rounded HALF_UP to 83")
					.isEqualTo(83L);
		}

		@Test
		@DisplayName("refuses to accept when the shop cannot pay the commission")
		void refusesWhenBroke() {
			// This is the mechanism behind "shops must credit money before accepting any order".
			ShopOrder order = place(2);

			assertThatThrownBy(() -> orders.accept(shop, order, null))
					.isInstanceOf(WalletService.InsufficientWalletBalanceException.class);

			// And nothing moved: the order is untouched, so the owner can fund and retry.
			assertThat(order.getStatus()).isEqualTo(ShopOrder.Status.PENDING);
			assertThat(order.getCommissionMinor())
					.as("a refused accept must not leave commission frozen on the order")
					.isNull();
			assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow()
					.getQuantityReserved())
					.as("stock must stay reserved for a retry")
					.isEqualTo(2);
		}

		@Test
		@DisplayName("a subscribed shop pays nothing, and that is recorded")
		void subscribedShopPaysNothing() {
			// D4. Recorded as an explicit waiver rather than a null so the ledger entry has a
			// reason a reviewer can read.
			shop.setCommissionPlan(Shop.CommissionPlan.SUBSCRIBED);
			shops.saveAndFlush(shop);

			ShopOrder order = place(2);
			ShopOrder accepted = orders.accept(shop, order, null);

			assertThat(accepted.isCommissionWaived()).isTrue();
			assertThat(accepted.getCommissionMinor()).isZero();
			assertThat(wallet.balanceOf(shop.getId()))
					.as("no debit at all for a subscribed shop")
					.isZero();
		}

		@Test
		@DisplayName("accepting twice charges once")
		void acceptIsIdempotent() {
			// A double-clicked Accept button, or a retry from a flaky connection. The
			// idempotency key derived from the order id is what makes this safe.
			fund(1_000_000L);
			ShopOrder order = place(1);
			orders.accept(shop, order, null);
			long afterFirst = wallet.balanceOf(shop.getId());

			// The state machine refuses the second transition, which is the first line of defence.
			assertThatThrownBy(() -> orders.accept(shop, order, null))
					.isInstanceOf(IllegalStateException.class);
			assertThat(wallet.balanceOf(shop.getId()))
					.as("a refused re-accept must not move money")
					.isEqualTo(afterFirst);
		}

		@Test
		@DisplayName("commits reserved stock on accept")
		void commitsStock() {
			fund(1_000_000L);
			ShopOrder order = place(3);

			orders.accept(shop, order, null);

			var row = inventory.find(product.getId(), variant.getId()).orElseThrow();
			assertThat(row.getQuantityReserved()).isZero();
			assertThat(row.getQuantityOnHand())
					.as("the goods have left the building, so on-hand drops")
					.isEqualTo(47);
		}
	}

	// ------------------------------------------------------------------ cancellation

	@Nested
	@DisplayName("cancellation")
	class Cancelling {

		@Test
		@DisplayName("cancelling before acceptance releases stock and takes no money")
		void cancelPendingReleasesStock() {
			fund(1_000_000L);
			ShopOrder order = place(4);

			orders.cancel(shop, order, "Customer changed their mind", null);

			assertThat(order.getStatus()).isEqualTo(ShopOrder.Status.CANCELLED);
			assertThat(order.getCancelReason()).isEqualTo("Customer changed their mind");
			assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow()
					.getQuantityReserved())
					.as("a cancelled order must give its reservation back")
					.isZero();
			assertThat(wallet.balanceOf(shop.getId()))
					.as("nothing was charged, so nothing is credited")
					.isEqualTo(1_000_000L);
		}

		@Test
		@DisplayName("cannot cancel an accepted order — that is a return")
		void cannotCancelAccepted() {
			fund(1_000_000L);
			ShopOrder order = place(1);
			orders.accept(shop, order, null);

			assertThatThrownBy(() -> orders.cancel(shop, order, "too late", null))
					.as("cancelling after accept would need a commission reversal for goods already packed")
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("reversal is reachable and exact once Phase 8 opens it")
		void reversalOnceReturnsEnabled() {
			// Phase 1 deliberately cannot cancel an accepted order, so the reversal path is
			// asserted through the wallet's own API rather than pretended into the state machine.
			// This documents that the money side already works when the flow is allowed.
			fund(1_000_000L);
			ShopOrder order = place(1);
			orders.accept(shop, order, null);
			long afterDebit = wallet.balanceOf(shop.getId());
			assertThat(afterDebit).isEqualTo(995_000L);

			wallet.reverseDebit(shop.getId(), "commission:" + order.getId(),
					"reversal", order.getId(), null);

			assertThat(wallet.balanceOf(shop.getId()))
					.as("the reversal returns exactly what the debit took")
					.isEqualTo(1_000_000L);
		}

		@Test
		@DisplayName("cancelling twice releases nothing extra")
		void cancelIsIdempotent() {
			ShopOrder order = place(2);
			orders.cancel(shop, order, "changed mind", null);

			assertThatThrownBy(() -> orders.cancel(shop, order, "again", null))
					.isInstanceOf(IllegalStateException.class);
			assertThat(inventory.find(product.getId(), variant.getId()).orElseThrow()
					.getQuantityReserved()).isZero();
		}
	}

	// ------------------------------------------------------------------ timeline

	@Nested
	@DisplayName("the timeline")
	class Timeline {

		@Test
		@DisplayName("records every hop with its real previous status")
		void recordsEveryHop() {
			fund(1_000_000L);
			ShopOrder order = place(1);

			orders.accept(shop, order, null);
			orders.markProcessing(shop, order, null);
			orders.markShipped(shop, order, null);
			orders.markDelivered(shop, order, null);

			List<OrderStatusHistory> timeline = history.findByOrderIdOrderByCreatedAtAsc(order.getId());
			assertThat(timeline).extracting(OrderStatusHistory::getToStatus)
					.containsExactly(ShopOrder.Status.PENDING, ShopOrder.Status.ACCEPTED,
							ShopOrder.Status.PROCESSING, ShopOrder.Status.SHIPPED,
							ShopOrder.Status.DELIVERED);

			assertThat(timeline.get(1).getFromStatus())
					.as("a history row reading ACCEPTED -> ACCEPTED would make the timeline a lie")
					.isEqualTo(ShopOrder.Status.PENDING);
			assertThat(timeline.get(4).getFromStatus())
					.isEqualTo(ShopOrder.Status.SHIPPED);
		}

		@Test
		@DisplayName("marks COD paid at dispatch, when the shop has the cash")
		void codBecomesPaidAtShip() {
			fund(1_000_000L);
			ShopOrder order = place(1);
			orders.accept(shop, order, null);
			orders.markProcessing(shop, order, null);

			assertThat(order.getPaymentStatus())
					.as("nothing has been collected yet at accept")
					.isEqualTo(ShopOrder.PaymentStatus.PENDING);

			orders.markShipped(shop, order, null);

			assertThat(order.getPaymentStatus())
					.as("COD is settled when the courier has the cash")
					.isEqualTo(ShopOrder.PaymentStatus.PAID);
		}

		@Test
		@DisplayName("refuses to deliver an unverified bank transfer")
		void refusesUnverifiedBankTransfer() {
			// Otherwise a shop could deliver an order whose money never arrived, and discover it
			// only when the bank statement comes.
			fund(1_000_000L);
			ShopOrder order = orders.place(shop, new OrderService.PlacedOrder(
					orderNumbers.next(shop.getId()), ShopOrder.PaymentMethod.BANK_TRANSFER,
					100_000L, 0, 0, 0, "{}", null,
					"bank@example.test", null, null, null, false,
					List.of(new OrderService.PlacedLine(product.getId(), variant.getId(),
							product.getName(), variant.getName(), ProductCategory.RICE,
							100_000L, 1, null, null))),
					null);
			orders.accept(shop, orders.getOwned(shop, order.getId()), null);
			ShopOrder live = orders.getOwned(shop, order.getId());
			orders.markProcessing(shop, live, null);
			orders.markShipped(shop, orders.getOwned(shop, live.getId()), null);

			assertThatThrownBy(() ->
					orders.markDelivered(shop, orders.getOwned(shop, live.getId()), null))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("verified");
		}
	}

	// ------------------------------------------------------------------ tenancy

	@Nested
	@DisplayName("tenant isolation")
	class Tenancy {

		@Test
		@DisplayName("another shop's order reads as not found, not forbidden")
		void crossShopIsIndistinguishable() {
			ShopOrder mine = place(1);
			Shop theirs = fixtures.createVerifiedShop(
					"Their Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);

			// 403 would confirm the id is real, turning this into an existence oracle.
			assertThatThrownBy(() -> orders.getOwned(theirs, mine.getId()))
					.isInstanceOf(OrderService.OrderNotFoundException.class);
		}

		@Test
		@DisplayName("another shop cannot accept my order")
		void crossShopCannotAccept() {
			ShopOrder mine = place(1);
			Shop theirs = fixtures.createVerifiedShop(
					"Their Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);

			assertThatThrownBy(() -> orders.accept(theirs, orders.getOwned(theirs, mine.getId()), null))
					.isInstanceOf(OrderService.OrderNotFoundException.class);
		}
	}
}
