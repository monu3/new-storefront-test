package com.storefront.store.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloudinary settings for media storage (D19).
 *
 * <p>Credentials live in the git-ignored {@code application-local.yml} for development. They are
 * {@code String}s rather than a nested credentials type so that a partially configured
 * environment produces a clear "not configured" error from {@link #isConfigured()} rather than a
 * startup failure with a null pointer.
 */
@ConfigurationProperties(prefix = "store.cloudinary")
public record CloudinaryProperties(
		String cloudName,
		String apiKey,
		String apiSecret,
		/** Upload size ceiling. A shop sending a 50 MB hero image is a cost for everyone. */
		long maxUploadBytes,
		/** Pixel ceiling. Beyond this the image is wasted bytes on every page load. */
		int maxDimension) {

	public CloudinaryProperties {
		if (maxUploadBytes <= 0) {
			maxUploadBytes = 5L * 1024 * 1024; // 5 MB
		}
		if (maxDimension <= 0) {
			maxDimension = 4000;
		}
	}

	/** True when all three credentials are present. */
	public boolean isConfigured() {
		return isPresent(cloudName) && isPresent(apiKey) && isPresent(apiSecret);
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}
