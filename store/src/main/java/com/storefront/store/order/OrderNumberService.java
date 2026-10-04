package com.storefront.store.order;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Allocates per-shop order numbers.
 *
 * <p><b>Numbers are per shop, not global.</b> Two shops can each have ORD-0001. A shop's own
 * customers see its numbers on printed invoices and in WhatsApp messages, so a global counter
 * would leak each shop's volume to its competitors and produce numbers nobody reads. The
 * uniqueness constraint is {@code UNIQUE (shop_id, order_number)} — the pair, not the number.
 *
 * <p><b>The counter is a row lock, not {@code MAX() + 1}.</b> {@code MAX + 1} is the classic
 * way to get duplicate order numbers: two checkouts in the same millisecond both read 41 and both
 * write ORD-0042, and one customer receives an order number that appears on two orders. A
 * {@code SELECT … FOR UPDATE} on the shop's counter row serialises allocation, and the
 * {@code UNIQUE (shop_id, order_number)} constraint then makes any remaining collision impossible
 * rather than merely unlikely.
 *
 * <p><b>Allocation joins the caller's transaction, and that is the better arrangement.</b> This
 * originally used {@code REQUIRES_NEW} so that a failed checkout would still consume a number,
 * reasoning that gaps in a shop's sequence look to an owner like lost orders. Two things are
 * wrong with that:
 *
 * <ul>
 *   <li>It needs a <em>second database connection per checkout</em>, held open across the whole
 *       order transaction. On a busy shop that is a pool exhausted for no benefit.</li>
 *   <li>It cannot see the caller's uncommitted writes. A checkout inside a transaction that
 *       created the shop in the same transaction fails its foreign key, because the suspended
 *       transaction sees only committed data.</li>
 * </ul>
 *
 * <p>Allocating in the caller's transaction fixes both. The perceived benefit does not survive
 * scrutiny either: a failed checkout rolls the counter back with everything else, so no gap
 * appears, and the customer's retry gets the same number they would have got anyway. The only
 * scenario that produces a gap is a hard-deleted order, and orders are never hard-deleted — a
 * cancelled order keeps its number, which is what an invoice needs.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderNumberService {

	/** Long enough for a small shop's lifetime, short enough to read out over the phone. */
	private static final int DIGITS = 4;

	private final OrderNumberSequenceRepository sequences;
	private final com.storefront.store.settings.ShopSettingService shopSettings;

	/**
	 * Allocates the next order number for a shop, joining the caller's transaction.
	 *
	 * @return e.g. {@code ORD-0042}
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String next(UUID shopId) {
		ensureCounterExists(shopId);

		// The pessimistic write lock is taken by the query, so a second caller for this shop
		// blocks here until this transaction ends and then reads the incremented value. Nothing
		// else in the method is needed for correctness: no re-read, no retry loop.
		OrderNumberSequence counter = sequences.lock(shopId)
				.orElseThrow(() -> new IllegalStateException(
						"Order number counter missing for shop " + shopId));

		long issued = counter.increment();

		// The owner may choose the format (Phase 4). Read after the increment so an unwritable or
		// absent settings row cannot fail the allocation: the number is already committed to this
		// shop's sequence and refusing here would burn it for nothing.
		var format = shopSettings.orderNumber(shopId);
		return format.render(issued, Instant.now());
	}

	/**
	 * The legacy {@code ORD-0001} format.
	 *
	 * <p>Kept for seeding historical orders and for any caller that must not depend on a settings
	 * row existing.
	 */
	String legacyNumber(long issued) {
		return "ORD-" + String.format(Locale.ROOT, "%0" + DIGITS + "d", issued);
	}

	/**
	 * Creates the counter row if this is the shop's first order.
	 *
	 * <p>Created lazily rather than at sign-up, so a shop that never sells anything needs no
	 * counter row. Two simultaneous first orders cannot both insert: the unique primary key makes
	 * the loser fail, and it then locks the row the winner created.
	 */
	private void ensureCounterExists(UUID shopId) {
		if (sequences.lock(shopId).isPresent()) {
			return;
		}
		try {
			sequences.saveAndFlush(new OrderNumberSequence(shopId));
		} catch (DataIntegrityViolationException e) {
			// Another transaction inserted it first. Its row is as good as ours.
			log.debug("Order number counter for {} created concurrently", shopId);
		}
	}

	/**
	 * Raises a shop's counter, for a test or a migration seeding historical orders.
	 *
	 * <p>Never lowers it: reissuing a number that already appears on a printed invoice is
	 * unrecoverable, whereas a gap is cosmetic.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserveThrough(UUID shopId, long lastNumber) {
		ensureCounterExists(shopId);
		sequences.raiseTo(shopId, lastNumber, java.time.Instant.now());
	}
}
