package com.storefront.store.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.media.MediaAsset;
import com.storefront.store.media.MediaAssetRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The public catalogue: what a customer browsing a shop's storefront can read.
 *
 * <p>Server-side rendered (D1), so these are hit on every page view and must be cheap and
 * cacheable. They therefore return a deliberately narrow projection: a customer's product list
 * must never carry {@code cost_price_minor}, and the DTOs here have no field for it at all —
 * relying on a query filter to strip it would be one forgotten predicate away from leaking a
 * shop's margin to the public.
 *
 * <p><b>Nothing here reveals an unverified shop.</b> A shop that is not VERIFIED, or one that is
 * SUSPENDED, reads as 404 — the same as one that was never created. A 403 or a distinct message
 * would let anyone enumerate shops the platform is deliberately hiding.
 *
 * <p>Every method takes a {@code shopSlug}, not a shop id, because the storefront URL is
 * {@code /s/[shopSlug]} and the customer-facing identifier is the slug.
 */
@RestController
@RequestMapping("/api/v1/p")
@Tag(name = "Public storefront", description = "Server-rendered catalogue reads. No authentication.")
@Slf4j
@RequiredArgsConstructor
public class PublicProductController {

	/** A hard cap so a public endpoint cannot be asked for an entire large catalogue at once. */
	private static final int MAX_PAGE_SIZE = 60;

	private static final int MAX_RELATED = 12;

	/** Products returned per category group. A phone cannot usefully render more. */
	private static final int MAX_PRODUCTS_PER_GROUP = 12;

	private final ShopRepository shops;
	private final ProductRepository products;
	private final ProductVariantRepository variants;
	private final MediaAssetRepository media;

	/**
	 * One shop's published products.
	 *
	 * <p>Filters to ACTIVE and non-deleted at the repository level rather than in memory, so the
	 * pagination count is right — a filter applied after paging reports the wrong total.
	 */
	@GetMapping("/shops/{shopSlug}/products")
	@Operation(summary = "A shop's published products",
			description = "Paged, newest first. Filter by category or featured.")
	public ProductPage list(
			@PathVariable String shopSlug,
			@RequestParam(required = false) ProductCategory category,
			@RequestParam(required = false) Boolean featured,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		Shop shop = requireVisibleShop(shopSlug);
		Pageable pageable = PageRequest.of(Math.max(page, 0),
				Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
				Sort.by(Sort.Direction.DESC, "createdAt"));

		var result = featured != null && featured
				? products.findByShopIdAndStatusAndDeletedAtIsNull(shop.getId(),
						Product.Status.ACTIVE, pageable)
						.getContent().stream()
						.filter(Product::isFeatured)
						.map(this::toSummary)
						.toList()
				: category != null
						? products.findByShopIdAndProductCategoryAndStatusAndDeletedAtIsNull(
								shop.getId(), category, Product.Status.ACTIVE, pageable)
								.getContent().stream().map(this::toSummary).toList()
						: products.findByShopIdAndStatusAndDeletedAtIsNull(
								shop.getId(), Product.Status.ACTIVE, pageable)
								.getContent().stream().map(this::toSummary).toList();

		return new ProductPage(result, page, size, shop.getName(), category);
	}

	/** One product, by its slug within the shop. */
	@GetMapping("/shops/{shopSlug}/products/{productSlug}")
	@Operation(summary = "One product, with its images and purchasable variants")
	public ProductDetail detail(@PathVariable String shopSlug, @PathVariable String productSlug) {
		Shop shop = requireVisibleShop(shopSlug);

		Product product = products.findByShopIdAndSlugAndDeletedAtIsNull(shop.getId(), productSlug)
				.filter(Product::isVisible)
				.orElseThrow(() -> new ProductNotFoundBySlugException(productSlug));

		List<ProductVariant> purchasable =
				variants.findByProductIdAndActiveTrueOrderBySortOrderAscNameAsc(product.getId());
		List<MediaAsset> images = media.findByShopIdAndProductIdOrderBySortOrderAsc(
				shop.getId(), product.getId());

		return new ProductDetail(
				shop.getSlug(),
				shop.getName(),
				product.getId(),
				product.getName(),
				product.getSlug(),
				product.getProductCategory(),
				product.getProductCategory().label(),
				product.getShortDescription(),
				product.getDescription(),
				product.getBasePriceMinor(),
				product.getCompareAtMinor(),
				product.hasDiscount(),
				product.getSoldCount() > 0,
				product.isAllowBackorder(),
				images.stream().map(this::toImage).toList(),
				purchasable.stream()
						.map(v -> ProductVariant.VariantView.from(v))
						.toList(),
				relatedProducts(shop, product));
	}

