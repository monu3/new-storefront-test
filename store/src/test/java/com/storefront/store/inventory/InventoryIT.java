package com.storefront.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Stock: reservation, commitment and the overselling guard.
 *
 * <p>All of it runs inside a real transaction because {@link InventoryService#reserve} is
 * {@link org.springframework.transaction.annotation.Propagation#MANDATORY} — the reservation and
 * the order that caused it must commit or roll back together. A test that called it outside a
 * transaction would be testing a code path that cannot occur in production.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class InventoryIT {

	@Autowired
	private InventoryService inventory;

	@Autowired
	private InventoryMovementRepository movements;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private Product product;
	private ProductVariant variant;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Stock Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
		product = fixtures.createProduct(shop, "Stocked", ProductCategory.RICE, 10_000L);
		variant = fixtures.defaultVariantOf(product);
	}

	private Inventory row() {
		return inventory.find(product.getId(), variant.getId()).orElseThrow();
	}

	@Nested
	@DisplayName("restocking")
	class Restocking {

		@Test
		@DisplayName("creates the row at zero, then adds units and logs the movement")
		void restockLogsMovement() {
			inventory.restock(product.getId(), variant.getId(), 30, null, "delivery arrived");

			assertThat(row().getQuantityOnHand()).isEqualTo(30);
			assertThat(row().getQuantityReserved()).isZero();

			var log = movements.findByProductIdAndVariantIdOrderByCreatedAtDesc(
					product.getId(), variant.getId(),
					org.springframework.data.domain.Pageable.unpaged()).getContent();
			assertThat(log).hasSize(1);
			assertThat(log.get(0).getDelta()).isEqualTo(30);
			assertThat(log.get(0).getReason()).isEqualTo(InventoryMovement.Reason.RESTOCK);
		}

		@Test
		@DisplayName("a stocktake logs the difference, not the new total")
		void stocktakeLogsDelta() {
			inventory.restock(product.getId(), variant.getId(), 40, null, null);
			inventory.setOnHand(product.getId(), variant.getId(), 37, null, "stocktake");

			var log = movements.findByProductIdAndVariantIdOrderByCreatedAtDesc(
					product.getId(), variant.getId(),
					org.springframework.data.domain.Pageable.unpaged()).getContent();
			assertThat(log.get(0).getDelta())
					.as("a stocktake that finds 37 where the system said 40 must leave a trace of 3")
					.isEqualTo(-3);
			assertThat(log.get(0).getReason()).isEqualTo(InventoryMovement.Reason.ADJUSTMENT);
		}

		@Test
		@DisplayName("a restock keeps the note the owner gave")
		void restockKeepsNote() {
			// The note is the difference between a log an owner trusts and one they guess at.
			// It was accepted by the service and then dropped on the way to the movement.
			inventory.restock(product.getId(), variant.getId(), 25, null, "delivered Tuesday");

			var log = movements.findByProductIdAndVariantIdOrderByCreatedAtDesc(
					product.getId(), variant.getId(),
					org.springframework.data.domain.Pageable.unpaged()).getContent();

			assertThat(log.get(0).getNote()).isEqualTo("delivered Tuesday");
		}

		@Test
		@DisplayName("a stocktake that matches the system logs nothing")
		void noChangeNoMovement() {
			// Otherwise the movement log fills with zero rows that have to be explained away.
			inventory.restock(product.getId(), variant.getId(), 40, null, null);
			inventory.setOnHand(product.getId(), variant.getId(), 40, null, "stocktake");

			assertThat(movements.findByProductIdAndVariantIdOrderByCreatedAtDesc(
					product.getId(), variant.getId(),
					org.springframework.data.domain.Pageable.unpaged())).hasSize(1);
		}

		@Test
		@DisplayName("cannot remove more than is on the shelf")
		void refusesNegativeStock() {
			inventory.restock(product.getId(), variant.getId(), 5, null, null);

			assertThatThrownBy(() -> inventory.setOnHand(product.getId(), variant.getId(), -1, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("cannot stocktake below what placed orders have already reserved")
		void refusesBelowReserved() {
			inventory.restock(product.getId(), variant.getId(), 20, null, null);
			inventory.reserve(product.getId(), variant.getId(), 8,
					UUID.randomUUID(), false);

			assertThatThrownBy(() -> inventory.setOnHand(product.getId(), variant.getId(), 3, null, null))
					.as("setting stock to 3 while 8 units are committed to customers would oversell")
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("reserved");
		}
	}

	@Nested
	@DisplayName("reservation")
	class Reservation {

		@Test
		@DisplayName("reserving takes from available, not from on-hand")
		void reservationDoesNotRemoveFromShelf() {
			inventory.restock(product.getId(), variant.getId(), 10, null, null);

			inventory.reserve(product.getId(), variant.getId(), 4, UUID.randomUUID(), false);

			assertThat(row().getQuantityOnHand()).isEqualTo(10);
			assertThat(row().getQuantityReserved()).isEqualTo(4);
			assertThat(row().available()).isEqualTo(6);
		}

		@Test
		@DisplayName("refuses to reserve more than is available")
		void refusesOverReservation() {
			inventory.restock(product.getId(), variant.getId(), 3, null, null);

			assertThatThrownBy(() ->
					inventory.reserve(product.getId(), variant.getId(), 4, UUID.randomUUID(), false))
					.isInstanceOf(Inventory.InsufficientStockException.class)
					.hasMessageContaining("Only 3 in stock");
		}

		@Test
		@DisplayName("the exception reports what was asked for and what exists")
		void exceptionIsActionable() {
			inventory.restock(product.getId(), variant.getId(), 3, null, null);

			try {
				inventory.reserve(product.getId(), variant.getId(), 7, UUID.randomUUID(), false);
			} catch (Inventory.InsufficientStockException e) {
				// The client needs both numbers to tell the customer what is actually left.
				assertThat(e.getRequested()).isEqualTo(7);
				assertThat(e.getAvailable()).isEqualTo(3);
				return;
			}
			throw new AssertionError("expected InsufficientStockException");
		}

		@Test
		@DisplayName("a backorder is tolerated and logged rather than refused")
		void backorderIsAllowed() {
			// The shop explicitly allows made-to-order items, so an order stands and the gap is
			// recorded for the stocktake to explain later.
			inventory.restock(product.getId(), variant.getId(), 1, null, null);
			UUID orderId = UUID.randomUUID();

			inventory.reserve(product.getId(), variant.getId(), 5, orderId, true);

			assertThat(row().getQuantityReserved()).isZero();
			assertThat(movements.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("ORDER", orderId))
					.as("a backorder with no movement row is stock that vanishes with no explanation")
					.hasSize(1);
		}

		@Test
		@DisplayName("releasing gives the reservation back")
		void releaseReturnsStock() {
			inventory.restock(product.getId(), variant.getId(), 10, null, null);
			inventory.reserve(product.getId(), variant.getId(), 6, UUID.randomUUID(), false);

			inventory.release(product.getId(), variant.getId(), 6, UUID.randomUUID());

			assertThat(row().getQuantityReserved()).isZero();
			assertThat(row().available()).isEqualTo(10);
		}

		@Test
		@DisplayName("releasing more than reserved clamps rather than going negative")
		void releaseIsClamped() {
			// A retried cancellation must not fail an otherwise valid request.
			inventory.restock(product.getId(), variant.getId(), 10, null, null);
			inventory.reserve(product.getId(), variant.getId(), 3, UUID.randomUUID(), false);

			inventory.release(product.getId(), variant.getId(), 99, UUID.randomUUID());

			assertThat(row().getQuantityReserved()).isZero();
			assertThat(row().available()).isEqualTo(10);
		}
	}

	@Nested
	@DisplayName("commit at accept")
	class Commit {

		@Test
		@DisplayName("moves reserved units off the shelf")
		void commitRemovesFromShelf() {
			inventory.restock(product.getId(), variant.getId(), 10, null, null);
			inventory.reserve(product.getId(), variant.getId(), 4, UUID.randomUUID(), false);

			inventory.commit(product.getId(), variant.getId(), 4, UUID.randomUUID());

			assertThat(row().getQuantityOnHand()).isEqualTo(6);
			assertThat(row().getQuantityReserved()).isZero();
		}

		@Test
		@DisplayName("committing twice is a no-op, so a retried accept cannot deduct twice")
		void commitIsIdempotent() {
			inventory.restock(product.getId(), variant.getId(), 10, null, null);
			inventory.reserve(product.getId(), variant.getId(), 4, UUID.randomUUID(), false);
			UUID orderId = UUID.randomUUID();

			inventory.commit(product.getId(), variant.getId(), 4, orderId);
			inventory.commit(product.getId(), variant.getId(), 4, orderId);

			assertThat(row().getQuantityOnHand())
					.as("the goods left the building once, not twice")
					.isEqualTo(6);

			assertThat(movements.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("ORDER", orderId))
					.as("a second SALE row for one order would make the log contradict the shelf")
					.hasSize(1);
		}

		@Test
		@DisplayName("a backordered order deducts nothing, because nothing was ever there")
		void backorderDeductsNothing() {
			// Committing a backorder against the shelf would take more units than exist and leave a
			// count nobody can reconcile. The goods were never on it, so the honest position is to
			// deduct nothing and let the shop's restock be the movement that explains the shortfall.
			inventory.restock(product.getId(), variant.getId(), 2, null, null);
			UUID orderId = UUID.randomUUID();
			inventory.reserve(product.getId(), variant.getId(), 5, orderId, true);

			inventory.commit(product.getId(), variant.getId(), 5, orderId);

			assertThat(row().getQuantityOnHand())
					.as("the shop still physically has the 2 units it had")
					.isEqualTo(2);
			assertThat(row().getQuantityReserved()).isZero();

			var orderLog = movements.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
					"ORDER", orderId);
			assertThat(orderLog)
					.as("one row, recorded when the backorder was accepted; the commit itself "
							+ "changed nothing so it logs nothing")
					.hasSize(1);
			assertThat(orderLog.get(0).getDelta())
					.as("a backorder moves no on-hand stock, which is why the row is still worth keeping")
					.isZero();
			assertThat(orderLog.get(0).getNote())
					.contains("Backordered");
		}
	}

	@Nested
	@DisplayName("bulk operations")
	class Bulk {

		@Test
		@DisplayName("reserveAll is all-or-nothing by virtue of one transaction")
		void reserveAllIsAtomic() {
			Product cheap = fixtures.createProduct(shop, "Cheap", ProductCategory.RICE, 100L);
			Product dear = fixtures.createProduct(shop, "Dear", ProductCategory.RICE, 100L);
			fixtures.stock(fixtures.defaultVariantOf(cheap), 10);
			fixtures.stock(fixtures.defaultVariantOf(dear), 1);

			var lines = java.util.List.of(
					new InventoryService.StockLine(cheap.getId(),
							fixtures.defaultVariantOf(cheap).getId(), 5),
					new InventoryService.StockLine(dear.getId(),
							fixtures.defaultVariantOf(dear).getId(), 5));

			assertThatThrownBy(() -> inventory.reserveAll(lines, UUID.randomUUID(), false))
					.isInstanceOf(Inventory.InsufficientStockException.class);
			// The whole call fails, and the outer test transaction rolls back the first line too —
			// a partially reserved basket is worse than a refused one.
		}

		@Test
		@DisplayName("low-stock query finds items at or below their threshold")
		void findsLowStock() {
			inventory.restock(product.getId(), variant.getId(), 2, null, null);
			inventory.setLowStockThreshold(product.getId(), variant.getId(), 5);

			assertThat(inventory.lowStock())
					.extracting(i -> i.getVariantId())
					.contains(variant.getId());
		}

		@Test
		@DisplayName("total available units excludes reserved stock")
		void totalAvailableExcludesReserved() {
			inventory.restock(product.getId(), variant.getId(), 20, null, null);
			inventory.reserve(product.getId(), variant.getId(), 7, UUID.randomUUID(), false);

			assertThat(inventory.totalAvailableUnits(shop.getId()))
					.as("a shop's dashboard must not claim stock that is already promised")
					.isEqualTo(13L);
		}
	}
}
