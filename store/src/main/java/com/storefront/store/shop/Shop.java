package com.storefront.store.shop;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.catalog.CategoryValidator;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.common.BaseEntity;
import com.storefront.store.identity.AppUser;

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
 * A shop, owned by exactly one person.
 *
 * <p>One owner per shop, not a team: there is no staff role in the Keycloak realms (D3), so
 * {@code owner_user_id} is UNIQUE and is the authority on who may act for a shop. Ownership is
 * deliberately <em>not</em> a Keycloak concern — a token says who you are, Postgres says whose
 * shop it is.
 *
 * <p>{@link #shopType} is a hardcoded enum code, validated in Java by {@link CategoryValidator}
 * rather than by a foreign key. There is no category table (D17/D18), so adding a vertical is a
 * code change and a redeploy.
 *
 * <p>Lombok is used for the accessors and the {@link Status} and {@link CommissionPlan} enums,
 * which are pure data. The lifecycle methods below are hand-written because they carry rules:
 * a Lombok-generated setter would let any caller skip the invariants those methods enforce.
 */
@Entity
@Table(name = "shop")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shop extends BaseEntity {

	/**
	 * Lifecycle. A shop is invisible in the gallery until it is VERIFIED, which is the gate that
	 * keeps fake shops out (plan Phase 1, item 3).
	 */
	public enum Status {
		DRAFT,
		PENDING_REVIEW,
		VERIFIED,
		REJECTED,
		SUSPENDED
	}

	/** Whether the platform takes commission. Mirrors the active subscription (D4). */
	public enum CommissionPlan {
		/** 5% at accept. */
		FREE,
		/** Zero commission. */
		SUBSCRIBED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, unique = true)
	private UUID ownerUserId;

	// citext: case-insensitive, so a slug lookup matches however it was typed or capitalised.
	@Column(name = "slug", nullable = false, unique = true, columnDefinition = "citext")
	private String slug;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description")
	private String description;

	@Column(name = "shop_type", nullable = false)
	private String shopType;

	@Column(name = "logo_url")
	private String logoUrl;

	@Column(name = "favicon_url")
	private String faviconUrl;

	@Column(name = "cover_url")
	private String coverUrl;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.DRAFT;

	@Column(name = "verification_note")
	private String verificationNote;

	@Column(name = "verified_at")
	private Instant verifiedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "commission_plan", nullable = false)
	private CommissionPlan commissionPlan = CommissionPlan.FREE;

	@Column(name = "follower_count", nullable = false)
	private int followerCount = 0;

	@Column(name = "published_at")
	private Instant publishedAt;

	public Shop(AppUser owner, String name, ShopType type) {
		this.id = UUID.randomUUID();
		this.ownerUserId = owner.getId();
		this.name = name;
		this.shopType = type.name();
		this.status = Status.DRAFT;
		this.commissionPlan = CommissionPlan.FREE;
		this.slug = SlugGenerator.from(name, this.id);
	}

	/** The raw code. Use {@link #getType()} for the validated enum. */
	public ShopType getType() {
		return ShopType.fromCode(shopType);
	}

	/**
	 * Changes the shop's vertical.
	 *
	 * <p>Deliberately does not check the caller. The rule that a change must not strand a
	 * product lives in {@code ShopTypeMigrationService}, which is the only thing that should
	 * call this; a setter that silently validated would give the same method two very different
	 * behaviours depending on who called it.
	 */
	public void changeType(ShopType newType) {
		if (newType == null) {
			throw new IllegalArgumentException("Shop type is required");
		}
		this.shopType = newType.name();
	}

	/**
	 * True when the shop is publicly visible.
	 *
	 * <p>Named {@code isVerified} rather than relying on a Lombok-generated
	 * {@code isVerified()} for a status field, because "verified" is a real domain question the
	 * gallery and the storefront both ask. Lombok cannot infer it from a {@code Status} enum, so
	 * it is written out.
	 */
	public boolean isVerified() {
		return status == Status.VERIFIED;
	}

	/** True when the shop pays no commission, i.e. it is on a paid subscription (D4). */
	public boolean paysCommission() {
		return commissionPlan == CommissionPlan.FREE;
	}

	/** Admin approval. Publishing is what makes the shop visible in the gallery. */
	public void verify() {
		this.status = Status.VERIFIED;
		this.verifiedAt = Instant.now();
		if (this.publishedAt == null) {
			this.publishedAt = Instant.now();
		}
	}

	public void reject(String reason) {
		this.status = Status.REJECTED;
		this.verificationNote = reason;
	}

	public void submitForReview() {
		this.status = Status.PENDING_REVIEW;
	}

	public void suspend(String reason) {
		this.status = Status.SUSPENDED;
		this.verificationNote = reason;
	}

	/**
	 * Atomically increments the follower counter (D13).
	 *
	 * <p>Kept for single-threaded callers; the repository's atomic {@code UPDATE} is preferred
	 * under concurrency, because a read-modify-write here would lose a count when two customers
	 * follow at the same moment.
	 */
	public void incrementFollowers() {
		this.followerCount++;
	}
}
