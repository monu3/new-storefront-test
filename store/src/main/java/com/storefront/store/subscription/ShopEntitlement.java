package com.storefront.store.subscription;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

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
 * What one shop may do right now (D4).
 *
 * <p><b>The single source of truth for entitlements</b>, and the reason plan entitlements are
 * copied in on activation rather than joined at read time: one table answers "may this shop do X",
 * and editing a plan that nobody has bought cannot change what existing subscribers can do.
 *
 * <p><b>{@link #expiresAt} is enforced on read</b> as {@code expires_at > now()}, not by a sweep.
 * The sweep exists to tidy the table, but access must stop the moment the window closes rather than
 * when a scheduled task next runs -- otherwise how long a lapsed subscriber keeps working depends
 * on the scheduler, which is not a thing a customer should be able to observe.
 *
 * <p>Written by {@link SubscriptionService} only. Nothing outside the subscription package
 * constructs one.
 */
@Entity
@Table(name = "shop_entitlement")
@IdClass(ShopEntitlement.Key.class)
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopEntitlement {

	/**
	 * The composite key.
	 *
	 * <p>Matches the table's {@code (shop_id, feature_key)} primary key. An {@link IdClass} rather
	 * than an {@code @EmbeddedId} because both columns are plain scalars with no lifecycle of their
	 * own, and an embedded id would mean a field that is not part of the entity being part of its
	 * identity in the Java type as well.
	 */
	@Getter
	@Setter
	public static class Key implements java.io.Serializable {
		private UUID shopId;
		private String featureKey;

		public Key() {
		}

		public Key(UUID shopId, String featureKey) {
			this.shopId = shopId;
			this.featureKey = featureKey;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key
					&& java.util.Objects.equals(shopId, key.shopId)
					&& java.util.Objects.equals(featureKey, key.featureKey);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(shopId, featureKey);
		}
	}

	@Id
	@Column(name = "shop_id", nullable = false)
	private UUID shopId;

	@Id
	@Column(name = "feature_key", nullable = false)
	private String featureKey;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	/** The cap for a countable feature. Null means uncapped. */
	@Column(name = "limit_value")
	private Long limitValue;

	/**
	 * When this grant stops applying.
	 *
	 * <p>Copied from the subscription's {@code expiresAt} at materialisation. Null on a manually
	 * granted feature that does not expire.
	 */
	@Column(name = "expires_at")
	private Instant expiresAt;

	public ShopEntitlement(UUID shopId, FeatureKey feature, boolean enabled,
			Long limitValue, Instant expiresAt) {
		this.shopId = java.util.Objects.requireNonNull(shopId, "shopId");
		this.featureKey = java.util.Objects.requireNonNull(feature, "feature").key();
		this.enabled = enabled;
		this.limitValue = limitValue;
		this.expiresAt = expiresAt;
	}

	/**
	 * Whether this grant is in force at {@code now}.
	 *
	 * <p>The expiry test is on the row, not on the subscription, so an entitlement survives a
	 * subscription row being closed by a sweep that ran late.
	 */
	public boolean inForce(Instant now) {
		return expiresAt == null || now.isBefore(expiresAt);
	}

	/** The feature, or null when the stored key is one this build does not know. */
	public FeatureKey feature() {
		return FeatureKey.fromKey(featureKey);
	}

	/** Whether this grant both applies and says yes. */
	public boolean granted(Instant now) {
		return enabled && inForce(now);
	}

	/**
	 * Whether {@code count} is within this grant's cap.
	 *
	 * <p>An uncapped feature accepts any count. A grant that is not in force caps at zero, because
	 * the shop has no allowance at all -- not at infinity, which is the tempting reading and the
	 * wrong one: a lapsed subscriber with {@code max_products} uncapped could list without limit.
	 */
	public boolean allows(long count, Instant now) {
		if (!inForce(now)) {
			return false;
		}
		if (limitValue == null) {
			return enabled;
		}
		return count <= limitValue;
	}
}