	/**
	 * The shop's catalogue, grouped by category.
	 *
	 * <p>One request returns everything a storefront needs to render its product pages: every
	 * group, and within each group the products. Doing this per category would be one request per
	 * category, and a grocery with six categories would be six round trips before the first
	 * product appeared.
	 *
	 * <p>Bounded deliberately. A shop with 500 SKUs returns at most
	 * {@link #MAX_PRODUCTS_PER_GROUP} per group, because a storefront that renders 500 cards at
	 * once is unusable on a phone — and a customer scrolling past 500 products to find rice has
	 * already decided they are on the wrong shop. The full catalogue is paginated by
	 * {@link #list}.
	 */
	@GetMapping("/shops/{shopSlug}/catalogue")
	@Operation(summary = "The whole catalogue, grouped by category",
			description = "One request for every category group. Capped per group for phone rendering.")
	public GroupedCatalogue catalogue(@PathVariable String shopSlug) {
		Shop shop = requireVisibleShop(shopSlug);

		var active = products.findByShopIdAndStatusAndDeletedAtIsNull(shop.getId(),
				Product.Status.ACTIVE, Pageable.unpaged()).getContent();

		// Featured first, then best-selling. A storefront's job is to show what the shop most
		// wants to sell above the fold, and that is not the same as the newest products.
		Map<ProductCategory, List<ProductSummary>> grouped = active.stream()
				.sorted(Comparator
						.comparingInt((Product p) -> p.isFeatured() ? 0 : 1)
						.thenComparing(Comparator.comparingInt(Product::getSoldCount).reversed()))
				.collect(java.util.stream.Collectors.groupingBy(
						Product::getProductCategory,
						java.util.stream.Collectors.collectingAndThen(
								java.util.stream.Collectors.toList(),
								list -> list.stream()
										.limit(MAX_PRODUCTS_PER_GROUP)
										.map(this::toSummary)
										.toList())));

		List<CategoryGroup> groups = grouped.entrySet().stream()
				.map(e -> new CategoryGroup(e.getKey(), e.getKey().label(),
						countIn(active, e.getKey()), e.getValue().size(), e.getValue()))
				.sorted(Comparator.comparing(CategoryGroup::label))
				.toList();

		return new GroupedCatalogue(shop.getSlug(), shop.getName(),
				shop.getFollowerCount(), groups, active.size());
	}

	/**
	 * The categories this shop actually sells, with a count each.
	 *
	 * <p>Served from live products rather than the shop type's full allowed set: a shop with three
	 * products should show three categories on its storefront, not the ten its vertical permits.
	 */
	@GetMapping("/shops/{shopSlug}/categories")
	@Operation(summary = "Categories this shop has published products in, with counts")
	public List<CategoryCount> categories(@PathVariable String shopSlug) {
		Shop shop = requireVisibleShop(shopSlug);

		return products.findByShopIdAndStatusAndDeletedAtIsNull(shop.getId(), Product.Status.ACTIVE,
						Pageable.unpaged())
				.getContent().stream()
				.collect(java.util.stream.Collectors.groupingBy(
						Product::getProductCategory,
						java.util.stream.Collectors.counting()))
				.entrySet().stream()
				.map(e -> new CategoryCount(e.getKey(), e.getKey().label(), e.getValue()))
				.sorted(java.util.Comparator.comparing(CategoryCount::label))
				.toList();
	}

	/** "Customers also bought" — Phase 5 does this properly; Phase 1 keeps the block populated. */
	/** How many the shop really has in a category, so the UI can say "12 of 60". */
	private int countIn(List<Product> products, ProductCategory category) {
		return (int) products.stream().filter(p -> p.getProductCategory() == category).count();
	}

