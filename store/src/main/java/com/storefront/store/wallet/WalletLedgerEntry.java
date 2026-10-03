package com.storefront.store.wallet;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One immutable movement in a shop's wallet. The ledger is the entire truth (D2).
 *
 * <p>This entity is append-only, and it deliberately does <b>not</b> extend {@code BaseEntity}.
 * That base class carries an {@code updated_at} maintained by a {@code @PreUpdate} callback,
 * which for this table would be actively wrong: a ledger row that changed would mean money
 * moved twice or was quietly rewritten. Declaring {@code createdAt} directly, with no update
 * hook, makes the append-only guarantee structural rather than a matter of discipline.
 *
 * <p>Lombok provides the constructor and accessors. The validation inside the constructor is
 * hand-written and is the point of it: a ledger entry that records nothing, or a negative
 * amount, is worse than a failed request. Lombok's {@code @Builder} is pointed at that
 * constructor, so a fluent builder still cannot bypass the checks.
 *
 * <p>Three columns together make double-charging impossible:
 * {@link #idempotencyKey} is UNIQUE, {@link #amountMinor} must be positive with the sign
 * carried by {@link #direction}, and {@link #balanceAfterMinor} records the running balance so
 * a reader never has to replay the whole ledger to see where a shop stands.
 */
@Entity
@Table(name = "wallet_ledger_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletLedgerEntry {

	/** Why money moved. Adding a value here is a schema change, deliberately. */
	public enum EntryType {
		TOPUP_APPROVED,
		COMMISSION_DEBIT,
		COMMISSION_REVERSAL,
		SUBSCRIPTION_CHARGE,
		ADJUSTMENT_CREDIT,
		ADJUSTMENT_DEBIT,
		REFUND_DEBIT,
		PAYOUT
	}

	/**
	 * The sign. A separate column rather than a signed amount, so a debit can never be
	 * confused with a reversal, and so a "negative credit" bug cannot be represented at all.
	 */
	public enum Direction {
		CREDIT, DEBIT
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false)
	private UUID shopId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false)
	private EntryType entryType;

	@Enumerated(EnumType.STRING)
	@Column(name = "direction", nullable = false)
	private Direction direction;

	/** Always positive. The schema enforces {@code amount_minor > 0}. */
	@Column(name = "amount_minor", nullable = false)
	private long amountMinor;

	/** The shop's balance immediately after this entry. */
	@Column(name = "balance_after_minor", nullable = false)
	private long balanceAfterMinor;

	@Column(name = "order_id")
	private UUID orderId;

	@Column(name = "payment_transaction_id")
	private UUID paymentTransactionId;

	/** Set on a reversal, pointing at the debit being reversed. */
	@Column(name = "reversal_of")
	private UUID reversalOf;

	/**
	 * UNIQUE in the database. This single constraint is what makes "exactly once" true: two
	 * concurrent accepts of the same order both try to insert with the same key, and one loses.
	 */
	@Column(name = "idempotency_key", nullable = false, unique = true)
	private String idempotencyKey;

	@Column(name = "description", nullable = false)
	private String description;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	/**
	 * Free-form JSON attached to the entry.
	 *
	 * <p>Kept as a String rather than a typed map, because nothing in the ledger reads it
	 * programmatically — it exists for an operator investigating a charge, and a type would
	 * suggest a schema that is not enforced anywhere.
	 *
	 * <p>The {@code JdbcTypeCode} is required: a bare {@code columnDefinition = "jsonb"} tells
	 * Hibernate the column type for DDL, but the value is still bound as VARCHAR and Postgres
	 * rejects that.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata", columnDefinition = "jsonb")
	private String metadata;

	/**
	 * Set by a field initialiser rather than in the constructor, so no construction path can
	 * forget it. A NOT NULL column with no default turns a forgotten assignment into
	 * "null value in column created_at" at flush time, well away from the cause.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/**
	 * The only way to build an entry, and where every invariant is enforced.
	 *
	 * <p>Lombok's {@code @Builder} on a constructor generates the fluent builder that calls
	 * this, so the builder cannot be used to skip a check.
	 */
	@Builder
	protected WalletLedgerEntry(UUID shopId, EntryType entryType, Direction direction,
			long amountMinor, long balanceAfterMinor, UUID orderId, UUID paymentTransactionId,
			UUID reversalOf, String idempotencyKey, String description, UUID actorUserId,
			String metadata) {

		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (entryType == null) {
			throw new IllegalArgumentException("entryType is required");
		}
		if (direction == null) {
			throw new IllegalArgumentException("direction is required");
		}
		if (amountMinor <= 0) {
			// A zero-value entry records a movement that did not happen, which makes every
			// later reconciliation diff harder to read.
			throw new IllegalArgumentException("Ledger amount must be positive: " + amountMinor);
		}
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("An idempotency key is required on every ledger entry");
		}
		if (description == null || description.isBlank()) {
			// A ledger a human cannot read is a ledger nobody can audit.
			throw new IllegalArgumentException("Every entry needs a human-readable description");
		}

		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.entryType = entryType;
		this.direction = direction;
		this.amountMinor = amountMinor;
		this.balanceAfterMinor = balanceAfterMinor;
		this.orderId = orderId;
		this.paymentTransactionId = paymentTransactionId;
		this.reversalOf = reversalOf;
		this.idempotencyKey = idempotencyKey;
		this.description = description;
		this.actorUserId = actorUserId;
		this.metadata = metadata;
	}

	/** Signed value for summing. The only place a sign is applied to an amount. */
	public long signedMinor() {
		return direction == Direction.CREDIT ? amountMinor : -amountMinor;
	}

	public boolean isCredit() {
		return direction == Direction.CREDIT;
	}
}
