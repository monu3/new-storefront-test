package com.storefront.store.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * A buyable configuration of a {@link Product}: a size, a weight, a flavour.
 *
 * <p>This is the row that holds the price, the SKU and the stock. A product with a single
 * configuration still gets one of these, because a product with no variant has no price and
 * cannot be added to a basket — so the single-SKU case is represented rather than special-cased.
 *
 * <p><b>{@link #priceMinor} is the source of truth for pricing</b>, not the product's
 * {@code base_price_minor}. The product column is a mirror of the cheapest active variant, kept
 * so listings can sort without a join; a variant price is what a customer is actually charged
 * and what a frozen order line stores.
 *
 * <p><b>{@link #optionValues} is JSON, not columns.</b> The option axes differ per shop type:
 * a clothing shop has Size and Colour, a grocery has Weight, a pharmacy has Pack Size. Modelling
 * that as an EAV table or a column per axis would make every new vertical a migration. JSON keeps
 * the axes data-driven and the filtering question answered honestly instead — the storefront
 * filters on the platform {@link ProductCategory}, not on a shop's private axes.
 *
 * <p>{@link #imageUrl} duplicates a media row's URL on purpose. Variant images are needed on
 * every listing and in the basket, and a join per variant to pick the first image is the kind of
 * cost that shows up as a slow product page. The media row remains the owner of the asset, so a
 * deleted image is visible here too.
 */
@Entity
@Table(name = "product_variant")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductVariant extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	/**
	 * Stock-keeping unit.
	 *
	 * <p>Optional, and unique per product when present. A single-variant product often has none:
	 * forcing every shop to invent barcodes they do not have would just fill the column with
	 * filler like "P1" and "P2".
	 */
	@Column(name = "sku")
	private String sku;

	/**
	 * Display label: "500 mg, 16 tablets" or "Red / Large".
	 *
	 * <p>Required even when {@link #optionValues} fully describes the variant, because a shopper
	 * comparing two variants needs one readable line, and a JSON blob is not that.
	 */
	@Column(name = "name", nullable = false)
	private String name;

	/**
	 * The option axes, as an ordered list of pairs, e.g. {@code [size=L, colour=red]}.
	 *
	 * <p><b>A list, not a map, and that is load-bearing.</b> The column is {@code jsonb}, and
	 * jsonb does not preserve object key order — it normalises keys by length then bytewise.
	 * A variant labelled "Large / Red" therefore read back as "red / L", and two reads of the
	 * same row could disagree. A jsonb <em>array</em> preserves element order, so the order the
	 * shop chose survives the round trip. This was a real bug found by
	 * {@code ProductServiceIT}, not a hypothetical one.
	 *
	 * <p>An empty list is valid and common: a single-variant product has no axes, and an absent
	 * list would be indistinguishable from a bug.
	 *
	 * <p>Order comes from the request body, which Jackson deserialises into a
	 * {@code LinkedHashMap} and therefore preserves.
	 */
	@Column(name = "option_values", nullable = false)
	@JdbcTypeCode(SqlTypes.JSON)
	private List<Option> options = new ArrayList<>();

	/** What the customer pays, in paisa. Never negative. */
	@Column(name = "price_minor", nullable = false)
	private long priceMinor;

	/** A strike-through "was" price. Must exceed {@link #priceMinor} to mean anything. */
	@Column(name = "compare_at_minor")
	private Long compareAtMinor;

	@Column(name = "barcode")
	private String barcode;

	/**
	 * A convenience pointer to the owning product's image.
	 *
	 * <p>Null when the variant has no image of its own, in which case the storefront falls back
	 * to the product's first image. Always resolving to something would mean duplicating every
	 * product image onto each variant and keeping the two in step forever.
	 */
	@Column(name = "image_url")
	private String imageUrl;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder = 0;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	public ProductVariant(UUID productId, String name, long priceMinor) {
		if (productId == null) {
			throw new IllegalArgumentException("productId is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A variant needs a display name");
		}
		if (priceMinor < 0) {
			throw new IllegalArgumentException("Price cannot be negative: " + priceMinor);
		}
		this.id = UUID.randomUUID();
		this.productId = productId;
		this.name = name.trim();
		this.priceMinor = priceMinor;
		this.active = true;
	}

	/** Replaces the axes, preserving the given order. */
	public void updateOptions(List<Option> options) {
		this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
		this.options.removeIf(Option::isBlank);
	}

	/**
	 * Replaces the axes from a map, preserving its iteration order.
	 *
	 * <p>That order is meaningful: it is the order the client sent the JSON object in, which is
	 * the order the shop wants to read, e.g. "Large / Red".
	 */
	public void updateOptions(Map<String, String> options) {
		List<Option> pairs = new ArrayList<>();
		if (options != null) {
			options.forEach((axis, value) -> pairs.add(new Option(axis, value)));
		}
		updateOptions(pairs);
	}

	/** Adds an axis, or replaces the value if that axis is already present. */
	public void addOption(String axis, String value) {
		if (axis == null || axis.isBlank()) {
			throw new IllegalArgumentException("An option needs a name, e.g. 'size'");
		}
		this.options.removeIf(o -> o.axis().equalsIgnoreCase(axis.trim()));
		this.options.add(new Option(axis.trim(), value == null ? "" : value));
	}

	/** The axes as a map, in the stored order. For callers that do not care about ordering. */
	public Map<String, String> optionMap() {
		Map<String, String> map = new LinkedHashMap<>();
		for (Option option : options) {
			map.put(option.axis(), option.value());
		}
		return map;
	}

	/**
	 * Changes the price.
	 *
	 * <p>Refuses a change that would leave the variant cheaper than nothing — trivially, but the
	 * real guard is the non-negative check, since a negative price reaching a basket would make
	 * the order total wrong in a way that is very hard to trace later.
	 */
	public void updatePrice(long newPriceMinor) {
		if (newPriceMinor < 0) {
			throw new IllegalArgumentException("Price cannot be negative: " + newPriceMinor);
		}
		this.priceMinor = newPriceMinor;
	}

	public void setCompareAt(Long compareAtMinor) {
		if (compareAtMinor != null && compareAtMinor <= priceMinor) {
			// A "was" price at or below the current price shows a discount of zero or negative,
			// which reads as a pricing bug to a customer.
			throw new IllegalArgumentException(
					"Compare-at price must be greater than the current price of " + priceMinor);
		}
		this.compareAtMinor = compareAtMinor;
	}

	/**
	 * Hides the variant from the storefront.
	 *
	 * <p>Not a soft delete, and that is deliberate: past order lines snapshot the variant's
	 * name and price, so a variant that has been sold can be deactivated with nothing lost,
	 * whereas deleting the row would leave inventory and sales history pointing at nothing.
	 */
	public void deactivate() {
		this.active = false;
	}

	public void activate() {
		this.active = true;
	}

	public boolean hasDiscount() {
		return compareAtMinor != null && compareAtMinor > priceMinor;
	}

	/** {@code size=L} → "L", or null when that axis is not present. */
	public String option(String axis) {
		if (axis == null) {
			return null;
		}
		for (Option option : options) {
			if (option.axis().equalsIgnoreCase(axis)) {
				return option.value();
			}
		}
		return null;
	}

	/**
	 * A readable label in the order the shop defined its axes, e.g. "Large / Red".
	 *
	 * <p>Null when there are no axes, so a caller can distinguish "no options" from "options
	 * with an empty value" — which matters, because a variant with no axes should show only its
	 * name.
	 */
	public String optionsLabel() {
		if (options.isEmpty()) {
			return null;
		}
		return options.stream().map(Option::value).reduce((a, b) -> a + " / " + b).orElse(null);
	}

	/**
	 * One option axis.
	 *
	 * <p>A record rather than a {@code Map.Entry}, so the JSON shape is explicit and the
	 * round trip through jsonb has a named type to bind to.
	 */
	public record Option(String axis, String value) {

		/** An axis with no name carries no information and would render as "/ Red". */
		boolean isBlank() {
			return axis == null || axis.isBlank();
		}
	}

	/**
	 * The DTO shape. Currency is deliberately absent — the platform is single-currency (NPR).
	 *
	 * <p>{@code options} is the ordered list rather than a map, because the order is part of what
	 * the shop asked for: a JSON object would arrive at the client in an arbitrary order and
	 * "Large / Red" would render as "red / L". {@code optionsLabel} is what the UI actually
	 * displays, so the client does not have to rebuild it and get it subtly wrong.
	 */
	public record VariantView(
			String id,
			String name,
			String sku,
			long price,
			Long compareAt,
			String imageUrl,
			boolean active,
			List<Option> options,
			String optionsLabel,
			boolean hasDiscount) {

		public static VariantView from(ProductVariant v) {
			return new VariantView(
					v.getId().toString(),
					v.getName(),
					v.getSku(),
					v.getPriceMinor(),
					v.getCompareAtMinor(),
					v.getImageUrl(),
					v.isActive(),
					List.copyOf(v.getOptions()),
					v.optionsLabel(),
					v.hasDiscount());
		}
	}
}
