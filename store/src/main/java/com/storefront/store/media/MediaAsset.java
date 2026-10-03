package com.storefront.store.media;

import java.util.UUID;

import com.storefront.store.common.BaseEntity;

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
 * One Cloudinary asset (D19).
 *
 * <p>A single table for every image in the system — product photos, variant photos, shop logos,
 * banners, payment QR codes — rather than one table per context. That is what makes a shop's
 * media library a single query, and a shop's assets removable in one sweep.
 *
 * <p>Both {@link #url} and {@link #publicId} are stored, and both are needed:
 * <ul>
 *   <li>{@code url} is what the browser renders, and the only column the storefront ever sees.</li>
 *   <li>{@code publicId} is what {@code destroy()} needs. Without it an image is undeletable
 *       and accumulates in the Cloudinary account forever, costing storage for the life of the
 *       project.</li>
 * </ul>
 *
 * <p>{@link #cloudinaryFolder} is derived from the public_id (Cloudinary encodes the path into
 * it) and stored only so a shop's assets can be listed by folder without string parsing at query
 * time. The two must agree; {@code CloudinaryServiceIT} asserts the derivation.
 */
@Entity
@Table(name = "media")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaAsset extends BaseEntity {

	/**
	 * What an asset is for.
	 *
	 * <p>Explicit rather than inferred from the folder, because a shop's logo and a product photo
	 * live in different folders but the difference that matters is how the UI presents them.
	 */
	public enum Usage {
		PRODUCT_IMAGE,
		VARIANT_IMAGE,
		SHOP_LOGO,
		SHOP_FAVICON,
		SHOP_COVER,
		BANNER,
		THEME_IMAGE,
		PAYMENT_QR,
		RECEIPT_PROOF
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false)
	private UUID shopId;

	/** Null for shop-level assets that are not attached to a product. */
	@Column(name = "product_id")
	private UUID productId;

	@Column(name = "variant_id")
	private UUID variantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "usage", nullable = false)
	private Usage usage;

	/** Convention: {@code shop/{shopId}/products/{productId}/...} (D19). */
	@Column(name = "cloudinary_folder", nullable = false)
	private String cloudinaryFolder;

	/** Required to delete or transform. Never exposed in a public DTO. */
	@Column(name = "public_id", nullable = false, unique = true)
	private String publicId;

	@Column(name = "url", nullable = false)
	private String url;

	@Column(name = "secure_url", nullable = false)
	private String secureUrl;

	@Column(name = "width")
	private Integer width;

	@Column(name = "height")
	private Integer height;

	@Column(name = "format")
	private String format;

	/**
	 * Bytes as uploaded.
	 *
	 * <p>Storing the size of the <em>original</em> matters even though the browser is served a
	 * transformed URL: the original is what the shop pays Cloudinary to store, and it is what
	 * makes an over-large upload worth rejecting.
	 */
	@Column(name = "bytes")
	private Integer bytes;

	@Column(name = "alt_text")
	private String altText;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "created_by")
	private UUID createdBy;

	public MediaAsset(UUID shopId, Usage usage, String cloudinaryFolder,
			String publicId, String url, String secureUrl,
			Integer width, Integer height, Integer bytes, String format, UUID createdBy) {

		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (publicId == null || publicId.isBlank()) {
			throw new IllegalArgumentException("publicId is required: without it the asset is undeletable");
		}
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url is required");
		}

		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.usage = usage;
		this.cloudinaryFolder = cloudinaryFolder != null
				? cloudinaryFolder
				: CloudinaryService.folderOf(publicId);
		this.publicId = publicId;
		this.url = url;
		this.secureUrl = secureUrl != null ? secureUrl : url;
		this.width = width;
		this.height = height;
		this.bytes = bytes;
		this.format = format;
		this.createdBy = createdBy;
	}

	/** The image to render, preferring the https URL. */
	public String displayUrl() {
		return secureUrl != null && !secureUrl.isBlank() ? secureUrl : url;
	}

	public void attachToProduct(UUID productId, UUID variantId) {
		this.productId = productId;
		this.variantId = variantId;
	}

	/** The customer-facing shape. Deliberately omits {@code publicId} and the Cloudinary folder. */
	public record MediaView(String id, String url, String width, String height, String altText) {

		static MediaView from(MediaAsset asset) {
			return new MediaView(
					asset.getId().toString(),
					asset.displayUrl(),
					asset.getWidth() == null ? null : String.valueOf(asset.getWidth()),
					asset.getHeight() == null ? null : String.valueOf(asset.getHeight()),
					asset.getAltText());
		}
	}
}
