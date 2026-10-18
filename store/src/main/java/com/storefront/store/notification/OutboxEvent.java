package com.storefront.store.notification;

import java.time.Instant;
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
 * An event written in the same transaction as the state change that caused it.
 *
 * <p><b>This table exists to close a window (R5).</b> Publishing to RabbitMQ after a commit has
 * a gap between the two: the database has committed but the message has not been sent, and a crash
 * in that gap leaves an order that exists and a customer who is never emailed. Nothing retries,
 * because nothing knows the message was meant to be sent.
 *
 * <p>Writing the event here and relaying it later closes the window completely. Either the state
 * change and the intent to publish both commit, or neither does. The relay then retries until
 * delivery succeeds, and an event that cannot be delivered stays visible with
 * {@link #getAttempts()} and {@link #getLastError()} rather than disappearing.
 *
 * <p>Rows are never deleted on success — {@link #publishedAt} is set instead. The
 * {@code idx_outbox_unpublished} partial index means the relay's constant polling only touches
 * the small unpublished set.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	/** Aggregate type names, matching what the notification worker routes on. */
	public static final String ORDER = "ORDER";
	public static final String SHOP = "SHOP";
	/** A customer's basket rather than an order (Phase 8, abandoned-cart reminders). */
	public static final String CART = "CART";

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "aggregate_type", nullable = false, updatable = false)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private UUID aggregateId;

	/** e.g. {@code ORDER_PLACED}. The routing key for consumers. */
	@Column(name = "event_type", nullable = false, updatable = false)
	private String eventType;

	/** The JSON body a consumer receives. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, columnDefinition = "jsonb")
	private String payload;

	/** Null until successfully published. The partial index is on this being null. */
	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "attempts", nullable = false)
	private int attempts = 0;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload) {
		if (aggregateType == null || aggregateType.isBlank()) {
			throw new IllegalArgumentException("An event needs an aggregate type, e.g. ORDER");
		}
		if (aggregateId == null) {
			throw new IllegalArgumentException("An event needs an aggregate id");
		}
		if (eventType == null || eventType.isBlank()) {
			throw new IllegalArgumentException("An event needs a type, e.g. ORDER_PLACED");
		}
		if (payload == null || payload.isBlank()) {
			// A consumer receiving an empty body cannot do anything, and discovering that at
			// delivery time turns a programming error into a mysteriously silent notification.
			throw new IllegalArgumentException("An event needs a payload");
		}
		this.id = UUID.randomUUID();
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.createdAt = Instant.now();
	}

	public boolean isPublished() {
		return publishedAt != null;
	}

	/**
	 * Whether to try again.
	 *
	 * <p>After {@link #MAX_ATTEMPTS} the event is left alone. Retrying a permanently rejected
	 * message forever fills the table and logs, and a human needs to see the failure rather than
	 * have it masked by an ever-growing attempt count.
	 */
	public boolean shouldRetry() {
		return !isPublished() && attempts < MAX_ATTEMPTS;
	}

	/** Enough for a transient broker outage, few enough that a poison message is noticed. */
	public static final int MAX_ATTEMPTS = 10;

	public void markPublished() {
		this.publishedAt = Instant.now();
		this.lastError = null;
	}

	/** Records a failed attempt with the reason, so the row explains itself. */
	public void recordFailure(String error) {
		this.attempts++;
		// Truncated: a driver exception can be enormous, and an unbounded error column turns a
		// transient blip into a table that no longer fits.
		this.lastError = error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 1000));
	}
}
