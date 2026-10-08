package com.storefront.store.theme;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.common.BaseEntity;
import com.storefront.store.media.MediaAsset;
import com.storefront.store.media.MediaAssetRepository;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * A template in the marketplace: what an admin publishes, and what shops install.
 *
 * <p><b>A template is a snapshot, not a live document.</b> A shop that installs one gets a copy of
 * its config, so editing or retiring the template later cannot change what that shop's storefront
 * looks like. The alternative — referencing the template and resolving at render time — means a
 * template edit silently restyles every shop that installed it, which is the kind of surprise that
 * costs a shop its customers' trust.
 *
 * <p>{@link #shopType} is a nullable enum code rather than a foreign key. The set of shop types
 * is closed (D17), so a FK would buy no referential integrity that the CHECK does not already
 * give, and it would cost a migration for every vertical ever added. Null means "suits any type",
 * which is what a genuinely generic template wants.
 *
 * <p>Publishing is explicit and reversible. A template with a null {@code publishedAt} is a draft:
 * editable, and absent from the marketplace. Unpublishing keeps it and its installs.
 */
@Entity
@Table(name = "theme_template")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ThemeTemplate extends BaseEntity {

	/** Which marketplace tier a template is for. Phase 3 renders everything; Phase 6 gates SUBSCRIBED. */
	public enum PriceTier {
		FREE,
		SUBSCRIBED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** Stable machine name, used in seed scripts and URLs. Unique. */
	@Column(name = "code", nullable = false, unique = true)
	private String code;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description")
	private String description;

	@Column(name = "preview_media_id")
	private UUID previewMediaId;

	/** Null suits every shop type. */
	@Column(name = "shop_type")
	private String shopType;

	/** The theme document, already validated by {@link ThemeValidator} on write. */
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
	@Column(name = "config", nullable = false, columnDefinition = "jsonb")
	private String config;

	/** Version of the THEME SCHEMA, so a renderer change can migrate rather than reinterpret. */
	@Column(name = "schema_version", nullable = false)
	private int schemaVersion = ThemeValidator.SCHEMA_VERSION;

	@Enumerated(EnumType.STRING)
	@Column(name = "price_tier", nullable = false)
	private PriceTier priceTier = PriceTier.FREE;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder = 0;

	/** Null while the template is a draft. Non-null once published. */
	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "created_by")
	private UUID createdBy;

	public ThemeTemplate(String code, String name, String config, ShopType shopType) {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("A template needs a code, e.g. 'pharmacy-clean'");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A template needs a name");
		}
		this.id = UUID.randomUUID();
		this.code = code.trim();
		this.name = name.trim();
		this.config = config;
		this.shopType = shopType == null ? null : shopType.name();
		this.active = true;
	}

	/** The validated enum, or null when the template suits any shop type. */
	public ShopType shopTypeOrNull() {
		return shopType == null ? null : ShopType.fromCode(shopType);
	}

	public void changeType(ShopType type) {
		this.shopType = type == null ? null : type.name();
	}

	/** Makes the template appear in the marketplace. Idempotent, so re-publishing is harmless. */
	public void publish() {
		this.publishedAt = this.publishedAt == null ? Instant.now() : this.publishedAt;
	}

	/**
	 * Withdraws the template from the marketplace.
	 *
	 * <p>Not a delete. Shops that installed it keep their own copy of the config, so withdrawing
	 * stops new installs without touching anyone who already has it.
	 */
	public void unpublish() {
		this.publishedAt = null;
	}

	public boolean isPublished() {
		return publishedAt != null;
	}

	/** Published, active, and offered at the shop's own tier. */
	public boolean isOfferedTo(ShopType shopType, boolean subscribed) {
		if (!isPublished() || !active) {
			return false;
		}
		if (priceTier == PriceTier.SUBSCRIBED && !subscribed) {
			return false;
		}
		return suitsShopType(shopType);
	}

	/**
	 * Whether this template is for a given shop's vertical, ignoring publication and tier.
	 *
	 * <p><b>Separate from {@link #isOfferedTo} because that one bundles three conditions.</b> The
	 * install endpoint legitimately installs a template that is still a draft -- that is what a
	 * draft is for, and an admin previewing before publishing is the ordinary case -- so gating
	 * installation on {@code isOfferedTo} refused six existing tests and would have refused the
	 * preview flow with them.
	 *
	 * <p>What installation must check is the one thing that is about the *shop*: a GROCERY shop may
	 * not install a PHARMACY-only template whatever it pays for. Publication and tier are checked
	 * separately, and the tier is checked by the entitlement rather than by this method.
	 */
	public boolean suitsShopType(ShopType shopType) {
		// A null shop_type suits every vertical; a tagged one must match.
		return this.shopType == null || this.shopType.equals(shopType.name());
	}

	public record TemplateView(
			UUID id,
			String code,
			String name,
			String description,
			ShopType shopType,
			PriceTier priceTier,
			boolean active,
			boolean published,
			int sortOrder,
			String previewImageUrl,
			int sectionCount,
			Instant publishedAt) {
	}
}
