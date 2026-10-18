package com.storefront.store.social;

import java.net.URLEncoder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

/**
 * Shareable links and the WhatsApp button (Phase 8 — the multi-channel requirement).
 *
 * <p><b>Links are built from configuration, never hardcoded.</b> The storefront's base URL is a
 * setting ({@code store.storefront.base-url}), because a link built with {@code localhost} baked in
 * is a link that shares {@code localhost} with every customer, and the failure only shows up when
 * somebody pastes it into a real chat.
 *
 * <p><b>The WhatsApp link carries the product in its text, not as a URL parameter.</b>
 * {@code wa.me} has no structured product field, so the only place a product name can live in a
 * WhatsApp message is the body. Building it as a deep link to the storefront with a UTM-tagged URL in
 * the body means the shop's owner can see which of their shared links actually brought a visitor —
 * which is the difference between a share button and a channel.
 *
 * <p><b>Attribution is recorded, because sharing without measuring is decoration.</b> A click on a
 * shared product records which link brought it, and an order placed afterwards can be traced back to
 * the link. Without that, "we added a WhatsApp button" is a claim nobody can check.
 */
@Service
public class ShareService {

	/**
	 * The campaign name on every link this service builds.
	 *
	 * <p>Fixed rather than passed in, because the value of the attribution is comparing channels
	 * against each other and a caller-chosen campaign name lets one be renamed per link until the
	 * comparison means nothing.
	 */
	public static final String CAMPAIGN = "share";

	/** A share-click panel over five years is a full-table scan of somebody's link farm. */
	private static final int MAX_WINDOW_DAYS = 365;

	private final ProductRepository products;
	private final ShopRepository shops;
	private final ShareClickRepository clicks;
	private final String storefrontBaseUrl;

	public ShareService(ProductRepository products, ShopRepository shops, ShareClickRepository clicks,
			@Value("${store.storefront.base-url}") String storefrontBaseUrl) {
		this.products = products;
		this.shops = shops;
		this.clicks = clicks;
		// Trailing slash trimmed so joining never produces `//`, which some hosts treat as a
		// different path and some CDNs cache separately.
		this.storefrontBaseUrl = storefrontBaseUrl.endsWith("/")
				? storefrontBaseUrl.substring(0, storefrontBaseUrl.length() - 1)
				: storefrontBaseUrl;
	}

	// ------------------------------------------------------------------ links

	/** Everything a shop needs to share one product. */
	@Transactional(readOnly = true)
	public ProductShare shareForProduct(UUID productId) {
		Product product = products.findById(productId)
				.orElseThrow(() -> new IllegalArgumentException("No product " + productId));

		Shop shop = shops.findById(product.getShopId()).orElseThrow();

		String path = "/product/" + product.getSlug();
		String url = link(path, CAMPAIGN, "product");

		return new ProductShare(
				product.getId(), product.getName(), product.getSlug(),
				shop.getId(), shop.getName(), shop.getSlug(),
				url,
				whatsapp(product.getName(), url),
				messenger(product.getName(), url),
				viber(product.getName(), url),
				copyText(product.getName(), url));
	}

	/** Everything a shop needs to share itself. */
	@Transactional(readOnly = true)
	public ShopShare shareForShop(UUID shopId) {
		Shop shop = shops.findById(shopId)
				.orElseThrow(() -> new IllegalArgumentException("No shop " + shopId));

		String url = link("/shop/" + shop.getSlug(), CAMPAIGN, "shop");

		return new ShopShare(
				shop.getId(), shop.getName(), shop.getSlug(), url,
				whatsapp(shop.getName(), url),
				facebook(url),
				whatsappGroupInvite(shop.getName(), shop.getSlug()));
	}

	/**
	 * A storefront link with campaign attribution.
	 *
	 * <p>{@code utm_source} is the channel and {@code utm_medium} the surface, so "shared on WhatsApp
	 * from the product page" and "posted on Facebook" are separable later rather than both collapsing
	 * into one undifferentiated traffic number.
	 */
	private String link(String path, String source, String medium) {
		return storefrontBaseUrl + path
				+ "?utm_source=" + encode(source)
				+ "&utm_medium=" + encode(medium)
				+ "&utm_campaign=" + encode(CAMPAIGN);
	}

	/**
	 * The text to copy with the link.
	 *
	 * <p>A product name on its own line, then the link. A bare URL in a chat message reads as a
	 * possibly-spammy link; a line of text above it reads as a recommendation, which is the whole
	 * reason somebody is sharing it.
	 */
	private String copyText(String name, String url) {
		return name + System.lineSeparator() + url;
	}

	// ------------------------------------------------------------------ channels

