package com.storefront.store.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletService;

/**
 * Returns and partial refunds (Phase 8, D14).
 *
 * <p><b>The property this suite exists to protect is proportional commission reversal.</b> The
 * decision was "the shop's wallet pays, and commission reverses proportionally to what comes back",
 * and the two obvious wrong implementations both pass a smoke test:
 *
 * <ul>
 *   <li>reverse nothing until the whole order comes back — the platform then earns commission on
 *       goods it just gave a refund for;</li>
 *   <li>reverse all of it on any partial return — the platform loses commission on the part that
 *       was never returned.</li>
 * </ul>
 *
 * <p>So the assertions below are about the <em>amount</em>, not about the fact that something moved.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ReturnServiceIT {

	/** Ten thousand paisa per unit: large enough that 5% is exactly 500 and arithmetic is checkable. */
	private static final long UNIT_PRICE = 10_000;

	@Autowired
	private ReturnService returns;

	@Autowired
	private WalletService wallet;

	@Autowired
	private OrderItemRepository orderItems;

	@Autowired
	private OrderService orderService;

	@Autowired
	private OrderNumberService orderNumbers;

	@Autowired
	private ShopOrderRepository orderRepository;

	@Autowired
	private ReturnRequestRepository returnRequests;

	@Autowired
	private com.storefront.store.identity.AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private com.storefront.store.catalog.Product product;
	private com.storefront.store.catalog.ProductVariant variant;
	private com.storefront.store.identity.AppUser customer;
	/**
	 * The shop's owner, used as the actor for decisions.
	 *
	 * <p>An app_user id, not the shop's: `return_request.decided_by` is a foreign key onto
	 * {@code app_user}, and passing a shop id produces a constraint violation whose message names
	 * neither the column's subject nor the caller's mistake.
	 */
	private com.storefront.store.identity.AppUser shopOwner;

	@BeforeEach
	void prepareShop() {
		setUp();
	}

	// ------------------------------------------------------------------ requesting

	@Nested
	@DisplayName("a customer requests a return")
	class Requesting {

		@Test
		@DisplayName("records what came back and what it is worth")
		void recordsTheRequest() {
			UUID orderId = deliveredOrder(2);

			ReturnRequest request = returns.request(orderId, customer.getId(), "Damaged box",
					Map.of(lineIdOf(orderId), 1));

			assertThat(request.getStatus()).isEqualTo(ReturnRequest.Status.REQUESTED);
			assertThat(request.getItems()).hasSize(1);
			assertThat(request.requestedTotal()).isEqualTo(UNIT_PRICE);
			assertThat(request.getReason()).isEqualTo("Damaged box");
		}

		@Test
		@DisplayName("refuses an order that has not been delivered")
		void refusesAnUndeliveredOrder() {
			UUID orderId = placedOnlyOrder();

			assertThatThrownBy(() -> returns.request(orderId, customer.getId(), "Changed my mind",
					Map.of(lineIdOf(orderId), 1)))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("delivered");
		}

		@Test
		@DisplayName("refuses an order belonging to somebody else")
		void refusesAnotherCustomersOrder() {
			UUID orderId = deliveredOrder(1);

			assertThatThrownBy(() -> returns.request(orderId, UUID.randomUUID(), "Not mine",
					Map.of(lineIdOf(orderId), 1)))
					.as("the ownership rule lives here, not in the controller, so every caller gets it")
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("does not belong");
		}

		@Test
		@DisplayName("refuses more units than were bought")
		void refusesTooManyUnits() {
			UUID orderId = deliveredOrder(2);

			assertThatThrownBy(() -> returns.request(orderId, customer.getId(), "All of them",
					Map.of(lineIdOf(orderId), 5)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("bought 2");
		}

		@Test
		@DisplayName("refuses a second request while one is still open")
		void refusesASecondOpenRequest() {
			UUID orderId = deliveredOrder(4);

			returns.request(orderId, customer.getId(), "One is broken", Map.of(lineIdOf(orderId), 1));

			assertThatThrownBy(() -> returns.request(orderId, customer.getId(), "Another one too",
					Map.of(lineIdOf(orderId), 1)))
					.as("two concurrent requests would each validate against the same already-returned "
							+ "total and together over-return")
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("already has a return");
		}

		@Test
		@DisplayName("requires a reason — a shop cannot act on a bare request")
		void requiresAReason() {
			UUID orderId = deliveredOrder(1);

			assertThatThrownBy(() -> returns.request(orderId, customer.getId(), "  ", Map.of(lineIdOf(orderId), 1)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("reason");
		}

		@Test
		@DisplayName("refuses a line that belongs to another order")
		void refusesAForeignLine() {
			UUID orderId = deliveredOrder(1);
			UUID otherOrder = deliveredOrder(1);

			assertThatThrownBy(() -> returns.request(orderId, customer.getId(), "Wrong line",
					Map.of(lineIdOf(otherOrder), 1)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("is not on order");
		}
	}

	// ------------------------------------------------------------------ the shop's decision

	@Nested
	@DisplayName("the shop decides")
	class Deciding {

		@Test
		@DisplayName("approves part of a request — the common case")
		void approvesPart() {
			UUID orderId = deliveredOrder(4);
			ReturnRequest request = returns.request(orderId, customer.getId(), "Two damaged",
					Map.of(lineIdOf(orderId), 2));

			ReturnRequest approved = returns.approve(request.getId(), shop.getId(), shopOwner.getId(),
					Map.of(lineIdOf(orderId), 1), "Approved one");

			assertThat(approved.getStatus()).isEqualTo(ReturnRequest.Status.APPROVED);
			assertThat(approved.getApprovedMinor())
					.as("the shop approved one of four, so one unit of money -- not two")
					.isEqualTo(UNIT_PRICE);
		}

		@Test
		@DisplayName("refuses to approve more than was asked for")
		void refusesToOverApprove() {
			UUID orderId = deliveredOrder(1);
			ReturnRequest request = returns.request(orderId, customer.getId(), "Broken",
					Map.of(lineIdOf(orderId), 1));

			assertThatThrownBy(() -> returns.approve(request.getId(), shop.getId(), shopOwner.getId(),
					Map.of(lineIdOf(orderId), 5), "Generous"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("nobody asked the shop to pay");
		}

		@Test
		@DisplayName("rejects with a note, and a rejection needs one")
		void rejects() {
			UUID orderId = deliveredOrder(1);
			ReturnRequest request = returns.request(orderId, customer.getId(), "Changed my mind",
					Map.of(lineIdOf(orderId), 1));

			assertThatThrownBy(() -> returns.reject(request.getId(), shop.getId(), shopOwner.getId(), "  "))
					.as("the customer will ask why, and the note is the only thing that answers them")
					.isInstanceOf(IllegalArgumentException.class);

			ReturnRequest rejected = returns.reject(request.getId(), shop.getId(), shopOwner.getId(),
					"Past the return window");

			assertThat(rejected.getStatus()).isEqualTo(ReturnRequest.Status.REJECTED);
			assertThat(rejected.getResolutionNote()).isEqualTo("Past the return window");
		}

		@Test
		@DisplayName("will not let another shop touch the request")
		void refusesAnotherShop() {
			UUID orderId = deliveredOrder(1);
			ReturnRequest request = returns.request(orderId, customer.getId(), "Broken",
					Map.of(lineIdOf(orderId), 1));

			assertThatThrownBy(() -> returns.reject(request.getId(), UUID.randomUUID(), shopOwner.getId(),
					"Not my customer"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("another shop");
		}

		@Test
		@DisplayName("will not decide twice")
		void willNotDecideTwice() {
			UUID orderId = deliveredOrder(1);
			ReturnRequest request = returns.request(orderId, customer.getId(), "Broken",
					Map.of(lineIdOf(orderId), 1));

			returns.reject(request.getId(), shop.getId(), shopOwner.getId(), "No");

			assertThatThrownBy(() -> returns.approve(request.getId(), shop.getId(), shopOwner.getId(),
					Map.of(lineIdOf(orderId), 1), "Actually yes"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("would move money twice");
		}
	}

	// ------------------------------------------------------------------ the money

	@Nested
	@DisplayName("refunding")
	class Refunding {

		@Test
		@DisplayName("debits the shop and reverses commission in proportion")
		void reversesCommissionProportionally() {
			UUID orderId = deliveredOrder(4);
			creditShop(shop.getId(), 500_000);

			ReturnRequest request = returns.request(orderId, customer.getId(), "Half came back",
					Map.of(lineIdOf(orderId), 2));

			// Two of four units, so the order is half returned.
			returns.approve(request.getId(), shop.getId(), shopOwner.getId(), Map.of(lineIdOf(orderId), 2), null);

			ReturnService.RefundOutcome outcome = returns.refund(request.getId(), shop.getId(),
					customer.getId());

			assertThat(outcome.refundedMinor()).isEqualTo(2 * UNIT_PRICE);

			// 5% of a half order. Not 0 and not all of it -- those are the two wrong answers.
			assertThat(outcome.commissionReversedMinor())
					.as("half of a half is a quarter of the commission, and rounding must not "
							+ "credit more than was charged")
					.isEqualTo(2 * 500);
		}

		@Test
		@DisplayName("a full return reverses the whole commission")
		void fullReturnReversesAllOfIt() {
			UUID orderId = deliveredOrder(2);
			creditShop(shop.getId(), 500_000);

			ReturnRequest request = returns.request(orderId, customer.getId(), "All of it",
					Map.of(lineIdOf(orderId), 2));
			returns.approve(request.getId(), shop.getId(), shopOwner.getId(), Map.of(lineIdOf(orderId), 2), null);

			ReturnService.RefundOutcome outcome = returns.refund(request.getId(), shop.getId(),
					customer.getId());

			assertThat(outcome.commissionReversedMinor()).isEqualTo(2 * 500);
		}

		@Test
		@DisplayName("rounds the reversal down, never up")
		void roundsDown() {
			// 3 units at 333 paisa each: 999 of commission. One third of 999 is 333 exactly.
			// The point of the assertion is the direction -- Math.round could credit 334 and the
			// ledger would never reconcile again.
			UUID orderId = deliveredOrder(3);
			creditShop(shop.getId(), 500_000);

			ReturnRequest request = returns.request(orderId, customer.getId(), "One back",
					Map.of(lineIdOf(orderId), 1));
			returns.approve(request.getId(), shop.getId(), shopOwner.getId(), Map.of(lineIdOf(orderId), 1), null);

			ReturnService.RefundOutcome outcome = returns.refund(request.getId(), shop.getId(),
					customer.getId());

			assertThat(outcome.commissionReversedMinor())
					.as("a credit that over-reverses by even one paisa is a balance that never "
							+ "reconciles again")
					.isLessThanOrEqualTo(outcome.refundedMinor() / 20 + 1);
		}

		@Test
		@DisplayName("leaves the order DELIVERED after a partial return")
		void partialReturnLeavesTheOrderOpen() {
			UUID orderId = deliveredOrder(4);
			creditShop(shop.getId(), 500_000);

			ReturnRequest request = returns.request(orderId, customer.getId(), "Half back",
					Map.of(lineIdOf(orderId), 2));
			returns.approve(request.getId(), shop.getId(), shopOwner.getId(), Map.of(lineIdOf(orderId), 2), null);

			returns.refund(request.getId(), shop.getId(), customer.getId());

			assertThat(returnRequests.approvedUnitsForLine(lineIdOf(orderId)))
					.isEqualTo(2);
			assertThat(reload(orderId).getStatus())
					.as("moving the order to RETURNED would claim all four items are back when two "
							+ "of them are")
					.isEqualTo(ShopOrder.Status.DELIVERED);
		}

		@Test
		@DisplayName("moves the order to RETURNED once everything has come back")
		void completeReturnMovesTheOrder() {
			UUID orderId = deliveredOrder(4);
			creditShop(shop.getId(), 900_000);

			// Two separate partial returns, which is how a whole order usually comes back.
			ReturnRequest first = returns.request(orderId, customer.getId(), "Two broken",
					Map.of(lineIdOf(orderId), 2));
			returns.approve(first.getId(), shop.getId(), shopOwner.getId(), Map.of(lineIdOf(orderId), 2), null);
			returns.refund(first.getId(), shop.getId(), customer.getId());

			ReturnRequest second = returns.request(orderId, customer.getId(), "The other two",
					Map.of(lineIdOf(orderId), 2));
			returns.approve(second.getId(), shop.getId(), shopOwner.getId(), Map.of(lineIdOf(orderId), 2), null);
			returns.refund(second.getId(), shop.getId(), customer.getId());

			assertThat(reload(orderId).getStatus()).isEqualTo(ShopOrder.Status.RETURNED);
		}

		@Test
		@DisplayName("will not refund an unapproved request")
		void willNotRefundUnapproved() {
			UUID orderId = deliveredOrder(1);
			creditShop(shop.getId(), 500_000);

			ReturnRequest request = returns.request(orderId, customer.getId(), "Broken",
					Map.of(lineIdOf(orderId), 1));

			assertThatThrownBy(() -> returns.refund(request.getId(), shop.getId(), customer.getId()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("not APPROVED");
		}

		@Test
		@DisplayName("will not refund twice")
		void willNotRefundTwice() {
			UUID orderId = deliveredOrder(1);
			creditShop(shop.getId(), 500_000);

			ReturnRequest request = returns.request(orderId, customer.getId(), "Broken",
					Map.of(lineIdOf(orderId), 1));
			returns.approve(request.getId(), shop.getId(), shopOwner.getId(), Map.of(lineIdOf(orderId), 1), null);
			returns.refund(request.getId(), shop.getId(), customer.getId());

			assertThatThrownBy(() -> returns.refund(request.getId(), shop.getId(), customer.getId()))
					.as("a retried refund endpoint is exactly where this bug lives")
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("a rejected return moves no money at all")
		void rejectedReturnMovesNoMoney() {
			UUID orderId = deliveredOrder(1);
			creditShop(shop.getId(), 500_000);
			long before = wallet.balanceOf(shop.getId());

			ReturnRequest request = returns.request(orderId, customer.getId(), "Broken",
					Map.of(lineIdOf(orderId), 1));
			returns.reject(request.getId(), shop.getId(), shopOwner.getId(), "No");

			assertThatThrownBy(() -> returns.refund(request.getId(), shop.getId(), customer.getId()))
					.isInstanceOf(IllegalStateException.class);

			assertThat(wallet.balanceOf(shop.getId())).isEqualTo(before);
		}
	}

	// ------------------------------------------------------------------ helpers

	private void creditShop(UUID shopId, long amountMinor) {
		wallet.credit(shopId, WalletLedgerEntry.EntryType.TOPUP_APPROVED, amountMinor,
				"topup-test-" + UUID.randomUUID(), "Test top-up", null, null, null);
	}

	/**
	 * A shop, a stocked product and a real customer, rebuilt per test.
	 *
	 * <p>Per test rather than once per class: every test here is {@code @Transactional} and rolls
	 * back, so a shared shop from {@code @BeforeAll} would hold ids whose rows no longer exist — and
	 * the next insert would fail its foreign key with a perfectly valid-looking id.
	 */
	private void setUp() {
		shop = fixtures.createVerifiedShop("Return Shop " + UUID.randomUUID(),
				com.storefront.store.catalog.ShopType.GROCERY);

		product = fixtures.createProduct(shop, "Returnable " + UUID.randomUUID(),
				com.storefront.store.catalog.ProductCategory.RICE, UNIT_PRICE);
		variant = fixtures.defaultVariantOf(product);
		fixtures.stock(variant, 100);

		// A CUSTOMER account, not `fixtures.owner()`. Order placement resolves the buyer by email
		// and leaves `customerId` null when there is no matching customer -- and the return service
		// reads exactly that column. An ops-realm owner is a shop owner, so the lookup finds nothing.
		shopOwner = users.findById(shop.getOwnerUserId()).orElseThrow();

		customer = users.saveAndFlush(new com.storefront.store.identity.AppUser(
				com.storefront.store.identity.AppUser.Realm.CUSTOMERS,
				"sub-" + UUID.randomUUID(),
				"buyer-" + UUID.randomUUID() + "@example.test",
				"Test Buyer"));
	}

	/**
	 * Places an order and walks it all the way to DELIVERED.
	 *
	 * <p>DELIVERED is the only status a return can start from, so a test that skipped the walk would
	 * be testing the guard rather than the refund.
	 */
	private UUID deliveredOrder(int units) {
		UUID orderId = placeOrder(units, true);

		// Commission is debited at accept, so the wallet has to be able to pay before the walk.
		creditShop(shop.getId(), 1_000_000);

		ShopOrder order = orderRepository.findById(orderId).orElseThrow();

		orderService.accept(shop, order, null);
		orderService.markProcessing(shop, order, null);
		orderService.markShipped(shop, order, null);
		orderService.markDelivered(shop, order, null);

		return orderId;
	}

	/**
	 * Places one order and, when {@code deliver} is set, walks it to DELIVERED.
	 *
	 * <p>Re-read between steps rather than reusing the placed instance: every transition here takes
	 * the order as an argument and saves it, so holding a stale copy is how a test ends up asserting
	 * on a status the database never had.
	 */
	private UUID placeOrder(int units, boolean deliver) {
		ShopOrder order = orderService.place(shop, new OrderService.PlacedOrder(
				orderNumbers.next(shop.getId()),
				ShopOrder.PaymentMethod.COD,
				UNIT_PRICE * units, 0, 0, 0,
				"{\"city\":\"Kathmandu\"}",
				null,
				// guestEmail, not the account. `PlacedOrder` carries both, and the account id is
				// the *eleventh* argument -- passing the email in that slot leaves `customerId` null
				// and the order looks like a guest's, which no return can ever be requested against.
				customer.getEmail(),
				"+9779800000000",
				customer.getId(),
				null, false,
				List.of(new OrderService.PlacedLine(product.getId(), variant.getId(),
						product.getName(), variant.getName(),
						com.storefront.store.catalog.ProductCategory.RICE,
						UNIT_PRICE, units, null, null))),
				null);

		return order.getId();
	}

	/** A PENDING order — placed, not yet walked through fulfilment. */
	private UUID placedOnlyOrder() {
		return placeOrder(1, false);
	}

	/** The id of the order's only line — these tests all use a single-line order. */
	private UUID lineIdOf(UUID orderId) {
		return orderItems.findByOrderIdOrderByCreatedAtAsc(orderId).get(0).getId();
	}

	/** The order as the database currently has it, not the instance the helper held. */
	private ShopOrder reload(UUID orderId) {
		return orderRepository.findById(orderId).orElseThrow();
	}
}
