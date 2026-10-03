package com.storefront.store.checkout;

import java.util.List;
import java.util.Set;
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
 * A delivery area and its flat rate.
 *
 * <p>{@link #cities} is an empty array for the shop's default zone, which applies when no other
 * zone matches. That is deliberate rather than a fallback hack: most Nepali shops deliver
 * nationwide at one price and want that to be the rule, not the exception.
 *
 * <p>Flat rate per zone, not weight-based, for Phase 1. A shop owner in a country where courier
 * pricing is genuinely distance- and weight-based will find this limiting, but a per-zone flat
 * rate covers the overwhelming majority of shops and — crucially — makes the total a customer sees
 * the same number every time, which is what builds trust. Weight-based rates are Phase 4.
 */
@Entity
@Table(name = "shipping_zone")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingZone extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@Column(name = "name", nullable = false)
	private String name;

	/** Matched case-insensitively. Empty means "everything else". */
	@Column(name = "cities", nullable = false)
	private String[] cities = new String[0];

	@Column(name = "rate_minor", nullable = false)
	private long rateMinor = 0;

	/** Free delivery at or above this subtotal. Null means never free. */
	@Column(name = "free_over_minor")
	private Long freeOverMinor;

	@Column(name = "min_days", nullable = false)
	private int minDays = 1;

	@Column(name = "max_days", nullable = false)
	private int maxDays = 7;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder = 0;

	public ShippingZone(UUID shopId, String name, long rateMinor) {
		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A shipping zone needs a name");
		}
		if (rateMinor < 0) {
			throw new IllegalArgumentException("Shipping rate cannot be negative: " + rateMinor);
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.name = name.trim();
		this.rateMinor = rateMinor;
		this.active = true;
	}

	/** Whether this zone covers a delivery city. Blank city means "use the default zone". */
	public boolean covers(String city) {
		if (cities == null || cities.length == 0) {
			return true;
		}
		if (city == null || city.isBlank()) {
			return false;
		}
		String target = city.trim();
		for (String candidate : cities) {
			if (candidate != null && candidate.trim().equalsIgnoreCase(target)) {
				return true;
			}
		}
		return false;
	}

	/** True for the zone that applies when nothing else matches. */
	public boolean isDefault() {
		return cities == null || cities.length == 0;
	}

	public void setCities(List<String> cities) {
		this.cities = cities == null ? new String[0] : cities.toArray(new String[0]);
	}

	public Set<String> citySet() {
		return cities == null ? Set.of() : Set.of(cities);
	}

	/**
	 * The shipping charge for a subtotal.
	 *
	 * <p>Free-over applies at or above the threshold. "At or above" rather than "above" because a
	 * shop setting a threshold of 5,000 means "free from 5,000", and a customer hitting exactly
	 * the threshold expects free delivery.
	 */
	public long chargeFor(long subtotalMinor) {
		if (freeOverMinor != null && subtotalMinor >= freeOverMinor) {
			return 0;
		}
		return rateMinor;
	}

	public void setDeliveryDays(int min, int max) {
		if (min < 0) {
			throw new IllegalArgumentException("Minimum days cannot be negative");
		}
		if (max < min) {
			throw new IllegalArgumentException(
					"Maximum days (" + max + ") cannot be less than minimum days (" + min + ")");
		}
		this.minDays = min;
		this.maxDays = max;
	}

	/** "2–4 days", or "1 day" when the range is a single value. */
	public String deliveryEstimate() {
		return minDays == maxDays ? minDays + (minDays == 1 ? " day" : " days")
				: minDays + "–" + maxDays + " days";
	}
}