	/**
	 * {@code wa.me} with the message pre-filled.
	 *
	 * <p>No phone number: {@code wa.me} opens the contact picker, which is what a shop wants. A
	 * number would mean sharing to one person, and a link with a number baked in cannot be posted in
	 * a group at all.
	 *
	 * <p>Encoded with {@link StandardCharsets#UTF_8} explicitly, because {@code URLEncoder} defaults
	 * to the platform charset and a Nepali product name encodes differently depending on where the
	 * server is running.
	 */
	private String whatsapp(String name, String url) {
		return "https://wa.me/?text=" + encode(copyText(name, url));
	}

	/** Facebook's sharer dialog. Facebook fetches its own preview, so only the URL is sent. */
	private String facebook(String url) {
		return "https://www.facebook.com/sharer/sharer.php?u=" + encode(url);
	}

	/**
	 * Viber's share dialog.
	 *
	 * <p>Viber takes its text as a query parameter too, and it is popular in the market this
	 * platform serves, so a share sheet without it would be missing the channel most of a shop's
	 * customers actually use.
	 */
	private String viber(String name, String url) {
		return "https://viber.com/share?title=" + encode(name) + "&url=" + encode(url);
	}

	/**
	 * Messenger's link.
	 *
	 * <p>Sent as {@code app_id} plus link rather than the newer {@code link} parameter, because
	 * {@code link} is only honoured for pages with an app configured and fails silently otherwise —
	 * which looks like a dead button rather than a missing configuration.
	 */
	private String messenger(String name, String url) {
		return "https://www.facebook.com/dialog/send?link=" + encode(url)
				+ "&redirect_uri=" + encode(url)
				+ "&app_id=966242223397117"
				+ "&to=%20"
				+ "&message=" + encode(copyText(name, url));
	}

	/**
	 * A WhatsApp group invite for a shop.
	 *
	 * <p><b>This is not the {@code wa.me} join-a-group link, and the difference matters.</b>
	 * {@code chat.whatsapp.com} links are created inside the WhatsApp app by an admin and expire; they
	 * cannot be minted by a server. So this returns the plain channel with the shop named, and the
	 * screen says a group invite has to be copied out of WhatsApp. Pretending otherwise would hand
	 * every shop a link that 404s.
	 */
	private String whatsappGroupInvite(String shopName, String shopSlug) {
		return link("/shop/" + shopSlug, "whatsapp", "group");
	}

	// ------------------------------------------------------------------ attribution

	/**
	 * Records that somebody arrived through a shared link.
	 *
	 * <p><b>Called by the storefront, not by this API's own pages.</b> The click happens on the
	 * storefront, so this is the API endpoint that lets it record where the visitor came from. It is
	 * unauthenticated by necessity — a visitor arriving from a link has no token — and therefore
	 * takes only what it can safely be told: which product, and which channel.
	 *
	 * <p><b>It records, it does not decide.</b> Whether the click later became an order is the
	 * storefront's checkout to attribute; this is the first half of that trail and nothing more.
	 */
	@Transactional
	public void recordClick(UUID productId, String source) {
		clicks.record(productId, source == null || source.isBlank() ? "unknown" : source.trim());
	}

	/**
	 * Clicks per channel for one product, over a window.
	 *
	 * <p><b>The window is bounded here rather than in the controller</b>, so the rule holds for every
	 * caller. A controller-level check is a check the next caller does not get — which is the same
	 * reasoning as recording audits in services rather than controllers.
	 */
	@Transactional(readOnly = true)
	public List<ShareClickRepository.SourceCount> clickCountsFor(UUID productId, int days) {
		if (days < 1 || days > MAX_WINDOW_DAYS) {
			throw new IllegalArgumentException(
					"Ask for between 1 and " + MAX_WINDOW_DAYS + " days, not " + days + ".");
		}

		return clicks.countsFor(productId, Instant.now().minus(days, ChronoUnit.DAYS));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ types

	/**
	 * One product's share sheet.
	 *
	 * <p>Every channel is a field rather than something the front end assembles, so the URL encoding
	 * lives in one place — a link built in the browser with a product name containing an ampersand is
	 * broken, and it is broken only for the products whose names happen to contain one.
	 */
	public record ProductShare(
			UUID productId,
			String productName,
			String slug,
			UUID shopId,
			String shopName,
			String shopSlug,
			String url,
			String whatsapp,
			String messenger,
			String viber,
			String copyText) {

		/** The channels, for a screen that renders a row of buttons. */
		public Map<String, String> channels() {
			Map<String, String> links = new LinkedHashMap<>();
			links.put("whatsapp", whatsapp);
			links.put("messenger", messenger);
			if (viber != null) {
				links.put("viber", viber);
			}
			return links;
		}
	}

	public record ShopShare(
			UUID shopId, String shopName, String slug, String url,
			String whatsapp, String facebook, String whatsappGroup) {
	}
}