package com.storefront.store.social;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One customer following one shop.
 *
 * <p>Deliberately minimal. D13 scopes followers to a counter: no feed, no ranking, no rewards. The
 * platform needs to know how many people follow a shop so a shop owner can see whether their
 * effort is reaching anyone, and that is the whole feature. Building a follower graph here would
 * be building Phase 9's social product in a table that has to be migrated twice.
 *
 * <p>The composite primary key is what makes "follow" idempotent: following twice is the same
 * row, so the caller cannot produce a duplicate. Combined with the counter update in
 * {@link FollowerService}, which is conditional on this row actually being inserted, the count
 * and the set of rows cannot disagree.
 */
@Entity
@Table(name = "shop_follower")
@IdClass(ShopFollower.Key.class)
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
public class ShopFollower {

	@Id
	@Column(name = "shop_id", nullable = false)
	private UUID shopId;

	@Id
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	/** Field initialiser rather than {@code @PrePersist}: see the note on BaseEntity. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	public ShopFollower(UUID shopId, UUID userId) {
		if (shopId == null || userId == null) {
			throw new IllegalArgumentException("A follower needs both a shop and a user");
		}
		this.shopId = shopId;
		this.userId = userId;
	}

	/**
	 * The composite key.
	 *
	 * <p>Named {@code Key} rather than {@code Id} because a nested type called {@code Id}
	 * shadows the {@code @Id} annotation inside this class, and the compiler silently resolves
	 * one for the other.
	 */
	public record Key(UUID shopId, UUID userId) {
	}
}
