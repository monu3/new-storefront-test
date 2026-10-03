package com.storefront.store.catalog;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * Something a shop sells.
 *
 * <p>{@link #productCategory} is a hardcoded enum code validated against the owning shop's
 * {@link ShopType} (D18) — there is no category table, so adding a vertical is a code change and
 * a redeploy. The check lives in {@link CategoryValidator}, and {@code ProductService} is the
 * only caller that performs it, so a product can never be persisted in a category its shop does
 * not sell.
 *
 * <p><b>The product/variant split.</b> A product is what the customer browses; a variant is what
 * they actually buy and what carries the price, the SKU and the stock. A pharmacy selling
 * "Panadol 500mg" has one product and one variant; a clothing shop selling "Kurta" has one
 * product and five variants. Modelling that as one table would mean either duplicating the
 * product copy per size or storing options in JSON and querying them badly.
 *
 * <p><b>Every product has at least one variant</b>, even a single-SKU one. A product with no
 * variant has no price and cannot be ordered, so the service refuses to publish one. This is
 * why {@link #basePriceMinor} is a derived convenience rather than the source of truth: it
 * mirrors the price of the default variant so a product list can sort and filter without a
 * join, and {@code ProductService} keeps the two in step.
 *
 * <p>Lombok for accessors only. {@link #publish}, {@link #archive} and {@link #updateCategory}
 * are hand-written because each carries a rule a generated setter would let a caller bypass.
 */
@Entity
@Table(name = "product")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

	/**
	 * Visibility within the shop.
	 *
	 * <p>DRAFT is the normal starting point and is invisible to customers. There is no
	 * "pending approval" status for products: product review is a shop-type decision (pharmacy
	 * products need a licence number), not a per-product platform decision, and inventing one
	 * here would duplicate that flow badly.
	 */
	public enum Status {
		DRAFT,
		ACTIVE,
		ARCHIVED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	/**
	 * Optional shop-authored grouping, in addition to the platform category.
	 *
	 * <p>A pharmacy may sort its shelves into "Pain Relief" and "Antibiotics"; a bookshop into
	 * "Fiction" and "Cookery". This is cosmetic navigation only — the platform category
	 * ({@link #productCategory}) is what customers filter by and what {@link CategoryValidator}
	 * enforces, so a shop cannot use this to escape the category rules.
	 */
	@Column(name = "shop_category_id")
	private UUID shopCategoryId;

	/** Enum code. Validated against {@code shop.shop_type}, never trusted from the request alone. */
	@Column(name = "product_category", nullable = false)
	@Convert(converter = com.storefront.store.catalog.ProductCategoryConverter.class)
	private ProductCategory productCategory;

	@Column(name = "name", nullable = false)
	private String name;

	/** Unique per shop. Not unique globally: two shops may both sell "Samosa". */
	@Column(name = "slug", nullable = false, columnDefinition = "citext")
	private String slug;

	/** One line, shown in listings. Full detail goes in {@link #description}. */
	@Column(name = "short_description")
	private String shortDescription;

	@Column(name = "description")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.DRAFT;

	/**
	 * Lowest active variant price, in paisa.
	 *
	 * <p>A mirror of the default variant's price, kept so a product list can sort by price and
	 * show "from Rs. 40" without a join per row. {@code ProductService} recomputes it whenever
	 * a variant price changes, and a test asserts the two agree.
	 */
	@Column(name = "base_price_minor", nullable = false)
	private long basePriceMinor;

	/** A struck-through "was" price. Must exceed {@link #basePriceMinor} to be meaningful. */
	@Column(name = "compare_at_minor")
	private Long compareAtMinor;

	/**
	 * What the shop paid. Never leaves the server.
	 *
	 * <p>No public DTO has a field for this, and no public query selects it. Margin is the most
	 * commercially sensitive number a shop has, and a leak here would be a permanent one in
	 * browser caches and scraped HTML.
	 */
	@Column(name = "cost_price_minor")
	private Long costPriceMinor;

	/** Needed to compute shipping, so a shop owner is asked for it up front. */
	@Column(name = "weight_grams")
	private Integer weightGrams;

	/**
	 * Whether stock is decremented on sale.
	 *
	 * <p>False for a shop selling made-to-order or unlimited digital-ish goods. A bakery with
	 * one "Birthday Cake" product has no meaningful stock count, and forcing it to manage one
	 * would be busywork that gets entered wrongly.
	 */
	@Column(name = "track_inventory", nullable = false)
	private boolean trackInventory = true;

	/** May be bought when stock hits zero. Common for medicines and made-to-order items. */
	@Column(name = "allow_backorder", nullable = false)
	private boolean allowBackorder = false;

	@Column(name = "is_featured", nullable = false)
	private boolean featured = false;

	@Column(name = "seo_title")
	private String seoTitle;

	@Column(name = "seo_description")
	private String seoDescription;

	/**
	 * Lifetime units sold.
	 *
	 * <p>A denormalised counter rather than a query over orders: "bestseller" sorts are on
	 * every product page, and counting order lines at read time does not scale. Incremented at
	 * order accept, not at payment, so it reflects fulfilment rather than intent.
	 */
	@Column(name = "sold_count", nullable = false)
	private int soldCount = 0;

	@Column(name = "view_count", nullable = false)
	private int viewCount = 0;

	/** Soft delete. A product with order history is never hard-deleted. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	public Product(UUID shopId, String name, String slug, ProductCategory category) {
		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A product needs a name");
		}
		if (category == null) {
			throw new IllegalArgumentException("A product needs a category");
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.name = name.trim();
		this.slug = slug;
		this.productCategory = category;
		this.status = Status.DRAFT;
		this.trackInventory = true;
	}

	/**
	 * Makes the product visible to customers.
	 *
	 * <p>Refuses a product with no price. Publishing a Rp. 0 or unpriced product puts a broken
	 * row in the customer's basket, and the alternative — publishing and letting checkout fail —
	 * is worse, because the customer only finds out after filling in their address.
	 */
	public void publish(long minimumVariantPriceMinor) {
		if (minimumVariantPriceMinor < 0) {
			throw new IllegalArgumentException("A product must have a price to be published");
		}
		this.basePriceMinor = minimumVariantPriceMinor;
		this.status = Status.ACTIVE;
	}

	/**
	 * Hides the product without losing its history.
	 *
	 * <p>Distinct from {@link #delete()}: archiving keeps the product selectable by past orders
	 * and keeps its URL alive, whereas deleting removes it from the storefront entirely.
	 */
	public void archive() {
		this.status = Status.ARCHIVED;
	}

	/**
	 * Soft delete.
	 *
	 * <p>Refuses to delete a product that has been sold. The order line holds a snapshot, but
	 * the shop's sales report, its refund flow and its inventory history all point at this row;
	 * deleting it would leave them dangling. Archiving is the right tool.
	 */
	public void delete() {
		if (soldCount > 0) {
			throw new IllegalStateException(
					"This product has sales history and cannot be deleted. Archive it instead.");
		}
		this.deletedAt = Instant.now();
		this.status = Status.ARCHIVED;
	}

	/**
	 * Re-categorises the product.
	 *
	 * <p>Does not validate against the shop type — that is {@code CategoryValidator}'s job and
	 * {@code ProductService}'s. Keeping the check out of the entity means the rule is stated in
	 * exactly one place instead of being duplicated here where it could drift.
	 */
	public void updateCategory(ProductCategory category) {
		if (category == null) {
			throw new IllegalArgumentException("A product needs a category");
		}
		this.productCategory = category;
	}

	/**
	 * Recomputes the mirrored minimum price.
	 *
	 * <p>Called after any variant price change. Derived values that are never refreshed become
	 * wrong within a day, and a "from Rs. 40" badge on a product whose cheapest size is Rs. 90
	 * is the kind of small lie that costs a shop a customer.
	 */
	public void refreshMinimumPrice(long minimumVariantPriceMinor) {
		this.basePriceMinor = Math.max(0, minimumVariantPriceMinor);
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	/** Visible in the storefront: active, not deleted. */
	public boolean isVisible() {
		return status == Status.ACTIVE && deletedAt == null;
	}

	/**
	 * Whether a customer may buy any variant right now.
	 *
	 * <p>Not the same as {@link #isVisible()}: a visible product with every variant out of stock
	 * is still listed (with "Out of stock"), because hiding it entirely would make a shop's
	 * catalogue look emptier than it is.
	 */
	public boolean isPurchasable() {
		return isVisible() && (allowBackorder || !trackInventory);
	}

	/** The price to show, which is the strike-through price when there is one. */
	public boolean hasDiscount() {
		return compareAtMinor != null && compareAtMinor > basePriceMinor;
	}

	public int discountPercent() {
		if (!hasDiscount()) {
			return 0;
		}
		return (int) Math.round((compareAtMinor - basePriceMinor) * 100.0 / compareAtMinor);
	}

	/** Records a page view. Counts are approximate by nature, so this never fails loudly. */
	public void recordView() {
		this.viewCount++;
	}
}
