package com.storefront.store.order;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per shop: the last order number handed out.
 *
 * <p>A tiny entity, but it exists so the allocation is a single row lock rather than a read of
 * the orders table. Taking the lock on the shop's own row serialises that shop's checkouts while
 * leaving every other shop completely unblocked — two shops placing orders at the same instant
 * never wait on each other.
 *
 * <p>Backed by {@code shop_wallet_balance}? No — a separate table with a clear name, so the
 * number sequence is not entangled with the ledger's serialisation behaviour. Mixing them would
 * mean a wallet top-up and an order placement contend on the same row for unrelated reasons.
 *
 * <p>The table is created in migration V6 rather than being derived from the orders table,
 * because "how many orders has this shop had" and "what was the last number" are not the same
 * question once orders can be deleted or imported.
 */
@Entity
@Table(name = "order_number_sequence")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderNumberSequence {

	@Id
	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	/** The highest number issued. Zero means none yet. */
	@Column(name = "last_number", nullable = false)
	private long lastNumber = 0;

	@Column(name = "updated_at", nullable = false)
	private java.time.Instant updatedAt = java.time.Instant.now();

	public OrderNumberSequence(UUID shopId) {
		this.shopId = shopId;
		this.lastNumber = 0;
		this.updatedAt = java.time.Instant.now();
	}

	/**
	 * Increments under the row lock taken by {@code SELECT … FOR UPDATE}.
	 *
	 * <p>The lock is what makes this correct: two concurrent callers block on this row, so the
	 * second one reads the first one's incremented value rather than the same one.
	 */
	public long increment() {
		this.lastNumber++;
		this.updatedAt = java.time.Instant.now();
		return this.lastNumber;
	}

	/** Raises the counter, never lowers it. Importing historical orders must not reuse numbers. */
	public void raiseTo(long value) {
		if (value > lastNumber) {
			this.lastNumber = value;
			this.updatedAt = java.time.Instant.now();
		}
	}
}
