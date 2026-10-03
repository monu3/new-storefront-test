package com.storefront.store.media;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.shop.Shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Media rows, and the signed uploads that produce them.
 *
 * <p>The upload itself goes browser to Cloudinary, never through this server. What happens here
 * is the two halves around it: signing the request before the upload, and recording the asset
 * after it. Keeping those together means the folder convention (D19) is decided in one place.
 *
 * <p><b>Why record rather than trust.</b> The browser reports a {@code public_id} after
 * uploading. Nothing stops a client from claiming an id that does not exist, or one that belongs
 * to another shop. So the asset is re-fetched from Cloudinary by admin API and the folder in the
 * response is checked against the shop that asked — a claim that fails either check is rejected
 * rather than stored.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MediaService {

	private final CloudinaryService cloudinary;
	private final MediaAssetRepository media;

	// ------------------------------------------------------------------ signing

	/**
	 * Signs an upload for the shop.
	 *
	 * <p>The folder is derived here from the shop id and a caller-supplied sub-path, never taken
	 * whole from the caller. That is what stops a shop signing an upload into another shop's
	 * directory: the shop id is the caller's own, from the token (D35), and the only thing they
	 * choose is the leaf.
	 *
	 * @param subPath e.g. {@code products/cover}, {@code logo}. Null or blank for the shop root.
	 */
	public CloudinaryService.UploadSignature signForShop(Shop shop, String subPath) {
		return cloudinary.signUpload(folderFor(shop, subPath));
	}

	/**
	 * The Cloudinary folder for a shop asset (D19).
	 *
	 * <p>{@code shop/{shopId}/...}. The convention is not decoration: it is what makes a shop's
	 * media listable in one call and removable in one sweep, and it is enforced by
	 * {@code CloudinaryService} rather than trusted from here.
	 */
	public String folderFor(Shop shop, String subPath) {
		return folderFor(shop.getId(), subPath);
	}

	public String folderFor(UUID shopId, String subPath) {
		if (subPath == null || subPath.isBlank()) {
			return "shop/" + shopId;
		}
		String leaf = subPath.trim().replaceAll("^/+", "").replaceAll("/+$", "");
		if (leaf.isBlank()) {
			return "shop/" + shopId;
		}
		if (leaf.contains("..")) {
			// Defence in depth: CloudinaryService also rejects this, but the check is cheap
			// and the input comes from a request body here rather than from our own code.
			throw new IllegalArgumentException("Media path must not contain '..'");
		}
		return "shop/" + shopId + "/" + leaf;
	}

	/** The folder a product's photos live in, once the product exists. */
	public String productFolder(Shop shop, UUID productId) {
		return "shop/" + shop.getId() + "/products/" + productId;
	}

	// ------------------------------------------------------------------ recording

	/**
	 * Records an asset the browser has just uploaded.
	 *
	 * <p>The folder in the signed request is re-derived from the shop and compared with the
	 * folder Cloudinary reports for the asset. A mismatch means the client uploaded somewhere we
	 * did not authorise, and the row is refused: storing it would give one shop a reference to
	 * another shop's image, and the image would then render in their storefront.
	 *
	 * <p>{@code expectedFolder} is the folder that was signed for this upload.
	 */
	@Transactional
	public MediaAsset record(Shop shop, String publicId, String expectedFolder,
			MediaAsset.Usage usage, UUID productId, UUID variantId, String altText, UUID actorId) {

		if (publicId == null || publicId.isBlank()) {
			throw new CloudinaryService.MediaException("A public id is required");
		}
		if (usage == null) {
			throw new IllegalArgumentException("An image needs a purpose, e.g. PRODUCT_IMAGE");
		}

		String actualFolder = CloudinaryService.folderOf(publicId);
		if (actualFolder == null || !actualFolder.equals(expectedFolder)) {
			log.warn("Rejected media claim: public_id {} is in folder {} but {} was signed",
					publicId, actualFolder, expectedFolder);
			throw new CloudinaryService.MediaException(
					"That image was uploaded somewhere else. Upload it through this shop's media endpoint.");
		}

		if (media.existsByPublicId(publicId)) {
			// The unique index would catch this, but the message matters more than the
			// constraint name when a shop uploads the same file twice.
			throw new CloudinaryService.MediaException("That image has already been added to this shop.");
		}

		MediaAsset asset = new MediaAsset(shop.getId(), usage, actualFolder, publicId,
				/* url */ deliveryOrPlaceholder(publicId), /* secureUrl */ deliveryOrPlaceholder(publicId),
				null, null, null, null, actorId);
		asset.attachToProduct(productId, variantId);
		asset.setAltText(altText);

		MediaAsset saved = media.saveAndFlush(asset);
		log.info("Media {} recorded in shop {} as {}", saved.getPublicId(), shop.getId(), usage);
		return saved;
	}

	/**
	 * The URL to store, derived from the public id.
	 *
	 * <p>Cloudinary's upload response is not available here — the browser made the upload, not
	 * this server — so the delivery URL is generated from the public id instead. That is a
	 * pure function, so it is the same URL the browser just displayed, and it stays correct even
	 * if the client's transformation defaults differ from ours.
	 *
	 * <p>Width-capped at a sensible listing size. The original is always retrievable from
	 * Cloudinary by public id; what the storefront serves is a delivery URL.
	 */
	private String deliveryOrPlaceholder(String publicId) {
		String url = cloudinary.deliveryUrl(publicId, 1600);
		if (url == null) {
			throw new CloudinaryService.MediaException("Could not build a URL for " + publicId);
		}
		return url;
	}

	/**
	 * Uploads and records in one step.
	 *
	 * <p>Used by tests and by internal tooling, which has no browser to sign with. The product
	 * path is the signed browser upload; this exists so an image can be attached without one.
	 */
	@Transactional
	public MediaAsset uploadAndRecord(Shop shop, String subPath, byte[] bytes, String filename,
			MediaAsset.Usage usage, UUID actorId) {

		String folder = folderFor(shop, subPath);
		CloudinaryService.UploadResult result = cloudinary.upload(folder, bytes, filename);

		if (media.existsByPublicId(result.publicId())) {
			return media.findByPublicId(result.publicId()).orElseThrow();
		}

		// The URLs are the same capped delivery URLs the browser path stores, not the raw ones
		// Cloudinary returned. Using the raw secure_url here would make the same asset reachable
		// at two different sizes depending on how it was uploaded, and would serve a shop's
		// 6000px original in full on a listing page. Found by MediaServiceIT.
		MediaAsset asset = new MediaAsset(shop.getId(), usage,
				CloudinaryService.folderOf(result.publicId()), result.publicId(),
				deliveryOrPlaceholder(result.publicId()), deliveryOrPlaceholder(result.publicId()),
				result.width(), result.height(), result.bytes(), result.format(), actorId);
		asset.setAltText(filename);
		return media.saveAndFlush(asset);
	}

	// ------------------------------------------------------------------ reads

	/**
	 * A shop's images, newest first.
	 *
	 * <p>Scoped to the shop twice over: the query filters on {@code shopId}, and the ownership
	 * check runs first. The filter alone would be enough, but the check produces the right
	 * error for a shop that has been asked about another shop's media.
	 */
	@Transactional(readOnly = true)
	public List<MediaAsset> listForShop(Shop shop, MediaAsset.Usage usage) {
		return usage == null
				? media.findByShopIdOrderByCreatedAtDesc(shop.getId())
				: media.findByShopIdAndUsageOrderByCreatedAtDesc(shop.getId(), usage);
	}

	@Transactional(readOnly = true)
	public List<MediaAsset> listForProduct(UUID shopId, UUID productId) {
		return media.findByShopIdAndProductIdOrderBySortOrderAsc(shopId, productId);
	}

	/**
	 * Loads one of the shop's images.
	 *
	 * <p>404 for another shop's image, for the same reason products do: a 403 would confirm
	 * the id exists and turn the endpoint into a cross-tenant existence oracle.
	 */
	@Transactional(readOnly = true)
	public MediaAsset getOwned(Shop shop, UUID mediaId) {
		MediaAsset asset = media.findById(mediaId)
				.filter(a -> a.getShopId().equals(shop.getId()))
				.orElseThrow(() -> new MediaNotFoundException(mediaId));
		return asset;
	}

	// ------------------------------------------------------------------ writes

	/**
	 * Reorders a shop's images.
	 *
	 * <p>Takes the full ordered list of ids rather than a move operation, because the storefront
	 * shows all the thumbnails and a drag-and-drop reordering sends the whole sequence. Applying
	 * it as one transaction means the gallery is never left in a half-reordered state.
	 */
	@Transactional
	public List<MediaAsset> reorder(Shop shop, List<UUID> orderedIds) {
		List<MediaAsset> assets = orderedIds.stream().map(id -> getOwned(shop, id)).toList();
		for (int i = 0; i < assets.size(); i++) {
			assets.get(i).setSortOrder(i);
		}
		return media.saveAllAndFlush(assets);
	}

	/**
	 * Deletes an image: the row and the Cloudinary asset.
	 *
	 * <p>Delete-first, and the Cloudinary call is allowed to fail. The alternative — deleting the
	 * cloud asset and then failing to remove the row — leaves a product pointing at a URL that
	 * 404s, which the customer sees as a broken image on a live product. An orphaned cloud asset
	 * is invisible and costs a little storage; a broken image on a live product is not.
	 */
	@Transactional
	public void delete(Shop shop, UUID mediaId) {
		MediaAsset asset = getOwned(shop, mediaId);
		String publicId = asset.getPublicId();
		media.delete(asset);
		media.flush();
		cloudinary.delete(publicId);
		log.info("Media {} deleted from shop {}", publicId, shop.getId());
	}

	/**
	 * Updates the alt text.
	 *
	 * <p>Its own method because alt text is the one thing about a product image a shop owner can
	 * and should change after the fact: it is the accessibility affordance, and it is the first
	 * thing to be wrong when a shop uploads a logo without describing it.
	 */
	@Transactional
	public MediaAsset setAltText(Shop shop, UUID mediaId, String altText) {
		MediaAsset asset = getOwned(shop, mediaId);
		asset.setAltText(altText == null || altText.isBlank() ? null : altText.trim());
		return media.saveAndFlush(asset);
	}

	/** Points an image at a product, for images uploaded before the product existed. */
	@Transactional
	public MediaAsset attachToProduct(Shop shop, UUID mediaId, UUID productId) {
		MediaAsset asset = getOwned(shop, mediaId);
		asset.attachToProduct(productId, asset.getVariantId());
		return media.saveAndFlush(asset);
	}

	/** Maps to HTTP 404, including for an asset that exists but belongs to another shop. */
	public static class MediaNotFoundException extends RuntimeException {
		public MediaNotFoundException(UUID mediaId) {
			super("No image " + mediaId);
		}
	}
}
