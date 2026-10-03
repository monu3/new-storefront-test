package com.storefront.store.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Product and variant management, for the owner's own shop.
 *
 * <p>No endpoint here accepts a shop id. The shop comes from the token via
 * {@link TenantGuard#requireOwnedShop()} (D35), so there is no request a caller could craft to
 * reach another shop's catalogue — the question simply is not expressible.
 *
 * <p>Ownership of a product or variant is then checked in {@link ProductService}. Two layers
 * rather than one because the service must be safe regardless of who calls it: the console calls
 * it, and so will the order flow when it snapshots a line item.
 */
@RestController
@RequestMapping("/api/v1/shop/products")
@Tag(name = "Products", description = "The owner's own catalogue: products, variants, pricing")
@PreAuthorize("hasRole('SHOP')")
@RequiredArgsConstructor
public class ProductController {

	/** A hard cap, so a client cannot ask for the whole catalogue in one response. */
	private static final int MAX_PAGE_SIZE = 100;

	private final ProductService products;
	private final TenantGuard tenantGuard;

	// ------------------------------------------------------------------ products

	@PostMapping
	@Operation(summary = "Create a draft product",
			description = "Always created as DRAFT. Publishing is a separate, explicit step.")
	public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.createDraft(shop, request.name(), request.category(),
				request.slug(), request.shortDescription(), request.description());
		return ProductResponse.from(product, products.variantsOf(product));
	}

	/**
	 * The owner's products.
	 *
	 * <p>Defaults to newest first. The storefront's own listing is a separate public endpoint
	 * with its own ordering — this one is the console's management table.
	 */
	@GetMapping
	@Operation(summary = "List the shop's products",
			description = "Paged. Filter by status or category, or search by name.")
	public PageResponse<ProductSummary> list(
			@RequestParam(required = false) Product.Status status,
			@RequestParam(required = false) ProductCategory category,
			@RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) int size) {

		Shop shop = tenantGuard.requireOwnedShop();
		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
				Sort.by(Sort.Direction.DESC, "createdAt"));

		Page<Product> result = search != null && !search.isBlank()
				? products.search(shop, search, pageable)
				: products.listForShop(shop, category, status, pageable);

		return PageResponse.from(result, ProductSummary::from);
	}

	/**
	 * One product, with its variants.
	 *
	 * <p>Variants are included because every product needs at least one before it can be
	 * published, so the edit screen always needs both halves.
	 */
	@GetMapping("/{productId}")
	@Operation(summary = "One product and its variants")
	public ProductResponse get(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwned(shop, productId);
		return ProductResponse.from(product, products.variantsOf(product));
	}

	@PatchMapping("/{productId}")
	@Operation(summary = "Update a product",
			description = "Copy and pricing fields only. The category has its own endpoint.")
	public ProductResponse update(@PathVariable UUID productId,
			@Valid @RequestBody UpdateProductRequest request) {

		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwned(shop, productId);
		Product updated = products.update(shop, product, request.name(), request.shortDescription(),
				request.description(), request.compareAt(), request.costPrice(), request.weightGrams(),
				request.trackInventory(), request.allowBackorder(), request.featured(),
				request.seoTitle(), request.seoDescription());

		if (request.clearCompareAt()) {
			products.clearCompareAt(shop, updated);
		}
		return ProductResponse.from(products.getOwned(shop, productId), products.variantsOf(updated));
	}

	@PatchMapping("/{productId}/category")
	@Operation(summary = "Change a product's category",
			description = "Re-validated against the shop type, so it cannot be moved out of range.")
	public ProductResponse recategorise(@PathVariable UUID productId,
			@Valid @RequestBody CategoryRequest request) {

		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwned(shop, productId);
		return ProductResponse.from(products.recategorise(shop, product, request.category()),
				products.variantsOf(product));
	}

	/**
	 * Publishes to the storefront.
	 *
	 * <p>409 if there is no priced variant, and 409 if the shop is not yet verified. Both are
	 * conflicts with current state rather than malformed requests, which is what the owner
	 * needs to see in order to know what to do next.
	 */
	@PostMapping("/{productId}/publish")
	@Operation(summary = "Publish to the storefront",
			description = "Requires at least one priced variant and a verified shop.")
	public ProductResponse publish(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwned(shop, productId);
		return ProductResponse.from(products.publish(shop, product), products.variantsOf(product));
	}

	@PostMapping("/{productId}/unpublish")
	@Operation(summary = "Return to draft without deleting")
	public ProductResponse unpublish(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwned(shop, productId);
		return ProductResponse.from(products.unpublish(shop, product), products.variantsOf(product));
	}

	@PostMapping("/{productId}/archive")
	@Operation(summary = "Archive: hidden from the storefront, kept for order history")
	public ProductResponse archive(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwned(shop, productId);
		return ProductResponse.from(products.archive(shop, product), products.variantsOf(product));
	}

	@DeleteMapping("/{productId}")
	@Operation(summary = "Delete a product",
			description = "Refused once the product has sales; archive it instead.")
	public ProductResponse delete(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwnedIncludingDeleted(shop, productId);
		return ProductResponse.from(products.delete(shop, product), List.of());
	}

	@PostMapping("/{productId}/restore")
	@Operation(summary = "Restore a deleted product, as a draft")
	public ProductResponse restore(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwnedIncludingDeleted(shop, productId);
		return ProductResponse.from(products.restore(shop, product), products.variantsOf(product));
	}

	/** The recycle bin. */
	@GetMapping("/deleted")
	@Operation(summary = "Soft-deleted products, for the recycle bin")
	public PageResponse<ProductSummary> deleted(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) int size) {

		Shop shop = tenantGuard.requireOwnedShop();
		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
				Sort.by(Sort.Direction.DESC, "deletedAt"));
		return PageResponse.from(products.deletedForShop(shop, pageable), ProductSummary::from);
	}

	/**
	 * The categories this shop may use.
	 *
	 * <p>Served from the same validator that enforces the rule on write, so the dropdown in the
	 * console can never offer a category the API would then reject.
	 */
	@GetMapping("/categories")
	@Operation(summary = "Categories valid for this shop's type",
			description = "The exact set the write endpoints will accept.")
	public CategoryOptions categories() {
		Shop shop = tenantGuard.requireOwnedShop();
		return new CategoryOptions(
				shop.getType(),
				shop.getType().categories().stream().map(c -> new CategoryOption(c.name(), c.label()))
						.toList(),
				products.categoriesInUse(shop).stream().map(Enum::name).sorted().toList());
	}

	// ------------------------------------------------------------------ variants

	@PostMapping("/{productId}/variants")
	@Operation(summary = "Add a variant",
			description = "A variant carries the price and the SKU; a product with no variant cannot be published.")
	public ProductVariant.VariantView addVariant(@PathVariable UUID productId,
			@Valid @RequestBody CreateVariantRequest request) {

		Shop shop = tenantGuard.requireOwnedShop();
		Product product = products.getOwned(shop, productId);

		ProductVariant variant = products.addVariant(shop, product, request.name(), request.price(),
				request.sku(), ProductService.VariantOptions.of(request.options()),
				request.barcode(), request.compareAt());

		return ProductVariant.VariantView.from(variant);
	}

	@PatchMapping("/variants/{variantId}")
	@Operation(summary = "Update a variant",
			description = "A price change refreshes the product's displayed 'from' price.")
	public ProductVariant.VariantView updateVariant(@PathVariable UUID variantId,
			@Valid @RequestBody UpdateVariantRequest request) {

		Shop shop = tenantGuard.requireOwnedShop();
		ProductVariant variant = products.getOwnedVariant(shop, variantId);

		ProductVariant updated = products.updateVariant(shop, variant, request.name(), request.price(),
				request.sku(), request.options() == null ? null
						: ProductService.VariantOptions.of(request.options()),
				request.barcode(), request.compareAt(), request.sortOrder(), request.active());

		if (request.imageUrl() != null) {
			updated = products.setVariantImage(shop, updated, request.imageUrl());
		}
		return ProductVariant.VariantView.from(updated);
	}

	/**
	 * Deactivates a variant.
	 *
	 * <p>A POST that removes something is deliberate: the row survives, so order history and
	 * inventory history still resolve. A DELETE that soft-deleted would suggest removal that
	 * does not happen, and the client would have to know the difference.
	 */
	@PostMapping("/variants/{variantId}/deactivate")
	@Operation(summary = "Hide a variant from the storefront, keeping its history")
	public ProductVariant.VariantView deactivateVariant(@PathVariable UUID variantId) {
		Shop shop = tenantGuard.requireOwnedShop();
		return ProductVariant.VariantView.from(
				products.deactivateVariant(shop, products.getOwnedVariant(shop, variantId)));
	}

	@PostMapping("/variants/{variantId}/activate")
	@Operation(summary = "Bring a variant back into the storefront")
	public ProductVariant.VariantView activateVariant(@PathVariable UUID variantId) {
		Shop shop = tenantGuard.requireOwnedShop();
		return ProductVariant.VariantView.from(
				products.activateVariant(shop, products.getOwnedVariant(shop, variantId)));
	}

	// ------------------------------------------------------------------ requests

	public record CreateProductRequest(
			@NotBlank @Size(max = 200) String name,
			@NotNull ProductCategory category,
			@Size(max = 80) String slug,
			@Size(max = 300) String shortDescription,
			String description) {
	}

	public record UpdateProductRequest(
			@Size(max = 200) String name,
			@Size(max = 300) String shortDescription,
			String description,
			/** Strike-through price in paisa. Must exceed the current price. */
			@Min(1) Long compareAt,
			/**
			 * Explicit clear, because a PATCH cannot express "set this field to null" any other
			 * way. Without it a compare-at price could be set once and never removed.
			 */
			boolean clearCompareAt,
			/** Shop-private. Never returned by any endpoint. */
			@Min(0) Long costPrice,
			@Min(0) Integer weightGrams,
			Boolean trackInventory,
			Boolean allowBackorder,
			Boolean featured,
			@Size(max = 200) String seoTitle,
			@Size(max = 500) String seoDescription) {
	}

	public record CategoryRequest(@NotNull ProductCategory category) {
	}

	public record CreateVariantRequest(
			@NotBlank @Size(max = 120) String name,
			@NotNull @Min(0) Long price,
			@Size(max = 64) String sku,
			/** e.g. {@code {"size": "L", "colour": "red"}} */
			java.util.Map<String, String> options,
			@Size(max = 64) String barcode,
			@Min(1) Long compareAt) {
	}

	public record UpdateVariantRequest(
			@Size(max = 120) String name,
			@Min(0) Long price,
			@Size(max = 64) String sku,
			java.util.Map<String, String> options,
			@Size(max = 64) String barcode,
			@Min(1) Long compareAt,
			Integer sortOrder,
			Boolean active,
			/** A delivery URL from the media library, or "" to fall back to the product image. */
			@Size(max = 500) String imageUrl) {
	}

	// ------------------------------------------------------------------ responses

	/**
	 * A product for the owner's own console.
	 *
	 * <p>{@code costPrice} is absent here as well as from the public DTO. There is no endpoint
	 * that returns a shop's cost price to anyone but that shop's owner, and the console needs
	 * it to show margins — so the field is present for the owner and nowhere else, rather than
	 * present everywhere and filtered on the way out, which is one mistake away from a leak.
	 */
	public record ProductResponse(
			UUID id,
			String name,
			String slug,
			ProductCategory category,
			String categoryLabel,
			Product.Status status,
			long price,
			Long compareAt,
			Long costPrice,
			Integer weightGrams,
			boolean trackInventory,
			boolean allowBackorder,
			boolean featured,
			int soldCount,
			int viewCount,
			String shortDescription,
			String description,
			String seoTitle,
			String seoDescription,
			List<ProductVariant.VariantView> variants) {

		static ProductResponse from(Product product, List<ProductVariant> variants) {
			return new ProductResponse(
					product.getId(),
					product.getName(),
					product.getSlug(),
					product.getProductCategory(),
					product.getProductCategory().label(),
					product.getStatus(),
					product.getBasePriceMinor(),
					product.getCompareAtMinor(),
					product.getCostPriceMinor(),
					product.getWeightGrams(),
					product.isTrackInventory(),
					product.isAllowBackorder(),
					product.isFeatured(),
					product.getSoldCount(),
					product.getViewCount(),
					product.getShortDescription(),
					product.getDescription(),
					product.getSeoTitle(),
					product.getSeoDescription(),
					variants.stream().map(ProductVariant.VariantView::from).toList());
		}
	}

	/** A row in the console's product table: enough to render, not enough to open an edit form. */
	public record ProductSummary(
			UUID id,
			String name,
			String slug,
			ProductCategory category,
			Product.Status status,
			long price,
			boolean trackInventory,
			int soldCount,
			int variantCount) {

		static ProductSummary from(Product product) {
			return new ProductSummary(
					product.getId(),
					product.getName(),
					product.getSlug(),
					product.getProductCategory(),
					product.getStatus(),
					product.getBasePriceMinor(),
					product.isTrackInventory(),
					product.getSoldCount(),
					0);
		}
	}

	public record CategoryOption(String code, String label) {
	}

	/**
	 * The category dropdown's data.
	 *
	 * <p>{@code inUse} is included so the console can mark which ones a shop has already used,
	 * which is what tells an owner why a shop-type change is blocked.
	 */
	public record CategoryOptions(ShopType shopType, List<CategoryOption> allowed,
			List<String> inUse) {
	}

	/**
	 * A page of results.
	 *
	 * <p>A record rather than Spring's {@code Page}, because Spring's serialises an unpaged and
	 * internal {@code Pageable} into the response, and that leaks a fragment of the repository's
	 * implementation into the public API contract.
	 */
	public record PageResponse<T>(List<T> content, int page, int size, long totalElements,
			int totalPages, boolean last) {

		static <E, T> PageResponse<T> from(Page<E> page, java.util.function.Function<E, T> mapper) {
			return new PageResponse<>(
					page.getContent().stream().map(mapper).toList(),
					page.getNumber(),
					page.getSize(),
					page.getTotalElements(),
					page.getTotalPages(),
					page.isLast());
		}
	}
}
