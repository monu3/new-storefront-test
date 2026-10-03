package com.storefront.store.wallet;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** Read access to the wallet balance cache. See {@link ShopWalletBalance} on why this is not the truth. */
public interface WalletBalanceRepository extends JpaRepository<ShopWalletBalance, UUID> {

	Optional<ShopWalletBalance> findByShopId(UUID shopId);

	/**
	 * Reads the balance while holding a row lock for the rest of the transaction.
	 *
	 * <p>Used by every write path. Without the lock, two concurrent debits read the same balance,
	 * both pass the affordability check, and the shop ends up further below zero than intended —
	 * an overspend that no per-order idempotency key can prevent, because the two orders are
	 * different.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT b FROM ShopWalletBalance b WHERE b.shopId = :shopId")
	Optional<ShopWalletBalance> findByShopIdForUpdate(@Param("shopId") UUID shopId);

	/**
	 * Applies a signed delta in a single statement.
	 *
	 * <p>Prefer this over mutating the entity and saving: an atomic UPDATE cannot be lost to a
	 * stale in-memory copy, and it sidesteps the merge-versus-persist ambiguity that makes a
	 * read-modify-write on a {@code @Version}ed entity fail under concurrency.
	 *
	 * @return rows updated, 0 when no balance row exists yet
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE ShopWalletBalance b
			SET b.balanceMinor = b.balanceMinor + :delta
			WHERE b.shopId = :shopId
			""")
	int applyDelta(@Param("shopId") UUID shopId, @Param("delta") long delta);

	/**
	 * Overwrites the cached balance with the ledger's own total.
	 *
	 * <p>Reconciliation only. Distinct from {@link #applyDelta} on purpose: this asserts an
	 * absolute value, where the other adds to one.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE ShopWalletBalance b SET b.balanceMinor = :balance WHERE b.shopId = :shopId")
	int reconcileTo(@Param("shopId") UUID shopId, @Param("balance") long balance);
}
