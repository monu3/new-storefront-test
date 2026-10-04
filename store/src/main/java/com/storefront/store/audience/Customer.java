package com.storefront.store.audience;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One customer of one shop, as that shop knows them.
 *
 * <p><b>This is not a platform-level customer record.</b> It is scoped to a single shop and holds
 * that shop's view: how much this person has spent <em>here</em>, how many orders, when they last
 * ordered. The same person ordering from two shops is two rows, because "spend at this shop" is
 * the only question the audience screen asks and the only number that would be meaningful.
 *
 * <p><b>Created by checkout, not by sign-up.</b> A row appears the first time someone orders. This
 * is why {@link #userId} is nullable: most of a small shop's customers check out as guests, and a
 * non-null user id would mean the majority of a shop's audience is invisible to it.
 *
 * <p><b>{@code email} is the natural key.</b> {@code UNIQUE (shop_id, email)}, and case-insensitive
 * by virtue of the citext column, because two spellings of one address must not become two
 * customers with split spend history.
 *
 * <p><b>{@link #notes} is shop-private and never leaves this table.</b> It is an owner's own
 * annotation — "always asks for delivery after 6pm" — and is excluded from every customer-facing
 * response by construction, because the only view that reads it is {@link AudienceService}'s owner
 * view. There is deliberately no public accessor path from this row to a customer response.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	/** Null for a guest checkout, which is the common case for a small shop. */
	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "email", nullable = false, columnDefinition = "citext")
	private String email;

	@Column(name = "full_name")
	private String fullName;

	@Column(name = "phone")
	private String phone;

	@Column(name = "total_orders", nullable = false)
	private int totalOrders = 0;

	@Column(name = "total_spent_minor", nullable = false)
	private long totalSpentMinor = 0;

	@Column(name = "last_order_at")
	private Instant lastOrderAt;

	/** Shop-private. Never returned to the customer. */
	@Column(name = "notes")
	private String notes;

	/** When an owner last edited {@link #notes}. Null until they do. */
	@Column(name = "private_note_updated_at")
	private Instant privateNoteUpdatedAt;

	/** Who last edited the note, for shops that will grow a team later (there is no staff role yet). */
	@Column(name = "private_note_updated_by")
	private UUID privateNoteUpdatedBy;

	public Customer(UUID shopId, String email) {
		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("A customer needs an email: it is their natural key");
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.email = email.trim();
	}

	/** Builds the display name a shop would recognise, from whatever the checkout gave us. */
	public String displayName() {
		if (fullName != null && !fullName.isBlank()) {
			return fullName.trim();
		}
		// An email local part beats the raw address on a list an owner scans quickly.
		int at = email.indexOf('@');
		return at > 0 ? email.substring(0, at) : email;
	}

	/** Average order value in minor units, or 0 for a customer who has never ordered. */
	public long averageOrderMinor() {
		return totalOrders == 0 ? 0 : totalSpentMinor / totalOrders;
	}

	/** Whether this customer has ever placed an order here. */
	public boolean hasOrdered() {
		return totalOrders > 0;
	}

	/** Replaces the private note and stamps who wrote it and when. */
	public void annotate(String newNotes, UUID authorId) {
		this.notes = newNotes == null || newNotes.isBlank() ? null : newNotes.trim();
		this.privateNoteUpdatedBy = authorId;
		this.privateNoteUpdatedAt = this.notes == null ? null : Instant.now();
	}

	/**
	 * True when this row has no orders and no identity worth keeping.
	 *
	 * <p>Used only by a cleanup job, never by a request path. A customer with no orders may still
	 * have an account and a saved address, and deleting them because a basket was abandoned would
	 * destroy something the customer did deliberately.
	 */
	public boolean isEmpty() {
		return totalOrders == 0 && userId == null && (notes == null || notes.isBlank());
	}
}