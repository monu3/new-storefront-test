package com.storefront.store.wallet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read access to the wallet ledger.
 *
 * <p>Deliberately has <b>no</b> update or delete method. The ledger is append-only, and leaving
 * the destructive operations off the interface means a caller who wants to "fix" an entry
 * cannot — they must post a reversing entry, which is what an audit trail requires.
 */
public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, UUID> {

	Optional<WalletLedgerEntry> findByIdempotencyKey(String idempotencyKey);

	/**
	 * The signed sum of a shop's ledger: the authoritative balance.
	 *
	 * <p>Computed in the database rather than by loading rows, so reconciliation stays cheap as
	 * a ledger grows.
	 */
	@Query("""
			SELECT COALESCE(SUM(
			    CASE WHEN e.direction = com.storefront.store.wallet.WalletLedgerEntry$Direction.CREDIT
			         THEN e.amountMinor
			         ELSE -e.amountMinor
			    END), 0)
			FROM WalletLedgerEntry e
			WHERE e.shopId = :shopId
			""")
	long sumSignedMinor(@Param("shopId") UUID shopId);

	Page<WalletLedgerEntry> findByShopIdOrderByCreatedAtDesc(UUID shopId, Pageable pageable);

	List<WalletLedgerEntry> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

	Optional<WalletLedgerEntry> findFirstByReversalOf(UUID originalEntryId);

	long countByShopIdAndEntryType(UUID shopId, WalletLedgerEntry.EntryType entryType);

	/**
	 * The gross of one entry type in one direction, since a moment.
	 *
	 * <p><b>Direction-aware and unsigned on purpose.</b> The obvious query -- a signed
	 * {@code CASE direction WHEN CREDIT THEN +amount ELSE -amount END} -- returns a
	 * <em>negative</em> number for a debit-only type, which then fails in {@code Money.ofMinor} with
	 * "amount cannot be negative" and looks like a money bug in the dashboard rather than a sign
	 * convention in a query. A negative commission figure is a real thing (a reversal), and it is
	 * expressed by subtracting two gross numbers, where the subtraction is visible in the code that
	 * is named for it.
	 *
	 * <p>{@code COALESCE} because a type with no rows has no {@code SUM} at all, and "no commission"
	 * is a legitimate answer that must not become an exception.
	 */
	@Query("""
			SELECT COALESCE(SUM(e.amountMinor), 0)
			FROM WalletLedgerEntry e
			WHERE e.entryType = :entryType
			  AND e.direction = :direction
			  AND e.createdAt >= :since
			""")
	long sumByTypeAndDirection(@Param("entryType") WalletLedgerEntry.EntryType entryType,
			@Param("direction") WalletLedgerEntry.Direction direction,
			@Param("since") java.time.Instant since);
}
