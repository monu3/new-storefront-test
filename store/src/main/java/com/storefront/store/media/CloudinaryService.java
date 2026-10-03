package com.storefront.store.media;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.storefront.store.common.CloudinaryProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Talks to Cloudinary. The only class that knows the vendor exists.
 *
 * <p>Everything above this deals in {@link MediaAsset} and Cloudinary identifiers; nothing above
 * it constructs a URL or signs a request. That containment is what makes swapping the provider a
 * change to one file.
 *
 * <p>Uses the official SDK rather than a hand-rolled signed-upload implementation. Cloudinary's
 * signature is SHA-1 over an alphabetically sorted parameter string with the secret appended,
 * base64url-encoded — reimplementing that gets it subtly wrong and fails at runtime with
 * "Invalid Signature" and no useful diagnostic. That was tried first and abandoned; the SDK also
 * provides delivery-URL transformations, which the storefront needs for responsive images.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CloudinaryService {

	private final CloudinaryProperties properties;

	private volatile com.cloudinary.Cloudinary cloudinary;

	/** Built lazily so a partially configured environment does not stop the app from starting. */
	private com.cloudinary.Cloudinary client() {
		com.cloudinary.Cloudinary existing = cloudinary;
		if (existing == null) {
			synchronized (this) {
				existing = cloudinary;
				if (existing == null) {
					existing = build();
					cloudinary = existing;
				}
			}
		}
		return existing;
	}

	private com.cloudinary.Cloudinary build() {
		if (!properties.isConfigured()) {
			// Thrown on use, not on startup: the rest of the platform is useful without media,
			// and refusing to boot would turn a missing optional credential into an outage.
			throw new MediaException("Cloudinary is not configured. Set the cloud name, api key "
					+ "and api secret.");
		}
		Map<String, Object> config = new HashMap<>();
		config.put("cloud_name", properties.cloudName());
		config.put("api_key", properties.apiKey());
		config.put("api_secret", properties.apiSecret());
		config.put("secure", true);
		return new com.cloudinary.Cloudinary(config);
	}

	/**
	 * Signs a browser upload, so the image goes straight from the customer's machine to
	 * Cloudinary without passing through this server.
	 *
	 * <p>A large image must not transit the API: a 5 MB upload is 5 MB of our bandwidth and
	 * connection time for something Cloudinary will store anyway.
	 *
	 * <p>Every parameter the browser will send is included in the signature, so a client cannot
	 * add one of its own — otherwise an attacker could sign {@code folder=a} and write into an
	 * arbitrary shop's directory.
	 *
	 * @param folder the shop-scoped destination, e.g. {@code shop/{shopId}/products/{productId}}
	 */
	public UploadSignature signUpload(String folder) {
		requireShopFolder(folder);

		long timestamp = System.currentTimeMillis() / 1000;
		Map<String, Object> params = new HashMap<>();
		params.put("timestamp", timestamp);
		params.put("folder", folder);

		// The SDK sorts the parameters and appends the secret before hashing, which is exactly
		// what Cloudinary's server does. Hand-rolling this is where "Invalid Signature" comes from.
		String signature = client().apiSignRequest(params, properties.apiSecret());

		return new UploadSignature(timestamp, signature, properties.cloudName(), folder,
				properties.maxUploadBytes(), properties.maxDimension());
	}

	/**
	 * Uploads from the server, used for fixtures and for assets the server itself produces.
	 *
	 * <p>The product path is the browser-signed upload above; this exists so tests and internal
	 * tooling can push an image without a browser.
	 */
	public UploadResult upload(String folder, byte[] bytes, String filename) {
		requireShopFolder(folder);
		if (bytes == null || bytes.length == 0) {
			throw new MediaException("Refusing to upload an empty file");
		}
		if (bytes.length > properties.maxUploadBytes()) {
			throw new MediaException(
					"File is %d bytes, limit is %d".formatted(bytes.length, properties.maxUploadBytes()));
		}

		Map<String, Object> options = new HashMap<>();
		options.put("folder", folder);
		String publicId = stripExtension(filename);
		if (publicId != null) {
			options.put("public_id", publicId);
		}
		// Overwrite rather than accumulate: a shop replacing a logo should not leave the old
		// one in the account paying for storage forever.
		options.put("overwrite", true);
		options.put("resource_type", "auto");

		try {
			Map response = client().uploader().upload(bytes, options);
			return toResult(String.valueOf(response.getOrDefault("folder", folder)), response);
		} catch (Exception e) {
			log.error("Cloudinary upload failed for folder {}", folder, e);
			throw new MediaException("Upload failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Deletes an asset.
	 *
	 * <p>Only possible because {@code public_id} is stored (D19). With a URL alone the image
	 * would be undeletable and would accumulate in the account forever.
	 */
	public void delete(String publicId) {
		if (publicId == null || publicId.isBlank()) {
			return;
		}
		try {
			Map response = client().uploader().destroy(publicId, Map.of());
			String result = String.valueOf(response.get("result"));
			if (!"ok".equals(result) && !"not found".equals(result)) {
				// "not found" is success for our purposes: the goal is absence.
				log.warn("Cloudinary delete of {} returned {}", publicId, result);
			}
		} catch (Exception e) {
			// A failed delete must not roll back the database change that made it redundant.
			// The asset becomes an orphan to be reaped later, which is far better than a product
			// save that fails because a network call did.
			log.warn("Cloudinary delete failed for {}: {}", publicId, e.getMessage());
		}
	}

	/**
	 * Every asset in a folder, so a shop's media library is one call and a shop's assets can be
	 * removed in one sweep.
	 */
	public List<String> listPublicIds(String folder) {
		requireShopFolder(folder);
		try {
			// api().resources() is the admin-API call; the 1.x uploader has no list method.
			Map response = client().api().resources(
					Map.of("folder", folder, "type", "upload", "max_results", 500));
			Object resources = response.get("resources");
			if (!(resources instanceof List<?> list)) {
				return List.of();
			}
			return list.stream()
					.filter(Map.class::isInstance)
					.map(Map.class::cast)
					.map(r -> String.valueOf(r.get("public_id")))
					.toList();
		} catch (Exception e) {
			log.warn("Could not list Cloudinary folder {}: {}", folder, e.getMessage());
			return List.of();
		}
	}

	/**
	 * A transformed delivery URL.
	 *
	 * <p>Width-capped rather than quality-tuned, because the common failure is a shop uploading
	 * a 6000px image that every customer downloads in full. {@code crop: limit} means a small
	 * image is never upscaled, and {@code format: auto} lets Cloudinary serve WebP or AVIF.
	 */
	public String deliveryUrl(String publicId, int maxWidth) {
		if (publicId == null || publicId.isBlank()) {
			return null;
		}
		int width = Math.min(Math.max(maxWidth, 1), properties.maxDimension());
		var transformation = new com.cloudinary.Transformation()
				.width(width)
				.crop("limit")      // never upscales a small image
				.quality("auto")
				.fetchFormat("auto");   // WebP/AVIF where the browser supports it
		// secure() lives on Url, not on Transformation.
		return client().url().type("upload").secure(true)
				.transformation(transformation).generate(publicId);
	}

	/**
	 * The folder an asset belongs in, derived from its public_id.
	 *
	 * <p>Cloudinary encodes the folder into the public_id as a path, so this is a string split
	 * rather than a stored column. Storing it as a column too would be a second source of truth
	 * that can disagree.
	 */
	public static String folderOf(String publicId) {
		if (publicId == null) {
			return null;
		}
		int lastSlash = publicId.lastIndexOf('/');
		return lastSlash == -1 ? null : publicId.substring(0, lastSlash);
	}

	/**
	 * Enforces the {@code shop/...} convention (D19).
	 *
	 * <p>The convention is what makes a shop's media listable and its assets deletable in one
	 * call, so it is checked rather than trusted. A path containing {@code ..} would otherwise
	 * let a shop write outside its own directory.
	 */
	private void requireShopFolder(String folder) {
		if (folder == null || folder.isBlank()) {
			throw new IllegalArgumentException("An upload folder is required");
		}
		if (!folder.startsWith("shop/")) {
			throw new IllegalArgumentException("Uploads must go under shop/..., got: " + folder);
		}
		if (folder.contains("..")) {
			throw new IllegalArgumentException("Upload folder must not contain '..': " + folder);
		}
	}

	private UploadResult toResult(String folder, Map response) {
		return new UploadResult(
				String.valueOf(response.get("public_id")),
				String.valueOf(response.get("secure_url")),
				String.valueOf(response.get("url")),
				folder,
				asInteger(response.get("width")),
				asInteger(response.get("height")),
				asInteger(response.get("bytes")),
				response.get("format") == null ? null : String.valueOf(response.get("format")));
	}

	private static Integer asInteger(Object value) {
		return value instanceof Number n ? n.intValue() : null;
	}

	/** Cloudinary stores a public_id without an extension. */
	private static String stripExtension(String filename) {
		if (filename == null || filename.isBlank()) {
			return null;
		}
		int dot = filename.lastIndexOf('.');
		return dot > 0 ? filename.substring(0, dot) : filename;
	}

	/**
	 * What the browser needs to upload directly.
	 *
	 * @param maxBytes the server's limit, so the browser can reject a large file before
	 *                 spending the user's bandwidth on an upload that would be refused
	 */
	public record UploadSignature(
			long timestamp,
			String signature,
			String cloudName,
			String folder,
			long maxBytes,
			int maxDimension) {
	}

	/** What Cloudinary returned, normalised into what {@code media} stores (D19). */
	public record UploadResult(
			String publicId,
			String secureUrl,
			String url,
			String folder,
			Integer width,
			Integer height,
			Integer bytes,
			String format) {
	}

	/** A media operation that failed. Maps to HTTP 400/502. */
	public static class MediaException extends RuntimeException {
		public MediaException(String message) {
			super(message);
		}

		public MediaException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
