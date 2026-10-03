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
}
