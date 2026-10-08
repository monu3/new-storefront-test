package com.storefront.store.wallet;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.storefront.store.wallet.WalletLedgerEntry.Direction;
import com.storefront.store.wallet.WalletLedgerEntry.EntryType;

/**
 * The only place a shop's wallet balance changes.
 *
 * <p>Two invariants live here, and they are why this is a service rather than a repository
 * method (plan risk R1):
 *
 * <ol>
 *   <li><b>The cache and the ledger move together.</b> Both writes share one transaction, so a
 *       crash cannot leave them disagreeing. The nightly reconciliation is a backstop, not the
 *       mechanism.</li>
 *   <li><b>One key, one entry.</b> {@code idempotency_key} is UNIQUE, so a retried or concurrent
 *       accept cannot debit twice.</li>
 * </ol>
 *
 * <p>The balance is moved with a single atomic {@code UPDATE balance = balance + delta} rather
 * than a read-modify-write on the entity. A pessimistic row lock is held for the duration
 * regardless, because the affordability check is a read that must not be stale.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalletService {

	private final WalletLedgerRepository ledgerRepository;
	private final WalletBalanceRepository balanceRepository;

	/**
	 * Current balance, opening a zero balance on first sight.
	 *
	 * <p>The row is created with {@code saveAndFlush} so it exists before any {@code UPDATE}
	 * against it in the same transaction.
	 */
	@Transactional
	public long balanceOf(UUID shopId) {
		return balanceRepository.findByShopId(shopId)
				.map(ShopWalletBalance::getBalanceMinor)
				.orElseGet(() -> {
					log.info("Opening a zero wallet for shop {}", shopId);
					ShopWalletBalance opened = balanceRepository.saveAndFlush(new ShopWalletBalance(shopId));
					return opened.getBalanceMinor();
				});
	}

	@Transactional(readOnly = true)
	public Optional<Long> balanceMinorOf(UUID shopId) {
		return balanceRepository.findByShopId(shopId).map(ShopWalletBalance::getBalanceMinor);
	}

	/**
	 * Records a debit, refusing it when the shop cannot afford it.
	 *
	 * <p>Called when a shop accepts an order. The affordability check is the mechanism behind
	 * "shops must credit money before accepting any order" (D2).
	 *
	 * @param idempotencyKey derived from the order, so a retry is a no-op
	 * @throws InsufficientWalletBalanceException when the balance is short
	 */
	@Transactional(isolation = Isolation.SERIALIZABLE)
	public WalletLedgerEntry debit(UUID shopId, EntryType type, long amountMinor,
			String idempotencyKey, String description, UUID orderId, UUID actorUserId) {

		Optional<WalletLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			log.info("Wallet debit {} already recorded; returning the original entry", idempotencyKey);
			return existing.get();
		}

		if (amountMinor <= 0) {
			throw new IllegalArgumentException("Debit amount must be positive: " + amountMinor);
		}

		// Open the wallet if this is the shop's first movement, then re-read under a row lock so
		// the affordability check sees a committed value rather than a cached copy.
		balanceOf(shopId);
		long current = lockedBalance(shopId);

		if (current < amountMinor) {
			// A negative balance is permitted in the data model, but a NEW debit is not: that is
			// what stops a shop accepting orders it cannot pay commission on.
			throw new InsufficientWalletBalanceException(shopId, current, amountMinor);
		}

		return append(shopId, type, Direction.DEBIT, amountMinor, idempotencyKey, description,
				orderId, actorUserId, null, current);
	}

	/**
	 * Records a credit.
	 *
	 * <p>Used for top-ups, commission reversals and manual adjustments. Unlike a debit, a credit
	 * has no affordability check — money arriving can never be refused.
	 */
	@Transactional(isolation = Isolation.SERIALIZABLE)
	public WalletLedgerEntry credit(UUID shopId, EntryType type, long amountMinor,
			String idempotencyKey, String description, UUID orderId, UUID actorUserId,
			UUID paymentTransactionId) {

		Optional<WalletLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return existing.get();
		}
		if (amountMinor <= 0) {
			throw new IllegalArgumentException("Credit amount must be positive: " + amountMinor);
		}

		balanceOf(shopId);
		long current = lockedBalance(shopId);

		return append(shopId, type, Direction.CREDIT, amountMinor, idempotencyKey, description,
				orderId, actorUserId, paymentTransactionId, current);
	}

	/**
	 * Debits a subscription charge, linking it to the subscription that caused it.
	 *
	 * <p><b>A separate method rather than a nullable argument on {@link #debit}.</b> The link has to
	 * be set <em>before</em> the row is written: {@link #debit} is itself transactional, so the entry
	 * it returns is detached by the time its caller could set the field, and a mutation on a
	 * detached entity is discarded without complaint. That is not a hypothetical -- it is exactly what
	 * happened, and the resulting ledger had null subscription ids on every subscription charge.
	 *
	 * <p>Adding a parameter to {@code debit} instead would mean nine other call sites passing null,
	 * one of which would eventually pass the wrong subscription.
	 */
	@Transactional(isolation = Isolation.SERIALIZABLE)
	public WalletLedgerEntry debitSubscriptionCharge(UUID shopId, long amountMinor,
			String idempotencyKey, String description, UUID subscriptionId, UUID actorUserId) {

		if (subscriptionId == null) {
			// A charge with no plan behind it cannot be reconciled against MRR, so it must not be
			// writable at all.
			throw new IllegalArgumentException(
					"A subscription charge must name the subscription it is paying for.");
		}

		Optional<WalletLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return existing.get();
		}
		if (amountMinor <= 0) {
			throw new IllegalArgumentException("Charge amount must be positive: " + amountMinor);
		}

		balanceOf(shopId);
		long current = lockedBalance(shopId);
		long newBalance = current - amountMinor;

		WalletLedgerEntry entry = WalletLedgerEntry.builder()
				.shopId(shopId)
				.entryType(EntryType.SUBSCRIPTION_CHARGE)
				.direction(Direction.DEBIT)
				.amountMinor(amountMinor)
				.balanceAfterMinor(newBalance)
				.idempotencyKey(idempotencyKey)
				.description(description)
				.actorUserId(actorUserId)
				.build();

		entry.attachSubscription(subscriptionId);

		return persist(shopId, entry);
	}

	/**
	 * Credits back a previous debit, for a cancellation or refund.
	 *
	 * <p>Idempotent on the original debit's key with a {@code "reversal:"} prefix, so cancelling
	 * the same order twice cannot credit twice. Returns empty when the debit never happened — a
	 * PENDING order cancelled before acceptance owes nothing, and that is not an error.
	 */
	@Transactional(isolation = Isolation.SERIALIZABLE)
	public Optional<WalletLedgerEntry> reverseDebit(UUID shopId, String originalIdempotencyKey,
			String description, UUID orderId, UUID actorUserId) {

		String reversalKey = "reversal:" + originalIdempotencyKey;

		Optional<WalletLedgerEntry> alreadyReversed = ledgerRepository.findByIdempotencyKey(reversalKey);
		if (alreadyReversed.isPresent()) {
			return alreadyReversed;
		}

		Optional<WalletLedgerEntry> original = ledgerRepository.findByIdempotencyKey(originalIdempotencyKey);
		if (original.isEmpty()) {
			log.info("No debit {} to reverse for order {}", originalIdempotencyKey, orderId);
			return Optional.empty();
		}

		WalletLedgerEntry debit = original.get();
		balanceOf(shopId);
		long current = lockedBalance(shopId);

		// The reversal mirrors the original amount, not the current balance. A partial refund
		// reverses part of the debit and leaves the rest, which the ledger shows as two entries.
		WalletLedgerEntry reversal = WalletLedgerEntry.builder()
				.shopId(shopId)
				.entryType(EntryType.COMMISSION_REVERSAL)
				.direction(Direction.CREDIT)
				.amountMinor(debit.getAmountMinor())
				.balanceAfterMinor(current + debit.getAmountMinor())
				.orderId(orderId != null ? orderId : debit.getOrderId())
				.reversalOf(debit.getId())
				.idempotencyKey(reversalKey)
				.description(description)
				.actorUserId(actorUserId)
				.build();

		return Optional.of(persist(shopId, reversal));
	}

	/** True when a debit with this key exists, so callers can skip work. */
	/**
	 * A page of the shop's ledger, newest first.
	 *
	 * <p>Read-only and paged. The ledger is append-only and grows without bound, so the shop's
	 * history screen pages rather than loading everything — and there is deliberately no way to
	 * read it unfiltered, because a shop must only ever see its own entries.
	 */
	@Transactional(readOnly = true)
	public org.springframework.data.domain.Page<WalletLedgerEntry> ledgerFor(UUID shopId,
			org.springframework.data.domain.Pageable pageable) {
		return ledgerRepository.findByShopIdOrderByCreatedAtDesc(shopId, pageable);
	}

	public boolean hasEntry(String idempotencyKey) {
		return ledgerRepository.findByIdempotencyKey(idempotencyKey).isPresent();
	}

	/**
	 * The balance under a row lock, so the affordability check cannot read a value another
	 * transaction is about to change.
	 */
	private long lockedBalance(UUID shopId) {
		return balanceRepository.findByShopIdForUpdate(shopId)
				.map(ShopWalletBalance::getBalanceMinor)
				.orElse(0L);
	}

	/** Writes the ledger entry recording the resulting balance, then moves the cache. */
	private WalletLedgerEntry append(UUID shopId, EntryType type, Direction direction,
			long amountMinor, String idempotencyKey, String description, UUID orderId,
			UUID actorUserId, UUID paymentTransactionId, long currentBalance) {

		long signed = direction == Direction.CREDIT ? amountMinor : -amountMinor;
		long newBalance = currentBalance + signed;

		WalletLedgerEntry entry = WalletLedgerEntry.builder()
				.shopId(shopId)
				.entryType(type)
				.direction(direction)
				.amountMinor(amountMinor)
				.balanceAfterMinor(newBalance)
				.orderId(orderId)
				.paymentTransactionId(paymentTransactionId)
				.idempotencyKey(idempotencyKey)
				.description(description)
				.actorUserId(actorUserId)
				.build();

		return persist(shopId, entry);
	}

	/**
	 * Inserts the entry and moves the cache in the same transaction.
	 *
	 * <p>A unique-constraint violation means a concurrent request won the race for this
	 * idempotency key. That is the expected outcome under concurrency, not a failure: the caller
	 * gets the entry that already exists, and no money moves.
	 */
	private WalletLedgerEntry persist(UUID shopId, WalletLedgerEntry entry) {
		try {
			WalletLedgerEntry saved = ledgerRepository.saveAndFlush(entry);
			// Move the cache by the entry's own signed amount. The balance recorded on the entry
			// is for the reader; the delta is what the cache applies.
			balanceRepository.applyDelta(shopId, entry.signedMinor());
			return saved;
		} catch (DataIntegrityViolationException e) {
			log.info("Idempotency key {} already used; returning the existing entry",
					entry.getIdempotencyKey());
			return ledgerRepository.findByIdempotencyKey(entry.getIdempotencyKey())
					.orElseThrow(() -> e);
		}
	}

	/**
	 * Recomputes a shop's cached balance from its ledger.
	 *
	 * <p>Run nightly. A mismatch is logged at ERROR before being repaired: something wrote the
	 * cache outside this service, and that is worth knowing about even though the ledger means
	 * no money was lost.
	 *
	 * @return true when the cache was already correct
	 */
	@Transactional
	public boolean reconcile(UUID shopId) {
		Long ledgerSum = ledgerRepository.sumSignedMinor(shopId);
		long fromLedger = ledgerSum == null ? 0L : ledgerSum;

		balanceOf(shopId);
		long cached = lockedBalance(shopId);

		if (cached == fromLedger) {
			return true;
		}

		log.error("Wallet cache drift for shop {}: cache={} ledger={}. The ledger is authoritative; "
				+ "repairing the cache.", shopId, cached, fromLedger);
		balanceRepository.reconcileTo(shopId, fromLedger);
		return false;
	}

	/** Signals a shop that cannot cover a charge. Maps to HTTP 409. */
	public static class InsufficientWalletBalanceException extends RuntimeException {

		private final long currentBalanceMinor;
		private final long requiredMinor;

		public InsufficientWalletBalanceException(UUID shopId, long currentBalanceMinor, long requiredMinor) {
			super(("Wallet balance is short: have Rs. %.2f, need Rs. %.2f. Top up the wallet to accept this order.")
					.formatted(currentBalanceMinor / 100.0, requiredMinor / 100.0));
			this.currentBalanceMinor = currentBalanceMinor;
			this.requiredMinor = requiredMinor;
		}

		public long getCurrentBalanceMinor() {
			return currentBalanceMinor;
		}

		public long getRequiredMinor() {
			return requiredMinor;
		}
	}
}
