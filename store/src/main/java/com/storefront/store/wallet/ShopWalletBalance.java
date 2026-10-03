package com.storefront.store.wallet;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A shop's wallet balance, maintained as a read cache.
 *
 * <p><b>This is not the source of truth.</b> The truth is {@code SUM(CREDIT amount - DEBIT
 * amount)} over {@link WalletLedgerEntry}. This table exists so the common case — "can this shop
 * accept an order?" — is a primary-key lookup instead of an aggregate over a growing ledger.
 *
 * <p>It is written in the same transaction as the ledger entry that changed it, and
 * {@code WalletService.reconcile} recomputes it from the ledger nightly, logging any drift. Because
 * it is fully re-derivable, a bug here is a performance bug rather than a data-loss bug: drop the
 * table, rebuild it, and nothing is lost.
 *
 * <p>Deliberately <em>not</em> a plain mutable balance. There is no public setter and no
 * {@code @Setter} on this class: the only way to change the balance is the atomic repository
 * update in {@link WalletService}, so a balance can never move without a matching ledger entry.
 * That is why Lombok is used for the accessors but not for setters here.
 */
@Entity
@Table(name = "shop_wallet_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopWalletBalance {

	@Id
	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	/**
	 * Declared explicitly rather than inherited from {@code BaseEntity}, for the same reason as
	 * {@code WalletLedgerEntry}: this row is a cache, not a domain record.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/**
	 * May go negative.
	 *
	 * <p>Blocking a shop from spending into the red would deadlock it: the shop needs to accept
	 * an order to earn, and it cannot earn while frozen. A negative balance is alerted on instead,
	 * so an admin can top the shop up or use the override.
	 */
	@Column(name = "balance_minor", nullable = false)
	private long balanceMinor;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	public ShopWalletBalance(UUID shopId) {
		this.shopId = shopId;
	}

	public boolean isFunded() {
		return balanceMinor > 0;
	}

	/** Whether the shop can absorb a charge of this size without going negative. */
	public boolean canAfford(long amountMinor) {
		return balanceMinor >= amountMinor;
	}
}
