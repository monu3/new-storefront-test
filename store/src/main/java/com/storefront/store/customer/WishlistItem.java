package com.storefront.store.customer;

import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One product a customer has saved (Phase 5).
 *
 * <p><b>No variant.</b> A wishlist saves a product; the customer picks a size or colour when they
 * actually order. Pinning a variant would make the row undeletable once that variant is retired,
 * and would make the list stop meaning "things I might buy".
 *
 * <p>{@code UNIQUE (user_id, product_id)} means saving the same product twice is a no-op rather
 * than a duplicate row, which is what a customer's second tap on a wishlist heart produces.
 */
@Entity
@Table(name = "wishlist_item")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WishlistItem extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	public WishlistItem(UUID userId, UUID productId) {
		if (userId == null || productId == null) {
			throw new IllegalArgumentException("A wishlist item needs a user and a product");
		}
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.productId = productId;
	}
}