package com.storefront.store.inventory;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite key for {@link Inventory}: the product and the variant whose stock it is.
 *
 * <p>A surrogate id would be simpler but would need an extra join to answer the only question
 * anyone ever asks of this table — "how many of this variant are left?" The composite key makes
 * that question the primary key lookup.
 *
 * <p>Immutability and correct {@code equals}/{@code hashCode} are not optional: Hibernate uses
 * them to locate an instance already in the persistence context, and a mutable or value-incorrect
 * key produces updates that silently apply to the wrong row.
 */
@Embeddable
public class InventoryId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "product_id", nullable = false)
	private UUID productId;

	@Column(name = "variant_id", nullable = false)
	private UUID variantId;

	protected InventoryId() {
		// For Hibernate.
	}

	public InventoryId(UUID productId, UUID variantId) {
		this.productId = Objects.requireNonNull(productId, "productId");
		this.variantId = Objects.requireNonNull(variantId, "variantId");
	}

	public UUID getProductId() {
		return productId;
	}

	public UUID getVariantId() {
		return variantId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof InventoryId that)) {
			return false;
		}
		return Objects.equals(productId, that.productId) && Objects.equals(variantId, that.variantId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(productId, variantId);
	}

	@Override
	public String toString() {
		return "InventoryId[" + productId + "/" + variantId + "]";
	}
}
