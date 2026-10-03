package com.storefront.store.order;

import java.util.UUID;

import com.storefront.store.catalog.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of an order.
 *
 * <p><b>Everything here is a snapshot.</b> The product name, variant name, SKU, image, price and
 * category are all copied at order time rather than joined at read time. A shop that renames
 * "Sona Masoori Rice" to "Sona Masoori Premium", deletes a discontinued line, or is reclassified
 * into a different vertical must not change what a customer was charged three months ago or what
 * an invoice says. The category snapshot in particular is what makes the D17/phase-1 exit test
 * meaningful: the order records what was sold, not what the catalogue says today.
 *
 * <p>{@link #productId} and {@link #variantId} are nullable and {@code ON DELETE SET NULL}
 * precisely because the row must survive their deletion. They are a convenience for
 * "customers who bought this also bought", never the record of what happened.
 *
 * <p>Per-line {@link #discountMinor} and {@link #taxMinor} exist so that an order total is the
 * sum of its lines exactly. An order-level discount distributed across lines is done with
 * {@code Money.distribute()}, which hands the leftover paisa to the earliest weights so the parts
 * add up by construction rather than by luck. A customer will add up the line totals.
 *
 * <p><b>Does not extend {@code BaseEntity}</b>, unlike most entities here. Its table carries
 * {@code created_at} but no {@code updated_at}, and that is the correct shape rather than an
 * oversight: an order line is a frozen record of what was bought, at what price, on one date.
 * Nothing on it is ever edited after placement, and allowing it to be would be allowing a change
 * to what the customer was charged — which is the exact failure the snapshot exists to prevent.
 * {@code created_at} is also load-bearing: it is what orders the lines the way the customer added
 * them, and the line id is a random UUID that sorts arbitrarily.
 */
@Entity
@Table(name = "order_item")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	/** Null once the product is hard-deleted. Never the record of what was sold. */
	@Column(name = "product_id")
	private UUID productId;

	@Column(name = "variant_id")
	private UUID variantId;

	/** Snapshot. Never read from the product table at display time. */
	@Column(name = "product_name", nullable = false)
	private String productName;

	@Column(name = "variant_name")
	private String variantName;

	@Column(name = "sku")
	private String sku;

	@Column(name = "image_url")
	private String imageUrl;

	/**
	 * The platform category, snapshotted.
	 *
	 * <p>Frozen rather than resolved through {@code shop.shop_type}, because a shop's type can
	 * change (D29) and a historical order must keep the category it was placed under.
	 */
	@Column(name = "product_category", nullable = false)
	@Convert(converter = com.storefront.store.catalog.ProductCategoryConverter.class)
	private ProductCategory productCategory;

	/** The price charged per unit, before line discount. Snapshot. */
	@Column(name = "unit_price_minor", nullable = false)
	private long unitPriceMinor;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	/** This line's share of the order-level discount, distributed exactly. */
	@Column(name = "discount_minor", nullable = false)
	private long discountMinor;

	/** This line's tax. Computed on the post-discount amount. */
	@Column(name = "tax_minor", nullable = false)
	private long taxMinor;

	/** {@code unit_price × quantity − discount + tax}. The line total a customer sees. */
	@Column(name = "line_total_minor", nullable = false)
	private long lineTotalMinor;

	/** Field initialiser rather than {@code @PrePersist}: see the note on BaseEntity. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private java.time.Instant createdAt = java.time.Instant.now();

	public OrderItem(UUID orderId, UUID productId, UUID variantId, String productName,
			String variantName, ProductCategory category, long unitPriceMinor, int quantity,
			String sku, String imageUrl) {

		if (orderId == null) {
			throw new IllegalArgumentException("orderId is required");
		}
		if (productName == null || productName.isBlank()) {
			throw new IllegalArgumentException("An order line needs the product name it was sold under");
		}
		if (category == null) {
			throw new IllegalArgumentException("An order line needs the category it was sold under");
		}
		if (unitPriceMinor < 0) {
			throw new IllegalArgumentException("Unit price cannot be negative: " + unitPriceMinor);
		}
		if (quantity <= 0) {
			// A zero or negative quantity is the classic cart bug, and it silently produces an
			// order total that does not match the basket the customer saw.
			throw new IllegalArgumentException("Quantity must be at least 1, got: " + quantity);
		}

		this.id = UUID.randomUUID();
		this.orderId = orderId;
		this.productId = productId;
		this.variantId = variantId;
		this.productName = productName;
		this.variantName = variantName;
		this.productCategory = category;
		this.unitPriceMinor = unitPriceMinor;
		this.quantity = quantity;
		this.sku = sku;
		this.imageUrl = imageUrl;
		this.discountMinor = 0;
		this.taxMinor = 0;
		this.lineTotalMinor = unitPriceMinor * quantity;
	}

	/** The line total before any discount or tax. */
	public long grossMinor() {
		return unitPriceMinor * quantity;
	}

	/**
	 * Applies this line's share of the order discount and its tax.
	 *
	 * <p>Called by the quote engine once, at order creation. The two figures arrive already
	 * distributed and already computed, so an order's line totals sum to the order total by
	 * construction.
	 */
	public void applyDiscountAndTax(long discountMinor, long taxMinor) {
		if (discountMinor < 0 || taxMinor < 0) {
			throw new IllegalArgumentException("Line discount and tax cannot be negative");
		}
		long afterDiscount = grossMinor() - discountMinor;
		if (afterDiscount < 0) {
			// The quote engine must never distribute more discount than a line is worth; if it
			// does, the arithmetic bug must surface here rather than in a customer's total.
			throw new IllegalArgumentException(
					"Line discount %d exceeds the line amount %d".formatted(discountMinor, grossMinor()));
		}
		this.discountMinor = discountMinor;
		this.taxMinor = taxMinor;
		this.lineTotalMinor = afterDiscount + taxMinor;
	}

	/** Units, for the bestseller counter. */
	public int units() {
		return quantity;
	}

	/** The DTO shape. No product or variant id: an invoice shows what was bought, not what exists. */
	public record OrderItemView(
			String productName,
			String variantName,
			String sku,
			String imageUrl,
			ProductCategory category,
			String categoryLabel,
			long unitPrice,
			int quantity,
			long discount,
			long tax,
			long lineTotal) {

		public static OrderItemView from(OrderItem item) {
			return new OrderItemView(
					item.getProductName(),
					item.getVariantName(),
					item.getSku(),
					item.getImageUrl(),
					item.getProductCategory(),
					item.getProductCategory().label(),
					item.getUnitPriceMinor(),
					item.getQuantity(),
					item.getDiscountMinor(),
					item.getTaxMinor(),
					item.getLineTotalMinor());
		}
	}
}
