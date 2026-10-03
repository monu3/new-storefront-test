package com.storefront.store.notification;

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
 * One notification to one recipient.
 *
 * <p>Written by the email worker from a consumed event, never directly by the order flow. The
 * order's transaction records the <em>intent</em> to notify (an outbox row); this table records
 * what was actually sent. Keeping them apart means "we meant to email you" and "we emailed you"
 * are separately answerable, which is the question every support ticket about a missing
 * confirmation actually is.
 *
 * <p>{@link #status} is tracked per notification rather than assumed from the queue, because a
 * message that was consumed and then failed to send is exactly the case that otherwise vanishes.
 */
@Entity
@Table(name = "notification")
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
public class Notification {

	/** Field initialiser rather than {@code @PrePersist}: see the note on BaseEntity. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/** Who it is for. */
	public enum Audience {
		SHOP,
		CUSTOMER,
		ADMIN
	}

	/** How it goes out. PUSH arrives in Phase 5. */
	public enum Channel {
		EMAIL,
		PUSH,
		IN_APP
	}

	public enum Status {
		PENDING,
		SENT,
		FAILED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "audience", nullable = false)
	private Audience audience;

	@Column(name = "recipient_user_id")
	private UUID recipientUserId;

	@Column(name = "recipient_shop_id")
	private UUID recipientShopId;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false)
	private Channel channel;

	@Column(name = "type", nullable = false)
	private String type;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "body", nullable = false)
	private String body;

	/** Structured detail, so a future push notification need not parse the body text. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "data", columnDefinition = "jsonb")
	private String data;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.PENDING;

	@Column(name = "attempts", nullable = false)
	private int attempts = 0;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "read_at")
	private Instant readAt;

	public Notification(Audience audience, UUID recipientUserId, UUID recipientShopId,
			Channel channel, String type, String title, String body) {

		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("A notification needs a title");
		}
		if (body == null || body.isBlank()) {
			throw new IllegalArgumentException("A notification needs a body");
		}
		if (recipientUserId == null && recipientShopId == null) {
			// A notification addressed to nobody is a silent no-op that looks like a working
			// pipeline, so it is refused rather than stored.
			throw new IllegalArgumentException("A notification needs a recipient");
		}

		this.id = UUID.randomUUID();
		this.audience = audience;
		this.recipientUserId = recipientUserId;
		this.recipientShopId = recipientShopId;
		this.channel = channel;
		this.type = type;
		this.title = title;
		this.body = body;
		this.status = Status.PENDING;
	}

	public void markSent() {
		this.status = Status.SENT;
		this.sentAt = Instant.now();
		this.lastError = null;
	}

	public void markFailed(String error) {
		this.status = Status.FAILED;
		this.attempts++;
		this.lastError = error == null ? "unknown error"
				: error.substring(0, Math.min(error.length(), 1000));
	}

	public void markRead() {
		this.readAt = Instant.now();
	}

	public boolean isRead() {
		return readAt != null;
	}
}
