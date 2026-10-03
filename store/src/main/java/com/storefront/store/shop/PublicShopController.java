package com.storefront.store.shop;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.CategoryValidator;
import com.storefront.store.catalog.ShopType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The public shop gallery.
 *
 * <p>Unauthenticated by design: this is the storefront the whole platform exists to feed. Every
 * query filters on {@code VERIFIED}, so an unverified or suspended shop is invisible here no
 * matter how its URL was guessed.
 */
@RestController
@RequestMapping("/api/v1/shops")
@Tag(name = "Public", description = "Public gallery and storefront reads")
@RequiredArgsConstructor
public class PublicShopController {

	/**
	 * Page size is capped. An unbounded {@code ?limit=} on a public endpoint is a cheap way to
	 * be asked for a million rows.
	 */
	private static final int MAX_PAGE_SIZE = 48;
	private static final int DEFAULT_PAGE_SIZE = 24;

	private final ShopRepository shops;
	private final ShopService shopService;
	private final CategoryValidator validator;

	@GetMapping
	@Operation(summary = "The gallery of verified shops",
			description = "Filter by shop type. Only verified shops appear.")
	public GalleryResponse gallery(
			@RequestParam(required = false) ShopType type,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int limit) {

		int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		int safePage = Math.max(page, 0);
		PageRequest pageable = PageRequest.of(safePage, safeLimit);

		Page<Shop> found = type == null
				? shops.findByStatusOrderByNameAsc(Shop.Status.VERIFIED, pageable)
				: shops.findByStatusAndShopTypeOrderByNameAsc(Shop.Status.VERIFIED, type.name(), pageable);

		return new GalleryResponse(
				found.getContent().stream().map(GalleryEntry::from).toList(),
				found.getNumber(),
				found.getSize(),
				found.getTotalElements(),
				found.getTotalPages());
	}

	@GetMapping("/{slug}")
	@Operation(summary = "One shop by slug",
			description = "Returns the shop's theme config so the storefront can render server-side.")
	public StorefrontResponse storefront(@PathVariable String slug) {
		Shop shop = shopService.getBySlug(slug);

		// A suspended shop keeps its data but must stop being reachable publicly. Returning 404
		// rather than 403 is deliberate: a suspended shop should simply not exist to a customer.
		if (!shop.isVerified()) {
			throw new ShopService.ShopNotFoundException(slug);
		}

		return StorefrontResponse.from(shop, validator.allowedFor(shop.getType()));
	}

	// ---------------------------------------------------------------- responses

	/**
	 * A page of gallery results.
	 *
	 * <p>Total count is included so the client can render "showing 24 of 130" without a second
	 * request. It is one COUNT query, which is a fair price for saving the round trip.
	 */
	public record GalleryResponse(
			List<GalleryEntry> shops,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}

	/**
	 * One card in the gallery.
	 *
	 * <p>Carries the shop type so the client can render the right icon and offer the right
	 * category links without a second request.
	 */
	public record GalleryEntry(
			UUID id,
			String slug,
			String name,
			String description,
			ShopType shopType,
			String logoUrl,
			String coverUrl,
			int followerCount) {

		static GalleryEntry from(Shop shop) {
			return new GalleryEntry(
					shop.getId(),
					shop.getSlug(),
					shop.getName(),
					shop.getDescription(),
					shop.getType(),
					shop.getLogoUrl(),
					shop.getCoverUrl(),
					shop.getFollowerCount());
		}
	}

	/**
	 * Everything one storefront needs for its first render.
	 *
	 * <p>Categories come from the shop's type rather than a table (D17/D18), so the navigation a
	 * customer sees is derived from the vertical and cannot drift out of sync with it.
	 *
	 * <p>Theme config is absent until Phase 3. It is included as a nullable field now so the
	 * response shape does not change when sections arrive.
	 */
	public record StorefrontResponse(
			UUID id,
			String slug,
			String name,
			String description,
			ShopType shopType,
			String logoUrl,
			String coverUrl,
			int followerCount,
			/** The categories this shop's vertical permits, in display order. */
			List<Category> categories,
			/** Null until the theme engine lands in Phase 3. */
			Map<String, Object> theme) {

		static StorefrontResponse from(Shop shop, java.util.Set<com.storefront.store.catalog.ProductCategory> categories) {
			return new StorefrontResponse(
					shop.getId(),
					shop.getSlug(),
					shop.getName(),
					shop.getDescription(),
					shop.getType(),
					shop.getLogoUrl(),
					shop.getCoverUrl(),
					shop.getFollowerCount(),
					categories.stream()
							.map(c -> new Category(c.name(), c.label(), c.slug()))
							.toList(),
					null);
		}
	}

	/**
	 * A category in a storefront's navigation.
	 *
	 * <p>Includes the slug because it is a URL segment ({@code /s/acme/c/medicine}). Derived from
	 * the enum rather than stored, so renaming a label can never orphan a bookmarked link.
	 */
	public record Category(String code, String label, String slug) {
	}
}