	private List<ProductSummary> relatedProducts(Shop shop, Product product) {
		return products
				.findByShopIdAndProductCategoryAndStatusAndDeletedAtIsNull(
						shop.getId(), product.getProductCategory(), Product.Status.ACTIVE,
						PageRequest.of(0, MAX_RELATED + 1))
				.getContent().stream()
				.filter(p -> !p.getId().equals(product.getId()))
				.limit(MAX_RELATED)
				.map(this::toSummary)
				.toList();
	}

	private Shop requireVisibleShop(String slug) {
		return shops.findBySlugIgnoringCase(slug)
				.filter(Shop::isVerified)
				// 404, not 403: a hidden shop must be indistinguishable from one that was never
				// created, or the storefront endpoint enumerates every suspended shop on the
				// platform.
				.orElseThrow(() -> new com.storefront.store.shop.ShopService.ShopNotFoundException(slug));
	}

	private ProductSummary toSummary(Product p) {
		return new ProductSummary(
				p.getId(), p.getSlug(), p.getName(), p.getProductCategory(),
				p.getProductCategory().label(), p.getShortDescription(),
				p.getBasePriceMinor(), p.getCompareAtMinor(), p.hasDiscount(), p.isFeatured());
	}

	private ProductImage toImage(MediaAsset asset) {
		return new ProductImage(asset.getId().toString(), asset.displayUrl(),
				asset.getWidth(), asset.getHeight(), asset.getAltText());
	}

	// ------------------------------------------------------------------ responses

	/**
	 * A product card.
	 *
	 * <p>No cost price, no view count, no inventory level. A storefront that displayed "only 2
	 * left" would both leak throughput and create pressure to buy; the availability flag below
	 * is the honest summary.
	 */
	public record ProductSummary(
			UUID id,
			String slug,
			String name,
			ProductCategory category,
			String categoryLabel,
			String shortDescription,
			long price,
			Long compareAt,
			boolean hasDiscount,
			boolean featured) {
	}

	public record ProductImage(
			String id,
			String url,
			Integer width,
			Integer height,
			String altText) {
	}

	public record ProductDetail(
			String shopSlug,
			String shopName,
			UUID id,
			String name,
			String slug,
			ProductCategory category,
			String categoryLabel,
			String shortDescription,
			String description,
			long price,
			Long compareAt,
			boolean hasDiscount,
			boolean popular,
			boolean allowBackorder,
			List<ProductImage> images,
			List<ProductVariant.VariantView> variants,
			List<ProductSummary> related) {
	}

	public record CategoryCount(ProductCategory category, String label, long count) {
	}

	/**
	 * One category and its products.
	 *
	 * @param total how many the shop actually has in this category, so the UI can say
	 *              "showing 12 of 60" rather than implying that 12 is all there is
	 * @param shown how many are included, which is less than {@code total} for a large category
	 */
	public record CategoryGroup(ProductCategory category, String label, int total, int shown,
			List<ProductSummary> products) {

		/**
		 * True when the cap hid something, so the UI can offer "see all".
		 *
		 * <p>Named {@code isTruncated} rather than {@code truncated} because Jackson serialises a
		 * record's components plus its {@code isX}/{@code getX} accessors — a bare
		 * {@code truncated()} is silently absent from the JSON, which is the kind of thing that
		 * only shows up as an undefined in the frontend.
		 */
		public boolean isTruncated() {
			return shown < total;
		}
	}

	/**
	 * @param totalProducts across all groups, so a storefront can show a count without counting
	 *                     the groups itself
	 */
	public record GroupedCatalogue(
			String shopSlug,
			String shopName,
			int followerCount,
			List<CategoryGroup> groups,
			int totalProducts) {
	}

	/** Maps to HTTP 404, same as a missing product id: neither confirms nor denies by status. */
	public static class ProductNotFoundBySlugException extends RuntimeException {
		public ProductNotFoundBySlugException(String slug) {
			super("No product '" + slug + "' in this shop");
		}
	}

	/**
	 * A page of products.
	 *
	 * @param total null when it was not counted. The featured filter short-circuits before the
	 *              repository counts, and reporting a total that was never computed would be a
	 *              number the storefront would render as "of 0".
	 */
	public record ProductPage(List<ProductSummary> products, int page, int size, String shopName,
			ProductCategory category) {

		public int returned() {
			return products.size();
		}
	}
}
