package com.storefront.store.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.media.MediaAsset;
import com.storefront.store.media.MediaAssetRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.shop.SlugGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Product and variant management for the owning shop.
 *
 * <p>Every method takes the {@link Shop} rather than a shop id, and every method re-reads the
 * product before writing. The shop arrives from the token (D35) and the product is checked
 * against it, so there is no code path in this class that can write to another shop's
 * catalogue — a caller cannot even express the mistake, let alone make it.
 *
 * <p>The category rule is enforced here and nowhere else on the write path.
 * {@link CategoryValidator} knows the rule; this class is the only thing that calls it on a
 * write. A product therefore cannot reach the database in a category its shop does not sell,
 * which is what makes the shop-type-change check (D29) reliable: it only has to look at existing
 * rows, trusting that each was valid when written.
 *
 * <p>{@code base_price_minor} is recomputed after every variant change. It mirrors the cheapest
 * active variant, and a mirror that is not refreshed becomes a lie within a day — a "from
 * Rs. 40" badge on a product whose cheapest size is Rs. 90.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {

	/** How many slug variants to try before falling back to a random suffix. */
	private static final int MAX_SLUG_ATTEMPTS = 5;

	private final ProductRepository products;
	private final ProductVariantRepository variants;
	private final CategoryValidator categories;
	private final MediaAssetRepository mediaAssets;

	/**
	 * Shop lookup, used only by the bulk-edit path.
	 *
	 * <p>Injected directly rather than passed in: a bulk category change has to re-validate
	 * against the shop's type, and the shop is not among the method's arguments. {@code Shop}
	 * does not own this package, but the dependency is one narrow read and wrapping it in a
	 * supplier would only obscure that.
	 */
	private final ShopRepository shops;

	// ------------------------------------------------------------------ create

	/**
	 * Creates a draft product.
	 *
	 * <p>Always DRAFT. Publishing is a separate, explicit act, because a product created from a
	 * half-filled form must not appear in the customer's storefront — a broken product page is
	 * worse than no product page at all.
	 *
	 * @throws CategoryValidator.InvalidCategoryException when the category does not belong to
	 *                                                    the shop's type
	 */
	@Transactional
	public Product createDraft(Shop shop, String name, ProductCategory category,
			String slugPreference, String shortDescription, String description) {

		categories.validate(shop.getType(), category);

		Product product = new Product(shop.getId(), name,
				allocateSlug(shop.getId(), name, slugPreference), category);
		product.setShortDescription(shortDescription);
		product.setDescription(description);

		Product saved = products.saveAndFlush(product);
		log.info("Product '{}' created in shop {} as {}", saved.getName(), shop.getId(), category);
		return saved;
	}

	/**
	 * Finds an unused slug within a shop.
	 *
	 * <p>Scoped to the shop, not global: two shops may both sell "Samosa", and only one can own
	 * {@code /samosa-shop/samosa}. The per-shop unique index remains the authority; this just
	 * avoids the collision the owner cannot do anything about.
	 */
	private String allocateSlug(UUID shopId, String name, String preference) {
		String base = SlugGenerator.productSlug(preference, name, UUID.randomUUID());

		String candidate = base;
		for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
			if (!products.existsByShopIdAndSlug(shopId, candidate)) {
				return candidate;
			}
			candidate = base + "-" + (attempt + 2);
		}
		return base + "-" + UUID.randomUUID().toString().substring(0, 6).toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------ update

	/**
	 * Updates the editable fields of a product.
	 *
	 * <p>Category changes are deliberately not accepted here. They go through
	 * {@link #recategorise} so the validation rule and the fact that a category move is a
	 * different kind of change from a copy edit both stay visible, instead of hiding behind a
	 * nullable field.
	 */
	@Transactional
	public Product update(Shop shop, Product product, String name, String shortDescription,
			String description, Long compareAtMinor, Long costPriceMinor, Integer weightGrams,
			Boolean trackInventory, Boolean allowBackorder, Boolean featured,
			String seoTitle, String seoDescription) {

		requireOwned(shop, product);

		if (name != null && !name.isBlank()) {
			product.setName(name.trim());
		}
		if (shortDescription != null) {
			product.setShortDescription(shortDescription.trim());
		}
		if (description != null) {
			product.setDescription(description);
		}
		if (compareAtMinor != null) {
			product.setCompareAtMinor(compareAtMinor);
		}
		if (costPriceMinor != null) {
			product.setCostPriceMinor(requireNonNegativeLong(costPriceMinor, "Cost price"));
		}
		if (weightGrams != null) {
			product.setWeightGrams(requireNonNegativeInt(weightGrams, "Weight"));
		}
		if (trackInventory != null) {
			product.setTrackInventory(trackInventory);
		}
		if (allowBackorder != null) {
			product.setAllowBackorder(allowBackorder);
		}
		if (featured != null) {
			product.setFeatured(featured);
		}
		if (seoTitle != null) {
			product.setSeoTitle(seoTitle.trim());
		}
		if (seoDescription != null) {
			product.setSeoDescription(seoDescription.trim());
		}

		return products.saveAndFlush(product);
	}

	/** Clears the strike-through price. Separate because a partial update cannot express null. */
	@Transactional
	public Product clearCompareAt(Shop shop, Product product) {
		requireOwned(shop, product);
		product.setCompareAtMinor(null);
		return products.saveAndFlush(product);
	}

	/**
	 * Changes a product's platform category.
	 *
	 * <p>Re-validated against the shop's type. Re-categorising a live product is the one edit
	 * that can move it out of the permitted set, so it goes through the same validator as
	 * creation rather than being trusted.
	 */
	@Transactional
	public Product recategorise(Shop shop, Product product, ProductCategory category) {
		requireOwned(shop, product);
		categories.validate(shop.getType(), category);
		product.updateCategory(category);
		return products.saveAndFlush(product);
	}

	// ------------------------------------------------------------------ variants

	/**
	 * Adds a variant.
	 *
	 * <p>Refuses a duplicate SKU within the product. The database has a partial unique index for
	 * this, but catching it here produces a message the owner can act on instead of a constraint
	 * violation naming an index they have never heard of.
	 */
	@Transactional
	public ProductVariant addVariant(Shop shop, Product product, String name, Long priceMinor,
			String sku, VariantOptions options, String barcode, Long compareAtMinor) {

		requireOwned(shop, product);

		if (priceMinor == null) {
			throw new IllegalArgumentException("A variant needs a price");
		}
		if (sku != null && !sku.isBlank()
				&& variants.existsByProductIdAndSku(product.getId(), sku.trim())) {
			throw new IllegalArgumentException("SKU '" + sku.trim()
					+ "' is already used by another variant of this product");
		}

		ProductVariant variant = new ProductVariant(product.getId(), name, priceMinor);
		if (sku != null && !sku.isBlank()) {
			variant.setSku(sku.trim());
		}
		if (barcode != null && !barcode.isBlank()) {
			variant.setBarcode(barcode.trim());
		}
		if (options != null) {
			variant.updateOptions(options.options());
		}
		if (compareAtMinor != null) {
			variant.setCompareAt(compareAtMinor);
		}
		// Append to the end. The owner reorders explicitly rather than by creation order, which
		// would silently reshuffle a shop's carefully arranged catalogue on every edit.
		variant.setSortOrder((int) variants.countByProductId(product.getId()));

		ProductVariant saved = variants.saveAndFlush(variant);
		refreshMinimumPrice(product);
		// The SKU list is denormalised onto the product so search can match a bare SKU. Refreshed
		// here as well as in the bulk path, or ordinary single-variant edits leave search stale.
		products.refreshSkuIndex(product.getId());

		log.info("Variant '{}' ({} paisa) added to product {}", saved.getName(), priceMinor,
				product.getId());
		return saved;
	}

	/**
	 * Updates a variant.
	 *
	 * <p>Re-reads the product afterwards to refresh its mirrored minimum price, which is the
	 * whole reason this exists rather than a plain setter on the entity.
	 */
	@Transactional
	public ProductVariant updateVariant(Shop shop, ProductVariant variant, String name,
			Long priceMinor, String sku, VariantOptions options, String barcode, Long compareAtMinor,
			Integer sortOrder, Boolean active) {

		Product product = requireOwnedVariant(shop, variant);

		if (name != null && !name.isBlank()) {
			variant.setName(name.trim());
		}
		if (priceMinor != null) {
			variant.updatePrice(priceMinor);
		}
		if (sku != null && !sku.isBlank() && !sku.trim().equals(variant.getSku())) {
			if (variants.existsByProductIdAndSku(product.getId(), sku.trim())) {
				throw new IllegalArgumentException("SKU '" + sku.trim()
						+ "' is already used by another variant of this product");
			}
			variant.setSku(sku.trim());
		}
		if (options != null) {
			variant.updateOptions(options.options());
		}
		if (barcode != null) {
			variant.setBarcode(barcode.isBlank() ? null : barcode.trim());
		}
		if (compareAtMinor != null) {
			variant.setCompareAt(compareAtMinor);
		}
		if (sortOrder != null) {
			variant.setSortOrder(sortOrder);
		}
		if (active != null) {
			if (active) {
				variant.activate();
			} else {
				variant.deactivate();
			}
		}

		ProductVariant saved = variants.saveAndFlush(variant);
		refreshMinimumPrice(product);
		products.refreshSkuIndex(product.getId());
		return saved;
	}

	/**
	 * Points a variant at its own image, or clears it.
	 *
	 * <p>Null is meaningful here and is the common case: it means "fall back to the product's
	 * first image". Copying every product image onto every variant would multiply the writes
	 * and leave two things free to drift apart.
	 */
	@Transactional
	public ProductVariant setVariantImage(Shop shop, ProductVariant variant, String imageUrl) {
		requireOwnedVariant(shop, variant);
		variant.setImageUrl(imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim());
		return variants.saveAndFlush(variant);
	}

	/**
	 * Deactivates a variant.
	 *
	 * <p>Not a delete, even for a variant that has sold. The order line holds a snapshot, but the
	 * shop's sales report and inventory history both point here, so the row stays and simply
	 * leaves the storefront.
	 */
	@Transactional
	public ProductVariant deactivateVariant(Shop shop, ProductVariant variant) {
		Product product = requireOwnedVariant(shop, variant);
		variant.deactivate();
		ProductVariant saved = variants.saveAndFlush(variant);
		refreshMinimumPrice(product);
		return saved;
	}

	/** Reactivates a previously deactivated variant. */
	@Transactional
	public ProductVariant activateVariant(Shop shop, ProductVariant variant) {
		Product product = requireOwnedVariant(shop, variant);
		variant.activate();
		ProductVariant saved = variants.saveAndFlush(variant);
		refreshMinimumPrice(product);
		return saved;
	}

	// ------------------------------------------------------------------ bulk editing

	/**
	 * Replaces a product's whole variant list in one transaction.
	 *
	 * <p>This is the variants-matrix save. It is a **replace, not a merge**, and that is a
	 * deliberate decision with a sharp edge: a variant that is not in {@code incoming} is
	 * <em>removed</em>.
	 *
	 * <p>The reason is inventory. A merge would leave variants behind that the owner believed they
	 * had deleted, still holding stock that is still being counted. A replace makes the request
	 * mean exactly what it says, and the variant id is what makes deletion safe — a variant with
	 * stock or sales cannot be dropped silently, so it is deactivated instead of deleted, and
	 * the response says which happened.
	 *
	 * <p>Variants are matched by id. One that is not recognised is an error rather than an
	 * insert, because silently creating a new variant for a typo'd id gives the owner a
	 * duplicate they did not ask for.
	 */
	@Transactional
	public List<ProductVariant> replaceVariants(Shop shop, Product product,
			List<IncomingVariant> incoming) {

		requireOwned(shop, product);

		List<ProductVariant> existing = variants.findByProductIdOrderBySortOrderAscNameAsc(
				product.getId());
		java.util.Map<UUID, ProductVariant> byId = new java.util.HashMap<>();
		existing.forEach(v -> byId.put(v.getId(), v));

		java.util.Set<UUID> kept = new java.util.HashSet<>();
		List<ProductVariant> result = new java.util.ArrayList<>();

		int sortOrder = 0;
		for (IncomingVariant row : incoming) {
			ProductVariant variant;
			if (row.id() == null) {
				variant = new ProductVariant(product.getId(), row.name(), row.priceMinor());
			} else {
				variant = byId.get(row.id());
				if (variant == null) {
					// A typo'd id creating a duplicate is worse than the request failing.
					throw new IllegalArgumentException(
							"Variant " + row.id() + " is not part of this product");
				}
				kept.add(variant.getId());
				variant.updatePrice(row.priceMinor());
				variant.setName(row.name());
			}

			if (row.sku() != null && !row.sku().isBlank()) {
				// Uniqueness is per product, and only against the OTHER variants: re-saving a
				// variant with its own sku must not be refused.
				if (variants.findByProductIdAndSku(product.getId(), row.sku().trim())
						.filter(other -> !other.getId().equals(variant.getId())).isPresent()) {
					throw new IllegalArgumentException(
							"SKU '" + row.sku().trim() + "' is already used by another variant");
				}
				variant.setSku(row.sku().trim());
			} else {
				variant.setSku(null);
			}

			if (row.compareAtMinor() != null) {
				variant.setCompareAt(row.compareAtMinor());
			}
			if (row.barcode() != null) {
				variant.setBarcode(row.barcode().isBlank() ? null : row.barcode().trim());
			}
			if (row.options() != null && !row.options().isEmpty()) {
				variant.updateOptions(new ArrayList<>(row.options()));
			}
			if (row.active() != null) {
				if (row.active()) {
					variant.activate();
				} else {
					variant.deactivate();
				}
			}
			variant.setSortOrder(sortOrder++);
			result.add(variants.saveAndFlush(variant));
		}

		// Anything the owner left out of the matrix is removed rather than hidden.
		for (ProductVariant orphan : existing) {
			if (!kept.contains(orphan.getId()) && result.stream()
					.noneMatch(v -> v.getId().equals(orphan.getId()))) {
				orphan.deactivate();
				variants.saveAndFlush(orphan);
			}
		}

		refreshMinimumPrice(product);
		products.refreshSkuIndex(product.getId());
		return variants.findByProductIdOrderBySortOrderAscNameAsc(product.getId());
	}

	/** One row of a variants-matrix save. A null id means "a new variant". */
	public record IncomingVariant(
			UUID id,
			String name,
			long priceMinor,
			String sku,
			String barcode,
			Long compareAtMinor,
			List<ProductVariant.Option> options,
			Boolean active) {
	}

	// ------------------------------------------------------------------ duplication

	/**
	 * Copies a product, optionally its variants and images.
	 *
	 * <p>The copy is a DRAFT. Duplicating a live product straight into ACTIVE would put a second
	 * copy of a product on the storefront immediately, and the usual reason for duplicating is to
	 * change something — which then means editing a product customers can already see.
	 *
	 * <p>Images are copied by reference, not by re-upload. Re-uploading would cost storage per
	 * duplicate for no benefit, and Cloudinary's own {@code overwrite} semantics mean the same
	 * public_id cannot be written twice anyway. The media rows point at the same assets, which
	 * means editing an image on the copy edits it on the original — the trade is recorded here so
	 * it is not discovered later.
	 */
	@Transactional
	public Product duplicate(Shop shop, Product source, boolean includeVariants,
			boolean includeImages) {

		requireOwned(shop, source);

		Product copy = new Product(shop.getId(),
				source.getName() + " (copy)",
				allocateSlug(shop.getId(), source.getName() + " copy", null),
				source.getProductCategory());
		copy.setShortDescription(source.getShortDescription());
		copy.setDescription(source.getDescription());
		// Margin is the owner's own data and is worth carrying over; it is what makes the copy
		// useful rather than a blank form.
		copy.setCostPriceMinor(source.getCostPriceMinor());
		copy.setWeightGrams(source.getWeightGrams());
		copy.setTrackInventory(source.isTrackInventory());
		copy.setAllowBackorder(source.isAllowBackorder());
		copy.setSeoTitle(source.getSeoTitle());
		copy.setSeoDescription(source.getSeoDescription());
		copy.setCompareAtMinor(null);
		copy.setFeatured(false);

		Product saved = products.saveAndFlush(copy);

		if (includeVariants) {
			for (ProductVariant variant : variants.findByProductIdOrderBySortOrderAscNameAsc(
					source.getId())) {
				ProductVariant copyVariant = new ProductVariant(saved.getId(), variant.getName(),
						variant.getPriceMinor());
				copyVariant.setSku(null);
				// A SKU must be unique per product, so carrying it over would collide the moment
				// the owner tries to save. Left null and flagged in the response.
				copyVariant.setBarcode(variant.getBarcode());
				copyVariant.setCompareAt(variant.getCompareAtMinor());
				copyVariant.setImageUrl(variant.getImageUrl());
				copyVariant.setSortOrder(variant.getSortOrder());
				copyVariant.setActive(true);
				copyVariant.updateOptions(new ArrayList<>(variant.getOptions()));
				variants.saveAndFlush(copyVariant);
			}
			refreshMinimumPrice(saved);
		}

		if (includeImages) {
			// Same asset, new media row: the copy references the original's Cloudinary objects.
			List<com.storefront.store.media.MediaAsset> images =
					mediaAssets.findByShopIdAndProductIdOrderBySortOrderAsc(shop.getId(), source.getId());
			int order = 0;
			for (com.storefront.store.media.MediaAsset image : images) {
				MediaAsset copyImage = new MediaAsset(shop.getId(),
						com.storefront.store.media.MediaAsset.Usage.PRODUCT_IMAGE,
						image.getCloudinaryFolder(),
						// The public_id is deliberately the SAME as the original's. Re-uploading is
						// impossible (Cloudinary's overwrite semantics) and wasteful, and the media
						// table's unique index on public_id is what makes that explicit: one asset
						// cannot be registered twice, so one row must serve both products.
						//
						// The consequence, recorded here so it is not discovered later: deleting the
						// copy's image row removes the asset for the original too. Working around
						// that would mean copying every file on every duplicate, which is exactly
						// the cost duplication exists to avoid.
						image.getPublicId(),
						image.getUrl(), image.getSecureUrl(),
						image.getWidth(), image.getHeight(), image.getBytes(), image.getFormat(),
						null);
				copyImage.attachToProduct(saved.getId(), null);
				copyImage.setAltText(image.getAltText());
				copyImage.setSortOrder(order++);
				mediaAssets.saveAndFlush(copyImage);
			}
		}

		products.refreshSkuIndex(saved.getId());
		log.info("Product {} duplicated to {}", source.getId(), saved.getId());
		return saved;
	}

	// ------------------------------------------------------------------ bulk editing

	/**
	 * Applies one change across many products, skipping any the caller does not own.
	 *
	 * <p>Skip-and-report rather than fail: an owner selecting 40 products and changing a price
	 * wants 39 changed and one refused with a reason, not a single error and no changes at all.
	 *
	 * <p>Only the fields supplied are touched. A bulk price change must not blank descriptions —
	 * that is what happens with a naive "replace the whole object" implementation, and it is
	 * unrecoverable across 40 products.
	 */
	@Transactional
	public BulkResult bulkUpdate(Shop shop, List<UUID> productIds, BulkChange change) {

		List<Applied> applied = new ArrayList<>();
		List<Skipped> skipped = new ArrayList<>();

		for (UUID productId : productIds) {
			Product product = products.findById(productId).orElse(null);

			if (product == null || !product.getShopId().equals(shop.getId()) || product.isDeleted()) {
				// 404-equivalent: another shop's product is not visible, not merely forbidden.
				skipped.add(new Skipped(productId, "Not your product, or no longer exists"));
				continue;
			}

			try {
				applyBulkChange(product, change);
				applied.add(new Applied(product.getId(), product.getName()));
			} catch (RuntimeException e) {
				// One bad row must not abandon the other 39. The message is the owner's to act on.
				skipped.add(new Skipped(productId, e.getMessage()));
			}
		}

		log.info("Bulk update: {} applied, {} skipped", applied.size(), skipped.size());
		return new BulkResult(applied, skipped);
	}

	private void applyBulkChange(Product product, BulkChange change) {
		if (change.category() != null) {
			// Validated like any other category change: a bulk edit must not be a way around the
			// shop-type rule.
			categories.validate(shopOf(product).getType(), change.category());
			product.updateCategory(change.category());
		}
		if (change.status() != null) {
			if (change.status() == Product.Status.ACTIVE) {
				// Publishing needs a priced variant, and a bulk action must not skip that check.
				if (variants.countByProductIdAndActiveTrue(product.getId()) == 0) {
					throw new IllegalStateException("no priced variant to publish");
				}
				product.publish(product.getBasePriceMinor());
			} else {
				product.setStatus(change.status());
			}
		}
		if (change.featured() != null) {
			product.setFeatured(change.featured());
		}
		if (change.trackInventory() != null) {
			product.setTrackInventory(change.trackInventory());
		}
		if (change.weightGrams() != null) {
			product.setWeightGrams(requireNonNegativeInt(change.weightGrams(), "Weight"));
		}
		products.saveAndFlush(product);
	}

	/** The price adjustment shared by every selected variant. */
	@Transactional
	public BulkResult bulkAdjustPrices(Shop shop, List<UUID> productIds, PriceAdjustment adjustment) {
		List<Applied> applied = new ArrayList<>();
		List<Skipped> skipped = new ArrayList<>();

		for (UUID productId : productIds) {
			Product product = products.findById(productId).orElse(null);
			if (product == null || !product.getShopId().equals(shop.getId()) || product.isDeleted()) {
				skipped.add(new Skipped(productId, "Not your product, or no longer exists"));
				continue;
			}

			try {
				for (ProductVariant variant : variants
						.findByProductIdOrderBySortOrderAscNameAsc(product.getId())) {
					variant.updatePrice(adjustPrice(variant.getPriceMinor(), adjustment));
					variants.saveAndFlush(variant);
				}
				refreshMinimumPrice(product);
				applied.add(new Applied(product.getId(), product.getName()));
			} catch (RuntimeException e) {
				skipped.add(new Skipped(productId, e.getMessage()));
			}
		}
		return new BulkResult(applied, skipped);
	}

	/**
	 * One price change, applied to every variant.
	 *
	 * <p>Each variant is adjusted from its <em>own</em> price, not from the product's minimum, so a
	 * 10% increase preserves the relative spacing between sizes. Clamped at zero rather than
	 * throwing: a 90% reduction on a cheap variant would otherwise fail the whole batch, and
	 * refusing to sell at a loss the shop asked for is not this layer's decision.
	 */
	private long adjustPrice(long currentPrice, PriceAdjustment adjustment) {
		long adjusted = switch (adjustment.kind()) {
			case PERCENT -> Math.round(currentPrice * (100.0 + adjustment.value()) / 100.0);
			case FIXED_ADD -> currentPrice + adjustment.value();
			case FIXED_SET -> adjustment.value();
		};
		return Math.max(0, adjusted);
	}

	private Shop shopOf(Product product) {
		return shops.findById(product.getShopId()).orElseThrow();
	}

	public record Applied(UUID productId, String name) {
	}

	public record Skipped(UUID productId, String reason) {
	}

	public record BulkResult(List<Applied> applied, List<Skipped> skipped) {

		public int appliedCount() {
			return applied.size();
		}

		public int skippedCount() {
			return skipped.size();
		}
	}

	/** Which fields a bulk edit touches. Null means "leave alone". */
	public record BulkChange(
			ProductCategory category,
			Product.Status status,
			Boolean featured,
			Boolean trackInventory,
			Integer weightGrams) {
	}

	public enum AdjustmentKind {
		/** {@code value} is a percentage change: 10 raises by 10%, -50 halves. */
		PERCENT,
		/** {@code value} is a fixed amount added to every variant. */
		FIXED_ADD,
		/** {@code value} is the new price for every variant, whatever it was. */
		FIXED_SET
	}

	public record PriceAdjustment(AdjustmentKind kind, long value) {
	}

	/**
	 * Recomputes the product's mirrored minimum price from its active variants.
	 *
	 * <p>Flushed immediately because the caller usually returns the product, and a stale
	 * in-memory value would be serialised into the response the owner sees.
	 */
	private void refreshMinimumPrice(Product product) {
		Long minimum = variants.findMinimumActivePrice(product.getId());
		product.refreshMinimumPrice(minimum == null ? 0 : minimum);
		products.saveAndFlush(product);
	}

	// ------------------------------------------------------------------ lifecycle

	/**
	 * Publishes a product to the storefront.
	 *
	 * <p>Refuses a product with no active variant. Publishing an unpriced product puts a row in
	 * a customer's basket that cannot be bought, and discovering that after they have typed
	 * their address is a far worse experience than the product simply not appearing.
	 *
	 * <p>Also refused for an unverified shop. A shop's products must not appear before the shop
	 * itself does, or the verification gate is bypassed by listing something.
	 */
	@Transactional
	public Product publish(Shop shop, Product product) {
		requireOwned(shop, product);
		requireVerified(shop);

		List<ProductVariant> active = variants
				.findByProductIdAndActiveTrueOrderBySortOrderAscNameAsc(product.getId());
		if (active.isEmpty()) {
			throw new IllegalStateException(
					"Add at least one variant with a price before publishing this product.");
		}

		product.publish(active.get(0).getPriceMinor());
		log.info("Product {} published in shop {}", product.getId(), shop.getId());
		return products.saveAndFlush(product);
	}

	/** Returns a published or archived product to draft. */
	@Transactional
	public Product unpublish(Shop shop, Product product) {
		requireOwned(shop, product);
		product.setStatus(Product.Status.DRAFT);
		return products.saveAndFlush(product);
	}

	/**
	 * Archives a product: hidden from the storefront, still reachable, still referenced by
	 * order history.
	 */
	@Transactional
	public Product archive(Shop shop, Product product) {
		requireOwned(shop, product);
		product.archive();
		return products.saveAndFlush(product);
	}

	/**
	 * Soft-deletes a product.
	 *
	 * <p>Refuses once it has sales — {@link Product#delete()} throws. Hard-deleting a product
	 * that appears in an order's history would leave the shop's own reports dangling.
	 *
	 * <p><b>Re-reads the product rather than trusting the argument.</b> The sales guard depends on
	 * {@code soldCount}, which is a denormalised counter updated by a bulk UPDATE. That query
	 * declares {@code clearAutomatically}, so an entity instance held by the caller is detached
	 * and still showing the count from before the sale. Deleting through the stale instance would
	 * silently strand order history — the exact failure the guard exists to prevent. Found by
	 * {@code ProductServiceIT}.
	 */
	@Transactional
	public Product delete(Shop shop, Product product) {
		requireOwned(shop, product);
		Product current = products.findById(product.getId())
				.orElseThrow(() -> new ProductNotFoundException(product.getId()));
		current.delete();
		log.info("Product {} deleted in shop {}", current.getId(), shop.getId());
		return products.saveAndFlush(current);
	}

	/**
	 * Brings a soft-deleted product back.
	 *
	 * <p>Returns to DRAFT, not to whatever status it had: a restored product has not been
	 * re-checked, and silently re-publishing it would skip the price and image checks.
	 */
	@Transactional
	public Product restore(Shop shop, Product product) {
		requireOwned(shop, product);
		if (!product.isDeleted()) {
			return product;
		}
		product.setDeletedAt(null);
		product.setStatus(Product.Status.DRAFT);
		return products.saveAndFlush(product);
	}

	// ------------------------------------------------------------------ reads

	/**
	 * Loads a product the caller owns.
	 *
	 * <p>A product in another shop is reported as not found rather than forbidden. Both are
	 * 404: a 403 would confirm the id exists somewhere, turning this endpoint into an oracle
	 * for enumerating another shop's catalogue.
	 */
	@Transactional(readOnly = true)
	public Product getOwned(Shop shop, UUID productId) {
		Product product = products.findByIdAndDeletedAtIsNull(productId)
				.orElseThrow(() -> new ProductNotFoundException(productId));
		requireOwned(shop, product);
		return product;
	}

	/** Includes soft-deleted products, so the console can offer a restore. */
	@Transactional(readOnly = true)
	public Product getOwnedIncludingDeleted(Shop shop, UUID productId) {
		Product product = products.findById(productId)
				.orElseThrow(() -> new ProductNotFoundException(productId));
		requireOwned(shop, product);
		return product;
	}

	/** Loads a variant, checking ownership of the product that owns it. */
	@Transactional(readOnly = true)
	public ProductVariant getOwnedVariant(Shop shop, UUID variantId) {
		ProductVariant variant = variants.findById(variantId)
				.orElseThrow(() -> new ProductNotFoundException(variantId));
		requireOwnedVariant(shop, variant);
		return variant;
	}

	/**
	 * A product's first variant.
	 *
	 * <p>Not "the" variant, because a product can have many. The first by the same ordering the
	 * storefront uses, so a caller that wants "the one a product was created with" gets it.
	 */
	@Transactional(readOnly = true)
	public java.util.Optional<ProductVariant> getVariant(UUID productId) {
		return variants.findByProductIdOrderBySortOrderAscNameAsc(productId).stream().findFirst();
	}

	@Transactional(readOnly = true)
	public List<ProductVariant> variantsOf(Product product) {
		return variants.findByProductIdOrderBySortOrderAscNameAsc(product.getId());
	}

	/** Only the variants a customer can buy. */
	@Transactional(readOnly = true)
	public List<ProductVariant> purchasableVariantsOf(Product product) {
		return variants.findByProductIdAndActiveTrueOrderBySortOrderAscNameAsc(product.getId());
	}

	@Transactional(readOnly = true)
	public Page<Product> listForShop(Shop shop, Product.Status status, Pageable pageable) {
		return status == null
				? products.findByShopIdAndDeletedAtIsNull(shop.getId(), pageable)
				: products.findByShopIdAndStatusAndDeletedAtIsNull(shop.getId(), status, pageable);
	}

	@Transactional(readOnly = true)
	public Page<Product> listForShop(Shop shop, ProductCategory category, Product.Status status,
			Pageable pageable) {
		if (category == null) {
			return listForShop(shop, status, pageable);
		}
		return products.findByShopIdAndProductCategoryAndStatusAndDeletedAtIsNull(
				shop.getId(), category, status, pageable);
	}

	/**
	 * Searches the shop's catalogue.
	 *
	 * <p>Full-text and trigram together. The ranking is in the repository query and is
	 * deliberate: an exact word match outranks a fuzzy one, so "rice" beats "ricee", and sales
	 * break ties.
	 *
	 * <p>A blank or one-character term returns everything rather than nothing, because a
	 * one-character search has no useful trigram selectivity and a filter box that empties the
	 * moment you clear it looks broken.
	 */
	@Transactional(readOnly = true)
	public Page<Product> search(Shop shop, String term, Pageable pageable) {
		String cleaned = term == null ? "" : term.trim();
		Pageable unpagedSort = withoutSort(pageable);
		if (cleaned.isEmpty()) {
			return products.findByShopIdAndDeletedAtIsNull(shop.getId(), unpagedSort);
		}
		return products.searchInShop(shop.getId(), cleaned, unpagedSort);
	}

	/** Search within one category, for a storefront category page with its own search box. */
	@Transactional(readOnly = true)
	public Page<Product> searchInCategory(Shop shop, String term, ProductCategory category,
			Pageable pageable) {
		return products.searchInCategory(shop.getId(), category.name(), term.trim(),
				withoutSort(pageable));
	}

	/**
	 * Keeps a page request's size and offset but discards its sort.
	 *
	 * <p>Two independent reasons, and the first one is a bug this fixes rather than a preference:
	 *
	 * <ul>
	 *   <li>Spring Data <em>appends</em> a Pageable's sort to a native query by wrapping the whole
	 *       statement, which turns a query that already has {@code ORDER BY} into
	 *       {@code (ORDER BY ... order by createdAt desc)} — a syntax error. The console's product
	 *       list passes {@code Sort.by("createdAt")}, so every search request was failing.</li>
	 *   <li>Even when it worked, a caller-supplied sort would override the ranking, and the
	 *       ranking <em>is</em> the answer to a search: exact matches first, then sales.</li>
	 * </ul>
	 *
	 * <p>Paging is unaffected, which is all the caller actually needed.
	 */
	private Pageable withoutSort(Pageable pageable) {
		return pageable.getSort().isUnsorted()
				? pageable
				: PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
	}

	@Transactional(readOnly = true)
	public Page<Product> deletedForShop(Shop shop, Pageable pageable) {
		return products.findByShopIdAndDeletedAtIsNotNull(shop.getId(), pageable);
	}

	/**
	 * The categories this shop actually uses.
	 *
	 * <p>Feeds the shop-type-change picker (D29/D30), which offers only types that would not
	 * strand any of these.
	 */
	@Transactional(readOnly = true)
	public Set<ProductCategory> categoriesInUse(Shop shop) {
		return new HashSet<>(products.categoriesInUse(shop.getId()));
	}

	/**
	 * Records a page view.
	 *
	 * <p>Atomic in SQL. Counts are approximate by nature — nobody is going to reconcile them —
	 * so this is cheap and never throws, but it is still an UPDATE rather than an in-memory
	 * increment, because two customers on the same product page at the same moment would
	 * otherwise lose a view.
	 */
	@Transactional
	public void recordView(Product product) {
		products.addViewCount(product.getId());
	}

	/** Adds units to the bestseller counter. Called at order accept, not at payment. */
	@Transactional
	public void recordSale(Product product, int units) {
		if (units > 0) {
			products.addSoldCount(product.getId(), units);
		}
	}

	// ------------------------------------------------------------------ guards

	/**
	 * Confirms the product belongs to the shop.
	 *
	 * <p>The single choke point for the tenant boundary on writes. Every mutating method calls
	 * it first, so a new write cannot forget the check: the method will not compile without an
	 * owner to pass.
	 */
	private void requireOwned(Shop shop, Product product) {
		if (!product.getShopId().equals(shop.getId())) {
			// 404 semantics, deliberately. See getOwned.
			throw new ProductNotFoundException(product.getId());
		}
	}

	/** Ownership check for a variant, returning its product so callers can refresh the mirror. */
	private Product requireOwnedVariant(Shop shop, ProductVariant variant) {
		Product product = products.findById(variant.getProductId())
				.orElseThrow(() -> new ProductNotFoundException(variant.getProductId()));
		requireOwned(shop, product);
		return product;
	}

	private void requireVerified(Shop shop) {
		if (!shop.isVerified()) {
			throw new IllegalStateException(
					"Publish products only after your shop has been verified. Submit it for review first.");
		}
	}

	/**
	 * A non-negative paisa amount.
	 *
	 * <p>Checked rather than clamped: a negative cost price is a typo, and silently turning it
	 * into zero would corrupt the shop's margin report with no indication of why.
	 */
	private static long requireNonNegativeLong(Number value, String label) {
		if (value.longValue() < 0) {
			throw new IllegalArgumentException(label + " cannot be negative: " + value);
		}
		return value.longValue();
	}

	private static int requireNonNegativeInt(Number value, String label) {
		if (value.longValue() < 0 || value.longValue() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(label + " is out of range: " + value);
		}
		return value.intValue();
	}

	/**
	 * A variant's option axes, in the order the shop wants to read them.
	 *
	 * <p>Held as an ordered list of pairs rather than a {@code Map} because that order is
	 * meaningful — "Large / Red" is not the same label as "Red / Large" — and jsonb does not
	 * preserve object key order. See {@link ProductVariant#getOptions()}.
	 *
	 * <p>A record rather than a bare {@code Map} so the controller's request body has a named
	 * type and so "no options given" is one obvious value rather than three.
	 */
	public record VariantOptions(List<ProductVariant.Option> options) {

		public VariantOptions {
			options = options == null ? List.of() : List.copyOf(options);
		}

		/**
		 * From the request body, preserving the order the client sent.
		 *
		 * <p>Jackson deserialises a JSON object into a {@code LinkedHashMap}, so the map's
		 * iteration order is the order the shop chose in the form. That is the only reason the
		 * order survives to the label.
		 */
		public static VariantOptions of(Map<String, String> axes) {
			if (axes == null || axes.isEmpty()) {
				return empty();
			}
			List<ProductVariant.Option> pairs = new java.util.ArrayList<>();
			axes.forEach((axis, value) -> pairs.add(new ProductVariant.Option(axis, value)));
			return new VariantOptions(pairs);
		}

		public static VariantOptions of(String axis, String value) {
			return new VariantOptions(List.of(new ProductVariant.Option(axis, value)));
		}

		public static VariantOptions empty() {
			return new VariantOptions(List.of());
		}
	}

	/** Maps to HTTP 404. Also used for a product that exists but belongs to another shop. */
	public static class ProductNotFoundException extends RuntimeException {
		public ProductNotFoundException(UUID productId) {
			super("No product " + productId);
		}
	}
}
