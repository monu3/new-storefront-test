package com.storefront.store.subscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a plan grants.
 *
 * <p>The template that {@code shop_entitlement} rows are built from on activation. Editing a plan's
 * entitlements changes what the <em>next</em> activation grants, not what current subscribers have
 * -- which is why {@link SubscriptionService} refuses to edit a plan an ACTIVE subscription holds.
 */
@Entity
@Table(name = "plan_entitlement")
@IdClass(PlanEntitlement.Key.class)
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanEntitlement {

	/**
	 * The composite key: {@code (plan, featureKey)}.
	 *
	 * <p><b>The field is named {@code plan} and typed {@link SubscriptionPlan}, not
	 * {@code planId}/{@code UUID}.</b> An {@link IdClass} must declare exactly the entity's own
	 * persistent properties, by name and by type, and the entity maps this one as a
	 * {@code @ManyToOne}. Hibernate rejects the mismatch at startup with
	 *
	 * <pre>Property 'PlanEntitlement.planId' belongs to an '@IdClass' but has no matching
	 * property in entity class 'PlanEntitlement'</pre>
	 *
	 * <p>which names neither the entity field it wanted nor the fact that the type has to match too.
	 */
	@Getter
	@Setter
	public static class Key implements java.io.Serializable {
		private SubscriptionPlan plan;
		private String featureKey;

		public Key() {
		}

		public Key(SubscriptionPlan plan, String featureKey) {
			this.plan = plan;
			this.featureKey = featureKey;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key
					&& java.util.Objects.equals(plan == null ? null : plan.getId(),
							key.plan == null ? null : key.plan.getId())
					&& java.util.Objects.equals(featureKey, key.featureKey);
		}

		@Override
		public int hashCode() {
			// Hashed on the id, not on the entity: a proxy and the entity it stands for must produce
			// the same hash, or every lookup misses.
			return java.util.Objects.hash(plan == null ? null : plan.getId(), featureKey);
		}
	}

	@Id
	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "plan_id", nullable = false)
	private SubscriptionPlan plan;

	@Id
	@Column(name = "feature_key", nullable = false)
	private String featureKey;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	/** The cap this plan hands out. Null means uncapped. */
	@Column(name = "limit_value")
	private Long limitValue;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	public PlanEntitlement(SubscriptionPlan plan, FeatureKey feature, boolean enabled, Long limitValue) {
		this.plan = java.util.Objects.requireNonNull(plan, "plan");
		this.featureKey = java.util.Objects.requireNonNull(feature, "feature").key();
		this.enabled = enabled;
		this.limitValue = limitValue;
	}

	public FeatureKey feature() {
		return FeatureKey.fromKey(featureKey);
	}
}
