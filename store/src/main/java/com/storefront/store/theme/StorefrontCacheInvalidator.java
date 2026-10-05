package com.storefront.store.theme;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.storefront.store.checkout.StorefrontProperties;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tells the storefront to drop its cached copy of a shop's page after the theme changes.
 *
 * <p>The storefront is server-rendered with a revalidation window, because product pages have to be
 * indexable and share-previewable (D1). That window is correct under load and wrong at the moment an
 * owner publishes: without this, the console reports "Live. Your storefront is updated." while the
 * link the owner is about to click still serves the previous design for up to a minute. The publish
 * did work; nothing on screen says so, so the owner's conclusion is that it failed.
 *
 * <p>Best-effort by design. A storefront that cannot be told to revalidate still serves correct
 * HTML within its window, so a failure here degrades to "slightly stale for a minute" — never to an
 * error on the publish that succeeded, and never to a rollback of a committed theme.
 */
@Component
public class StorefrontCacheInvalidator {

	private static final Logger log = LoggerFactory.getLogger(StorefrontCacheInvalidator.class);

	private final StorefrontProperties storefront;
	private final String revalidateSecret;
	private final HttpClient http;
	private final ObjectMapper mapper;

	public StorefrontCacheInvalidator(StorefrontProperties storefront,
			@Value("${store.storefront.revalidate-secret:}") String revalidateSecret,
			ObjectMapper mapper) {
		this.storefront = storefront;
		this.revalidateSecret = revalidateSecret;
		this.mapper = mapper;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(3))
				.build();
	}

	/**
	 * Purges one shop's storefront. Never throws.
	 *
	 * @param shopSlug the shop whose page is now stale
	 */
	public void invalidate(String shopSlug) {
		if (revalidateSecret == null || revalidateSecret.isBlank()) {
			// Not an error: a developer running the API without the storefront does not get cache
			// invalidation, and the storefront's own revalidation window still keeps it correct.
			log.debug("No revalidate secret configured; skipping storefront cache invalidation");
			return;
		}

		String url = storefront.baseUrl() + "/api/revalidate";
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.header("Content-Type", "application/json")
					.header("x-revalidate-secret", revalidateSecret)
					.timeout(Duration.ofSeconds(5))
					.POST(HttpRequest.BodyPublishers.ofString(
							mapper.writeValueAsString(java.util.Map.of("shopSlug", shopSlug))))
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				log.info("Storefront cache invalidated for shop {}", shopSlug);
			} else {
				log.warn("Storefront revalidate for shop {} returned HTTP {}", shopSlug,
						response.statusCode());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Storefront revalidate for shop {} was interrupted", shopSlug);
		} catch (Exception e) {
			// Swallowed on purpose. The theme is already committed; failing the publish here would
			// tell the owner their update did not save, which is both untrue and more damaging than a
			// page that refreshes a minute later on its own.
			log.warn("Could not invalidate storefront cache for shop {}: {}", shopSlug, e.toString());
		}
	}
}