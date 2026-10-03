package com.storefront.store.media;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.CloudinaryProperties;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Image uploads and the shop's media library.
 *
 * <p>The flow is three steps, and the split is what keeps large files off this server:
 * <ol>
 *   <li>{@code POST /signature} — the browser asks what to upload and where.</li>
 *   <li>The browser uploads straight to Cloudinary. Our API never sees the bytes.</li>
 *   <li>{@code POST /record} — the browser reports the public id, and the row is created.</li>
 * </ol>
 *
 * <p>Step 3 re-derives the folder from the shop and checks it against the folder Cloudinary
 * reports, so a client cannot claim an image that lives in another shop's directory. See
 * {@link MediaService#record}.
 *
 * <p>No endpoint takes a shop id. The shop is the caller's own (D35).
 */
@RestController
@RequestMapping("/api/v1/shop/media")
@Tag(name = "Media", description = "Signed uploads to Cloudinary and the shop's image library")
@PreAuthorize("hasRole('SHOP')")
@RequiredArgsConstructor
public class MediaController {

	private final MediaService media;
	private final TenantGuard tenantGuard;
	private final CurrentUser currentUser;
	private final CloudinaryProperties cloudinaryProperties;

	/**
	 * Signs a direct-to-Cloudinary upload.
	 *
	 * <p>The response carries the size and dimension limits alongside the signature so the
	 * browser can reject an oversized file before spending the user's bandwidth on an upload
	 * that would be refused anyway.
	 *
	 * @param path the folder within the shop, e.g. {@code products} or {@code logo}. The shop
	 *             id is added here and cannot be overridden.
	 */
	@PostMapping("/signature")
	@Operation(summary = "Sign a direct upload to Cloudinary",
			description = "The browser posts the file straight to Cloudinary, never through this API.")
	public SignatureResponse sign(@Valid @RequestBody SignatureRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		CloudinaryService.UploadSignature signature = media.signForShop(shop, request.path());
		return SignatureResponse.from(signature, cloudinaryProperties.apiKey());
	}

	/**
	 * Records an asset the browser has uploaded.
	 *
	 * <p>{@code expectedFolder} must be the folder that was signed. The server compares it with
	 * the folder Cloudinary reports for the asset and rejects a mismatch, which is the check
	 * that stops a shop claiming an image from another shop's directory.
	 */
	@PostMapping("/record")
	@Operation(summary = "Record an uploaded asset",
			description = "Verified against Cloudinary; a claim about an image in another folder is refused.")
	public MediaView record(@Valid @RequestBody RecordRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		MediaAsset asset = media.record(shop, request.publicId(), request.expectedFolder(),
				request.usage(), request.productId(), request.variantId(), request.altText(),
				currentUser.optionalUserId());
		return MediaView.from(asset);
	}

	/**
	 * The shop's images.
	 *
	 * @param usage optional filter, so the logo picker does not show product photos
	 */
	@GetMapping
	@Operation(summary = "The shop's images, newest first")
	public List<MediaView> list(@RequestParam(required = false) MediaAsset.Usage usage) {
		Shop shop = tenantGuard.requireOwnedShop();
		return media.listForShop(shop, usage).stream().map(MediaView::from).toList();
	}

	@GetMapping("/for-product/{productId}")
	@Operation(summary = "Images attached to one product, in gallery order")
	public List<MediaView> forProduct(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		return media.listForProduct(shop.getId(), productId).stream().map(MediaView::from).toList();
	}

	/**
	 * Reorders the gallery.
	 *
	 * <p>Takes the whole ordered id list rather than a move, because the storefront shows every
	 * thumbnail and a drag sends the complete sequence. Applied in one transaction, so the
	 * gallery is never left half-reordered.
	 */
	@PatchMapping("/order")
	@Operation(summary = "Reorder images", description = "Send every image id in the order wanted.")
	public List<MediaView> reorder(@Valid @RequestBody ReorderRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		return media.reorder(shop, request.orderedIds()).stream().map(MediaView::from).toList();
	}

	/** Alt text is the accessibility affordance, and the first thing missing when a logo is uploaded. */
	@PatchMapping("/{mediaId}")
	@Operation(summary = "Set an image's alt text")
	public MediaView setAltText(@PathVariable UUID mediaId, @Valid @RequestBody AltTextRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		return MediaView.from(media.setAltText(shop, mediaId, request.altText()));
	}

	/** Points an already-uploaded image at a product. */
	@PatchMapping("/{mediaId}/product/{productId}")
	@Operation(summary = "Attach an image to a product",
			description = "For images uploaded before the product existed.")
	public MediaView attachToProduct(@PathVariable UUID mediaId, @PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		return MediaView.from(media.attachToProduct(shop, mediaId, productId));
	}

	/**
	 * Deletes an image: the row and the Cloudinary asset.
	 *
	 * <p>Possible at all only because {@code public_id} is stored (D19). With just a URL the
	 * asset would be undeletable and would accumulate in the account for the life of the project.
	 */
	@DeleteMapping("/{mediaId}")
	@Operation(summary = "Delete an image",
			description = "Removes the database row and the underlying Cloudinary asset.")
	public void delete(@PathVariable UUID mediaId) {
		Shop shop = tenantGuard.requireOwnedShop();
		media.delete(shop, mediaId);
	}

	// ------------------------------------------------------------------ requests

	public record SignatureRequest(
			/** Sub-folder within the shop, e.g. {@code products} or {@code logo}. Optional. */
			@Size(max = 120) String path) {
	}

	public record RecordRequest(
			@NotBlank @Size(max = 300) String publicId,
			/** The folder that was signed. Compared with what Cloudinary reports. */
			@NotBlank @Size(max = 300) String expectedFolder,
			MediaAsset.Usage usage,
			UUID productId,
			UUID variantId,
			@Size(max = 200) String altText) {
	}

	public record ReorderRequest(List<UUID> orderedIds) {
		public ReorderRequest {
			orderedIds = orderedIds == null ? List.of() : List.copyOf(orderedIds);
		}
	}

	public record AltTextRequest(@Size(max = 200) String altText) {
	}

	// ------------------------------------------------------------------ responses

	/**
	 * What the browser needs to upload.
	 *
	 * <p>Mirrors the SDK's own response type but as our own record, so a change to the vendor
	 * SDK does not silently change our API contract. {@code apiKey} is public by design — it is
	 * the client half of the credential pair and appears in every signed-upload integration.
	 */
	public record SignatureResponse(
			long timestamp,
			String signature,
			String apiKey,
			String cloudName,
			String folder,
			long maxBytes,
			int maxDimension) {

		static SignatureResponse from(CloudinaryService.UploadSignature s, String apiKey) {
			return new SignatureResponse(s.timestamp(), s.signature(), apiKey, s.cloudName(),
					s.folder(), s.maxBytes(), s.maxDimension());
		}
	}

	/**
	 * A stored image.
	 *
	 * <p>No {@code public_id}, no Cloudinary folder, no byte size. The client renders the URL
	 * and nothing else; the identifiers are server-side bookkeeping.
	 */
	public record MediaView(
			UUID id,
			String url,
			Integer width,
			Integer height,
			String altText,
			MediaAsset.Usage usage,
			UUID productId,
			UUID variantId) {

		static MediaView from(MediaAsset asset) {
			return new MediaView(
					asset.getId(),
					asset.displayUrl(),
					asset.getWidth(),
					asset.getHeight(),
					asset.getAltText(),
					asset.getUsage(),
					asset.getProductId(),
					asset.getVariantId());
		}
	}
}
