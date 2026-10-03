package com.storefront.store.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One attempt to pay for an order.
 *
 * <p><b>There is one row per attempt, not one per order.</b> A customer who fails a bank transfer
 * and tries again has two attempts against the same order, and the first must remain visible:
 * "we have your money" disputes are settled by what the gateway actually said, and a single
 * overwritten row destroys that evidence.
 *
 * <p>{@link #idempotencyKey} is UNIQUE and is what makes a retried request a no-op rather than a
 * second charge. The key is derived from the order and the attempt, so a double-clicked Pay
 * button produces one row.
 *
 * <p>{@link #requestPayload} keeps the raw gateway response. It is the raw bytes for the same
 * reason the ledger is append-only: when a customer and a shop disagree about what a gateway
 * said, a summarised row settles nothing.
 */
@Entity
@Table(name = "payment_transaction")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
/**
 * <p><b>Does not extend {@code BaseEntity}</b>, unlike most entities here. Its table carries
 * {@code created_at} but no {@code updated_at}, and that is correct rather than an oversight:
 * this row is written once and never edited. A {@code created_at} that answers "when did this
 * happen" is the whole story; an {@code updated_at} would always equal it and invite a reader to
 * think the row had been changed since.
 */
public class PaymentTransaction {

	/** Field initialiser rather than {@code @PrePersist}: see the note on BaseEntity. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/** The gateway that handled it. */
	public enum Gateway {
		ESEWA,
		COD,
		BANK,
		/** Set by a human in the console, for a bank transfer they confirmed. */
		MANUAL
	}

	/** Where it got to. */
	public enum GatewayStatus {
		PENDING,
		COMPLETED,
		FAILED,
		REFUNDED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false)
	private PaymentGateway.ShopOrderPaymentMethod method;

	@Column(name = "amount_minor", nullable = false)
	private long amountMinor;

	@Enumerated(EnumType.STRING)
	@Column(name = "gateway", nullable = false)
	private Gateway gateway;

	@Column(name = "gateway_ref")
	private String gatewayRef;

	@Enumerated(EnumType.STRING)
	@Column(name = "gateway_status", nullable = false)
	private GatewayStatus gatewayStatus = GatewayStatus.PENDING;

	/** The raw response, kept for disputes. Not parsed, not summarised. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "request_payload", columnDefinition = "jsonb")
	private String requestPayload;

	@Column(name = "verified_at")
	private Instant verifiedAt;

	@Column(name = "verified_by")
	private UUID verifiedBy;

	@Column(name = "receipt_media_id")
	private UUID receiptMediaId;

	/** UNIQUE. Derived from the order and attempt, so a retry is a no-op. */
	@Column(name = "idempotency_key", nullable = false, unique = true)
	private String idempotencyKey;

	public PaymentTransaction(UUID orderId, UUID shopId, PaymentGateway.ShopOrderPaymentMethod method,
			Gateway gateway, long amountMinor, String gatewayRef, String idempotencyKey) {

		if (orderId == null || shopId == null) {
			throw new IllegalArgumentException("A payment belongs to an order and a shop");
		}
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException(
					"An idempotency key is required: without it a retried payment is a second charge");
		}
		if (amountMinor <= 0) {
			throw new IllegalArgumentException("A payment must be for a positive amount: " + amountMinor);
		}

		this.id = UUID.randomUUID();
		this.orderId = orderId;
		this.shopId = shopId;
		this.method = method;
		this.gateway = gateway;
		this.amountMinor = amountMinor;
		this.gatewayRef = gatewayRef;
		this.idempotencyKey = idempotencyKey;
		this.gatewayStatus = GatewayStatus.PENDING;
	}

	/** Records a completed gateway response, with when and by whom it was verified. */
	public void markCompleted(String gatewayRef, UUID verifiedBy, String rawPayload) {
		this.gatewayStatus = GatewayStatus.COMPLETED;
		if (gatewayRef != null && !gatewayRef.isBlank()) {
			this.gatewayRef = gatewayRef;
		}
		this.verifiedAt = Instant.now();
		this.verifiedBy = verifiedBy;
		this.requestPayload = rawPayload;
	}

	/**
	 * Attaches the gateway's reference for this attempt.
	 *
	 * <p>Separate from {@link #markCompleted} because a bank transfer's reference exists the
	 * moment the customer is shown the account details, long before the money arrives.
	 */
	public void attachGatewayRef(String gatewayRef) {
		if (gatewayRef != null && !gatewayRef.isBlank()) {
			this.gatewayRef = gatewayRef;
		}
	}

	public void markFailed(String reason, String rawPayload) {
		this.gatewayStatus = GatewayStatus.FAILED;
		this.requestPayload = rawPayload;
	}

	public boolean isCompleted() {
		return gatewayStatus == GatewayStatus.COMPLETED;
	}

	/**
	 * The idempotency key for an order's payment attempt.
	 *
	 * <p>Attempt 1 has no suffix, so the first attempt's key stays stable even as later attempts
	 * are added. A key that changed per attempt would make the first one unhittable and defeat the
	 * point.
	 */
	public static String keyFor(UUID orderId, int attempt) {
		return attempt <= 1
				? "payment:" + orderId
				: "payment:" + orderId + ":" + attempt;
	}
}
