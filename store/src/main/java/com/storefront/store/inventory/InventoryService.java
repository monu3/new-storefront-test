package com.storefront.store.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stock: the single place quantities are read and written.
 *
 * <p><b>The overselling guard is a compare-and-set, not a read-then-write.</b> Two customers
 * ordering the last unit at the same moment both read {@code available == 1} and both succeed,
 * and the shop ends up owing one of them stock it does not have. So the reservation is a single
 * UPDATE whose WHERE clause states the precondition:
 *
 * <pre>
 * UPDATE inventory SET quantity_reserved = quantity_reserved + :qty
 *  WHERE product_id = :p AND variant_id = :v
 *    AND quantity_on_hand - quantity_reserved >= :qty
 * </pre>
 *
 * <p>If it affects no rows, someone else got there first and the caller gets an
 * {@link Inventory.InsufficientStockException} rather than an oversell. The database's
 * {@code quantity_reserved <= quantity_on_hand} constraint is the backstop underneath.
 *
 * <p><b>Reservation lifecycle.</b> Placed → reserved. Accepted → committed (on-hand drops).
 * Cancelled before acceptance → released. Once committed the units are gone and a cancellation
 * cannot bring them back; that is Phase 8's return workflow, and pretending otherwise here would
 * make the stock numbers disagree with the shelf.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryService {

	private final InventoryRepository inventory;
	private final InventoryMovementRepository movements;

	// ------------------------------------------------------------------ reads

	/**
	 * How many units of a variant could be sold right now.
	 *
	 * <p>Zero when there is no stock row at all, which is the right answer for a product that
	 * does not track inventory.
	 */
	@Transactional(readOnly = true)
	public int available(UUID productId, UUID variantId) {
		return inventory.findByIdProductIdAndIdVariantId(productId, variantId)
				.map(Inventory::available)
				.orElse(0);
	}

	@Transactional(readOnly = true)
	public Optional<Inventory> find(UUID productId, UUID variantId) {
		return inventory.findByIdProductIdAndIdVariantId(productId, variantId);
	}

	@Transactional(readOnly = true)
	public List<Inventory> forProduct(UUID productId) {
		return inventory.findByIdProductId(productId);
	}

	@Transactional(readOnly = true)
	public List<Inventory> lowStock() {
		return inventory.findLowStock();
	}

	@Transactional(readOnly = true)
	public long totalAvailableUnits(UUID shopId) {
		return inventory.totalAvailableUnits(shopId);
	}

	@Transactional(readOnly = true)
	public Page<InventoryMovement> movements(UUID productId, UUID variantId, Pageable pageable) {
		return movements.findByProductIdAndVariantIdOrderByCreatedAtDesc(productId, variantId, pageable);
	}

	/** Everything that happened to one order's stock, for an audit of that order. */
	@Transactional(readOnly = true)
	public List<InventoryMovement> movementsForOrder(UUID orderId) {
		return movements.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("ORDER", orderId);
	}

	// ------------------------------------------------------------------ restocking

	/** Creates the stock row if it is missing, then adds units. */
	@Transactional
	public Inventory restock(UUID productId, UUID variantId, int units, UUID actorUserId, String note) {
		if (units <= 0) {
			throw new IllegalArgumentException("Restock amount must be positive: " + units);
		}
		Inventory row = openRow(productId, variantId);
		row.adjustOnHand(units);
		// The note travels with the movement. An earlier version accepted one here and dropped it,
		// so "why did stock jump by 30 overnight" was unanswerable from the log — which is the
		// whole reason the log exists.
		movements.save(InventoryMovement.restock(productId, variantId, units, actorUserId, note));
		log.info("Restocked {} x{} by {} units", variantId, productId, units);
		return inventory.saveAndFlush(row);
	}

	/**
	 * Sets an absolute count, for a stocktake.
	 *
	 * <p>Logged as an {@code ADJUSTMENT} carrying the difference, so a stocktake that finds 37
	 * where the system said 40 leaves a trace of the 3 that went missing.
	 */
	@Transactional
	public Inventory setOnHand(UUID productId, UUID variantId, int quantity, UUID actorUserId,
			String note) {

		Inventory row = openRow(productId, variantId);
		int delta = quantity - row.getQuantityOnHand();
		row.setOnHand(quantity);
		if (delta != 0) {
			movements.save(InventoryMovement.adjustment(productId, variantId, delta, note, actorUserId));
		}
		return inventory.saveAndFlush(row);
	}

	@Transactional
	public void setLowStockThreshold(UUID productId, UUID variantId, int threshold) {
		Inventory row = openRow(productId, variantId);
		row.setLowStockThreshold(threshold);
		inventory.saveAndFlush(row);
	}

	// ------------------------------------------------------------------ reservation

	/**
	 * Reserves stock for a placed order.
	 *
	 * <p>{@link Propagation#MANDATORY} on purpose: reserving inside the order-creation
	 * transaction is the point. If the order insert later fails, the reservation rolls back with
	 * it, and there is no window where stock is held for an order that does not exist. A shop
	 * cannot be left with phantom reservations by a crashed checkout.
	 *
	 * @param backorderAllowed when true, insufficient stock is tolerated rather than refused
	 *                         (the shop explicitly allows made-to-order)
	 * @throws Inventory.InsufficientStockException when there is not enough and no backorder
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(UUID productId, UUID variantId, int quantity, UUID orderId,
			boolean backorderAllowed) {

		if (quantity <= 0) {
			return;
		}

		int affected = inventory.reserveAtomically(productId, variantId, quantity);
		if (affected == 0) {
			if (backorderAllowed) {
				// Logged rather than silently skipped, so a stocktake later can explain stock that
				// was promised but never existed.
				log.info("Order {} backorders {} x{}", orderId, quantity, variantId);
				movements.save(new InventoryMovement(productId, variantId, 0,
						InventoryMovement.Reason.SALE, "ORDER", orderId,
						"Backordered: insufficient stock at order time", null));
				return;
			}
			int available = available(productId, variantId);
			throw new Inventory.InsufficientStockException(quantity, available);
		}
	}

	/**
	 * Releases a reservation for a cancelled or expired order.
	 *
	 * <p>Idempotent: releasing more than was reserved clamps at zero rather than failing, so a
	 * retried cancellation succeeds. Safe to call for an order that never reserved anything.
	 */
	@Transactional
	public void release(UUID productId, UUID variantId, int quantity, UUID orderId) {
		if (quantity <= 0) {
			return;
		}
		Inventory row = inventory.findByIdProductIdAndIdVariantId(productId, variantId).orElse(null);
		if (row == null) {
			return;
		}
		row.release(quantity);
		inventory.saveAndFlush(row);
		movements.save(InventoryMovement.released(productId, variantId, quantity, orderId));
	}

	/**
	 * Commits a reservation when the shop accepts: the goods have left, so on-hand drops.
	 *
	 * <p>Idempotent by design, in both the shelf and the log: {@link
	 * Inventory#commitReservation} attributes only what is still reserved, so a second call
	 * subtracts nothing and logs nothing. An order accept is the one operation that must be safe
	 * to retry, because shop owners double-click and mobile networks retry.
	 */
	@Transactional
	public void commit(UUID productId, UUID variantId, int quantity, UUID orderId) {
		if (quantity <= 0) {
			return;
		}
		Inventory row = inventory.findByIdProductIdAndIdVariantId(productId, variantId).orElse(null);
		if (row == null) {
			return;
		}
		// commitReservation subtracts only what was reserved, so a retry attributes zero, changes
		// nothing and logs nothing. Both halves matter: suppressing the log while still deducting
		// looks idempotent in the log and destroys stock on the shelf.
		int attributed = row.commitReservation(quantity);
		inventory.saveAndFlush(row);

		if (attributed > 0) {
			movements.save(InventoryMovement.sale(productId, variantId, attributed, orderId));
		}
	}

	/**
	 * Low-stock rows that belong to one shop.
	 *
	 * <p>Filtered in SQL rather than in memory: the low-stock list is a shop's own screen, and a
	 * global query would either leak another shop's variants or require every row to be loaded
	 * and checked.
	 */
	@Transactional(readOnly = true)
	public List<Inventory> lowStockFor(UUID shopId) {
		return inventory.findLowStockForShop(shopId);
	}

	/**
	 * Reserves every line of an order, or none of them.
	 *
	 * <p>All-or-nothing matters: a partially reserved basket is worse than a refused one. The
	 * customer would see three of four items held back with no explanation, and the shop would
	 * have reservations it cannot account for.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserveAll(List<StockLine> lines, UUID orderId, boolean allowBackorder) {
		List<StockLine> staged = new ArrayList<>(lines.size());
		for (StockLine line : lines) {
			staged.add(line);
			reserve(line.productId(), line.variantId(), line.quantity(), orderId, allowBackorder);
		}
		log.debug("Reserved {} lines for order {}", staged.size(), orderId);
	}

	/** Releases every line of an order. Used when a cancellation lands before acceptance. */
	@Transactional
	public void releaseAll(List<StockLine> lines, UUID orderId) {
		for (StockLine line : lines) {
			release(line.productId(), line.variantId(), line.quantity(), orderId);
		}
	}

	/** Commits every line of an order at accept time. */
	@Transactional
	public void commitAll(List<StockLine> lines, UUID orderId) {
		for (StockLine line : lines) {
			commit(line.productId(), line.variantId(), line.quantity(), orderId);
		}
	}

	// ------------------------------------------------------------------ internals

	/** Loads the stock row, creating it at zero when a product has never been stocked. */
	private Inventory openRow(UUID productId, UUID variantId) {
		return inventory.findByIdProductIdAndIdVariantId(productId, variantId)
				.orElseGet(() -> inventory.saveAndFlush(new Inventory(productId, variantId)));
	}

	/**
	 * One order line's stock coordinates.
	 *
	 * <p>A record rather than {@code OrderItem} so this package does not depend on the order
	 * package. Inventory is also written to directly by restocking and by import, neither of
	 * which has an order.
	 */
	public record StockLine(UUID productId, UUID variantId, int quantity) {
	}
}
