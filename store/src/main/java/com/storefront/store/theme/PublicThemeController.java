package com.storefront.store.theme;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The storefront's theme and the data its sections need (D8).
 *
 * <p>Server-side rendered (D1), so this endpoint is hit on every page view and must be cheap and
 * cacheable. It returns the theme document <em>and</em> the data the commerce sections resolve
 * against, in one request — the alternative is a storefront page issuing six calls before the
 * first product appears.
 *
 * <p><b>Nothing here reveals an unverified shop.</b> A hidden shop reads as 404, indistinguishable
 * from one that was never created.
 *
 * <p>{@code category-grid} with {@code source: "shop_categories"} resolves from the shop's
 * {@code shop_type} enum rather than a category table — the D17 payoff, and zero queries.
 */
@RestController
@RequestMapping("/api/v1/p")
@Tag(name = "Public storefront", description = "The theme document and the data its sections need")
@RequiredArgsConstructor
public class PublicThemeController {

	/** Products per commerce section. A storefront rendering 500 cards is unusable on a phone. */
	private static final int SECTION_PRODUCT_LIMIT = 24;

	private final ThemeService themes;
	private final ShopRepository shops;
	private final ProductRepository products;

	/**
	 * Everything a storefront page needs for one shop, in one request.
	 *
	 * <p>The section data is capped rather than complete. A shop with 1,000 products should render
	 * a homepage, not load a catalogue; the full grid is paginated by the catalogue endpoint.
	 */
	@GetMapping("/shops/{shopSlug}/storefront")
	@Operation(summary = "The shop's theme plus the data its sections render from",
			description = "One request per page view. Section data is capped; the catalogue is paginated separately.")
	public StorefrontData storefront(@PathVariable String shopSlug) {
		Shop shop = shops.findBySlugIgnoringCase(shopSlug)
				.filter(Shop::isVerified)
				// 404, not 403: a hidden shop must be indistinguishable from one that was never
				// created, or this endpoint enumerates every suspended shop on the platform.
				.orElseThrow(() -> new com.storefront.store.shop.ShopService.ShopNotFoundException(shopSlug));

		String config = themes.renderableConfig(shop);

		List<Product> featured = products.findByShopIdAndStatusAndDeletedAtIsNull(shop.getId(),
						Product.Status.ACTIVE,
						org.springframework.data.domain.PageRequest.of(0, SECTION_PRODUCT_LIMIT))
				.getContent().stream()
				.filter(Product::isFeatured)
				.toList();

		List<Product> latest = products.findByShopIdAndStatusAndDeletedAtIsNull(shop.getId(),
				Product.Status.ACTIVE,
				org.springframework.data.domain.PageRequest.of(0, SECTION_PRODUCT_LIMIT)).getContent();

		return new StorefrontData(
				shop.getSlug(),
				shop.getName(),
				shop.getDescription(),
				shop.getLogoUrl(),
				shop.getCoverUrl(),
				shop.getFollowerCount(),
				shop.getType().name(),
				config,
				themes.sectionCount(config),
				// Whether this is the shop's own theme or the platform default. The editor needs it;
				// a customer does not, and it is harmless.
				themes.hasPublishedTheme(shop),
				categoriesFor(shop),
				featured,
				latest,
				themes.assetsOf(shop, null).stream()
						.map(asset -> new AssetData(asset.getId(), asset.getKind(), asset.getHandle(),
								asset.getName(), asset.getConfig()))
						.toList());
	}

	/**
	 * The categories this shop sells, from the enum (D17).
	 *
	 * <p><b>Only categories the shop actually has products in.</b> A pharmacy with three products
	 * should show three categories on its storefront, not the five its vertical permits — a grid of
	 * mostly-empty tiles is worse than a smaller honest one.
	 */
	private List<CategoryTile> categoriesFor(Shop shop) {
		Map<ProductCategory, Long> counts = products
				.findByShopIdAndStatusAndDeletedAtIsNull(shop.getId(), Product.Status.ACTIVE,
						org.springframework.data.domain.Pageable.unpaged())
				.getContent().stream()
				.collect(Collectors.groupingBy(Product::getProductCategory, Collectors.counting()));

		return counts.entrySet().stream()
				.map(e -> new CategoryTile(e.getKey().name(), e.getKey().label(), e.getValue()))
				.sorted(java.util.Comparator.comparing(CategoryTile::label))
				.toList();
	}

	/** What a storefront page renders from. */
	public record StorefrontData(
			String shopSlug,
			String shopName,
			String description,
			String logoUrl,
			String coverImageUrl,
			int followerCount,
			String shopType,
			/** The theme document. Validated on write; safe to render. */
			String themeConfig,
			int sectionCount,
			boolean fromPublishedTheme,
			List<CategoryTile> categories,
			List<Product> featuredProducts,
			List<Product> latestProducts,
			List<AssetData> assets) {
	}

	public record CategoryTile(String code, String label, long productCount) {
	}

	public record AssetData(UUID id, ThemeAsset.Kind kind, String handle, String name,
			String config) {
	}
}
