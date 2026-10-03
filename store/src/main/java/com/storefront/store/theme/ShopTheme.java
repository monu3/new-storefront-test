package com.storefront.store.theme;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One shop's theme: its config, its draft state, and whether it is live.
 *
 * <p><b>Several per shop, exactly one published.</b> The editor needs somewhere to save while a
 * shop owner is still deciding, and the partial unique index on
 * {@code (shop_id) WHERE is_published} is what enforces "one live theme" without preventing
 * drafts.
 *
 * <p><b>The config is a copy of the template's, not a reference.</b> See {@link ThemeTemplate} for
 * why. {@link #templateId} is provenance — which template this came from — and is
 * {@code ON DELETE SET NULL}, so retiring a template does not delete the themes built from it.
 */
@Entity
@Table(name = "shop_theme")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopTheme extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	/** Provenance only. Null once the template has been deleted. */
	@Column(name = "template_id")
	private UUID templateId;

	@Column(name = "name", nullable = false)
	private String name = "Default";

	/** The theme document, validated by {@link ThemeValidator} on write. */
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
	@Column(name = "config", nullable = false, columnDefinition = "jsonb")
	private String config;

	@Column(name = "schema_version", nullable = false)
	private int schemaVersion = ThemeValidator.SCHEMA_VERSION;

	@Column(name = "is_published", nullable = false)
	private boolean published = false;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "created_by")
	private UUID createdBy;

	/** Whether a theme is a working draft or live. */
	public enum Status {
		DRAFT,
		PUBLISHED
	}

	/** The status, derived from {@link #published} rather than stored twice. */
	public Status getStatus() {
		return published ? Status.PUBLISHED : Status.DRAFT;
	}

	public boolean isPublished() {
		return published;
	}

	public ShopTheme(UUID shopId, String name, String config, UUID templateId, UUID createdBy) {
		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A theme needs a name");
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.name = name.trim();
		this.config = config;
		this.templateId = templateId;
		this.createdBy = createdBy;
		this.published = false;
	}

	/**
	 * Publishes this theme, and only if it is the shop's first.
	 *
	 * <p>Refuses a second published theme rather than silently unpublishing the live one. The
	 * partial unique index would reject it anyway, but the message here tells the shop what
	 * happened instead of surfacing a constraint name.
	 *
	 * @throws IllegalStateException when the shop already has a live theme
	 */
	public void publish(boolean shopAlreadyHasPublishedTheme) {
		if (published) {
			return;
		}
		if (shopAlreadyHasPublishedTheme) {
			throw new IllegalStateException(
					"This shop already has a published theme. Unpublish that one first.");
		}
		this.published = true;
		this.publishedAt = Instant.now();
	}

	/**
	 * Withdraws this theme.
	 *
	 * <p>A shop with no published theme falls back to the platform default at render time rather
	 * than showing nothing, so unpublishing is safe.
	 */
	public void unpublish() {
		this.published = false;
		this.publishedAt = null;
	}

	public void rename(String name) {
		if (name != null && !name.isBlank()) {
			this.name = name.trim();
		}
	}

	/** Replaces the document. Refuses to edit a live theme's config without unpublishing first. */
	public void replaceConfig(String newConfig) {
		if (published) {
			throw new IllegalStateException(
					"Unpublish the theme before editing it, so the live storefront is never "
							+ "half-updated.");
		}
		this.config = newConfig;
	}
}
