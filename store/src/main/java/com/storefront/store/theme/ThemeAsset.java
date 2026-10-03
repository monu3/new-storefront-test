package com.storefront.store.theme;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

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
 * A shop's own image or page, referenced from a theme by a stable handle.
 *
 * <p><b>Handles, not ids.</b> A section says {@code "footer-main"} and the asset behind that
 * handle can be re-pointed to a new image without editing the section. With an id, replacing a
 * logo means finding and editing every section that referenced it — and missing one leaves a
 * broken image on a live storefront.
 *
 * <p>A handle a section references may not exist yet. That is deliberate: a shop can lay out a
 * page before uploading its photograph, and the renderer shows a placeholder rather than an error.
 */
@Entity
@Table(name = "theme_asset")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ThemeAsset extends BaseEntity {

	/** What an asset is for. Kept in step with the database CHECK. */
	public enum Kind {
		HERO,
		HEADER_PAGE,
		FOOTER,
		BANNER,
		MEDIA_PAGE,
		CATALOG_LAYOUT,
		PRODUCT_LAYOUT,
		POLICY_PAGE
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false)
	private Kind kind;

	/** The stable key a section refers to, e.g. "footer-main". Unique per shop and kind. */
	@Column(name = "handle", nullable = false)
	private String handle;

	/** Human label for the editor's asset list. */
	@Column(name = "name", nullable = false)
	private String name;

	/**
	 * The asset's content.
	 *
	 * <p>Shape varies by kind: an image URL, a policy page's body, a header's links. Stored as
	 * jsonb so adding a field to one kind does not need a migration, and validated per kind at the
	 * service boundary rather than here.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "config", nullable = false, columnDefinition = "jsonb")
	private String config = "{}";

	public ThemeAsset(UUID shopId, Kind kind, String handle, String name, String config) {
		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (kind == null) {
			throw new IllegalArgumentException("An asset needs a kind");
		}
		if (!ThemeValidator.isValidHandle(handle)) {
			// Checked here as well as by the column CHECK, so the message names the pattern rather
			// than surfacing a database error to someone typing a handle in a form.
			throw new IllegalArgumentException(
					"A handle must be lowercase letters, digits and dashes, starting with a letter "
							+ "or digit: '" + handle + "'");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("An asset needs a name");
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.kind = kind;
		this.handle = handle.trim();
		this.name = name.trim();
		this.config = config == null ? "{}" : config;
	}

	public void replaceConfig(String newConfig) {
		this.config = newConfig == null ? "{}" : newConfig;
	}

	public void rename(String newName) {
		if (newName != null && !newName.isBlank()) {
			this.name = newName.trim();
		}
	}

	/**
	 * The image URL this asset points at, or null.
	 *
	 * <p>Only the well-known key is read, because that is the one the theme editor writes and the
	 * renderer reads. A config shape that changes must change both at once, which is why the key
	 * is named once here rather than being repeated at every call site.
	 */
	public String imageUrl(com.fasterxml.jackson.databind.ObjectMapper json) {
		try {
			com.fasterxml.jackson.databind.JsonNode node = json.readTree(config);
			com.fasterxml.jackson.databind.JsonNode url = node.get("imageUrl");
			return url == null || !url.isTextual() ? null : url.asText();
		} catch (Exception e) {
			// A malformed config must not take down a storefront; the asset renders without an
			// image, which is the degraded state the placeholder is for.
			return null;
		}
	}
}
