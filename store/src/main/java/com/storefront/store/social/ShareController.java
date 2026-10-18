package com.storefront.store.social;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * Sharing a shop or a product (Phase 8 — the multi-channel requirement).
 *
 * <p><b>Two audiences on one controller, and the split is enforced rather than documented.</b> The
 * {@code /share/...} endpoints are owner-facing: they produce a link for a product the caller owns.
 * The {@code /click} endpoint is storefront-facing and unauthenticated, because a visitor arriving
 * from a shared link has no token — which is exactly why it takes only a product id and a channel,
 * and why it can do nothing else.
 *
 * <p><b>The click endpoint is rate-limited per source, not rejected.</b> Somebody else's link being
 * scraped should not turn into somebody's dead shop; the write is dropped and the visitor still
 * reaches the page.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Social selling",
		description = "Shareable product and shop links, the WhatsApp button, and click attribution")
@RequiredArgsConstructor
public class ShareController {

	private final ShareService sharing;

	/**
	 * The share sheet for one product.
	 *
	 * <p><b>The shop is in the path, and {@code @tenantGuard.owns(#shopId)} is what makes that
	 * safe.</b> The id being a path parameter is not a weakness as long as it is checked — which is
	 * the reasoning behind every other shop-scoped endpoint here. An unchecked path parameter is
	 * just a way to read somebody else's data by editing a URL, and this one was that until the
	 * guard was added.
	 */
	@GetMapping("/shops/{shopId}/products/{productId}/share")
	@PreAuthorize("@tenantGuard.owns(#shopId) or hasRole('ROLE_ADMIN')")
	@Operation(summary = "Every share link for one product, ready to use",
			description = "WhatsApp, Messenger, Viber and copy-to-clipboard text, all attributed.")
	public ShareService.ProductShare forProduct(@PathVariable UUID shopId, @PathVariable UUID productId) {
		return sharing.shareForProduct(productId);
	}

	/** The share sheet for a shop itself. */
	@GetMapping("/shops/{shopId}/share")
	@PreAuthorize("@tenantGuard.owns(#shopId) or hasRole('ROLE_ADMIN')")
	@Operation(summary = "Every share link for the shop itself")
	public ShareService.ShopShare forShop(@PathVariable UUID shopId) {
		return sharing.shareForShop(shopId);
	}

	/**
	 * Records that a shared link brought a visitor.
	 *
	 * <p><b>Unauthenticated on purpose, and therefore the most dangerous endpoint in this package.</b>
	 * A visitor following a WhatsApp link has no session. So this accepts only a product id and a
	 * free-text channel, writes one append-only row, and can be made to do nothing else — it cannot
	 * read anything, and it cannot touch an order.
	 *
	 * <p>Always 204, whether or not a row was written. Returning 429 would turn somebody else's link
	 * spam into a visitor staring at an error page instead of the product.
	 */
	@PostMapping("/share/click")
	@Operation(summary = "Record a click on a shared link",
			description = "Unauthenticated: a visitor from a shared link has no token. Returns 204.")
	public org.springframework.http.ResponseEntity<Void> click(@Valid @RequestBody ClickBody body) {
		sharing.recordClick(body.productId(), body.source());
		return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}

	/** How many visits each channel has brought to a product. */
	@GetMapping("/shops/{shopId}/products/{productId}/share/stats")
	@PreAuthorize("@tenantGuard.owns(#shopId) or hasRole('ROLE_ADMIN')")
	@Operation(summary = "Clicks per channel for one product, since a window")
	public List<ShareClickRepository.SourceCount> stats(
			@PathVariable UUID shopId,
			@PathVariable UUID productId,
			@RequestParam(defaultValue = "30") int days) {

		// The window bound lives in the service, so every caller gets it -- not just this one.
		return sharing.clickCountsFor(productId, days);
	}

	/** The two fields this endpoint accepts. Anything more would be an open door. */
	public record ClickBody(@NotNull UUID productId, @NotNull String source) {
	}
}