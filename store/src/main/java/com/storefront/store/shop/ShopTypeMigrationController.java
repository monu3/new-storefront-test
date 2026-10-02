package com.storefront.store.shop;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.CategoryValidator;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Shop-type migration support.
 *
 * <p>Shops do pivot, so the owner can change their own type. The hard part is that a type
 * change constrains every product the shop already sells, so this endpoint answers the
 * question the UI actually needs: <em>which types can I move to right now?</em>
 *
 * <p>Rather than rejecting every change and pushing the owner to a support ticket, the picker
 * only offers reachable types. A shop selling only grocery staples sees the narrower grocery
 * types; a shop also selling something outside the new type's categories is told exactly which
 * products to recategorise first. Nothing is ever silently rewritten.
 *
 * <p>Placeholder routes return empty data until the shop and product tables exist in Phase 1.
 */
@RestController
@RequestMapping("/api/v1/shop/type-migration")
@Tag(name = "Shop", description = "Shop profile and settings")
public class ShopTypeMigrationController {

	private static final Logger log = LoggerFactory.getLogger(ShopTypeMigrationController.class);

	private final CategoryValidator validator;

	public ShopTypeMigrationController(CategoryValidator validator) {
		this.validator = validator;
	}

	@GetMapping("/reachable")
	@Operation(summary = "Shop types this shop can switch to without recategorising products",
			description = "The current type is excluded. A shop with no products can reach every type.")
	public ReachableTypesResponse reachable(
			@RequestParam("currentType") ShopType currentType,
			@RequestParam(name = "currentCategories", required = false) List<String> currentCategories) {

		// Categories are read from Postgres by the real service in Phase 1. Until then they
		// arrive as query parameters. A blank or absent value means "no products", which is
		// a legitimate state for a shop that has not added anything yet.
		Set<ProductCategory> categories = parseCategories(currentCategories);

		List<ShopType> reachable = validator.reachableTypes(currentType, categories);

		return new ReachableTypesResponse(
				currentType,
				// Returned so the UI can explain *why* other types are missing, rather than
				// silently omitting them and leaving the owner to guess.
				categories.stream().map(ProductCategory::label).sorted().toList(),
				reachable.stream().map(ShopType::name).toList(),
				blockedTypes(currentType, categories, reachable));
	}

	/**
	 * Parses category codes, ignoring blanks.
	 *
	 * <p>An unrecognised code is dropped rather than rejected: this is a read-only advisory
	 * endpoint, and a single stale value in a query string should not 400 a page the owner is
	 * trying to load. The real service passes enums straight from the database.
	 */
	private Set<ProductCategory> parseCategories(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return Set.of();
		}
		Set<ProductCategory> parsed = new java.util.LinkedHashSet<>();
		for (String value : raw) {
			if (value == null || value.isBlank()) {
				continue;
			}
			ProductCategory category = ProductCategory.fromCode(value);
			if (category == null) {
				log.warn("Ignoring unknown product category '{}' in type-migration request", value);
				continue;
			}
			parsed.add(category);
		}
		return Set.copyOf(parsed);
	}

	/**
	 * Types currently unavailable, each with the categories that block it. Rendering the reason
	 * next to the option is what turns a dead end into a to-do list.
	 */
	private List<BlockedType> blockedTypes(
			ShopType currentType, Set<ProductCategory> currentCategories, List<ShopType> reachable) {

		List<BlockedType> blocked = new java.util.ArrayList<>();
		for (ShopType candidate : ShopType.values()) {
			if (candidate == currentType || reachable.contains(candidate)) {
				continue;
			}
			Set<ProductCategory> blocking = validator.invalidAfterTypeChange(candidate, currentCategories);
			blocked.add(new BlockedType(
					candidate.name(),
					candidate.label(),
					blocking.stream().map(ProductCategory::label).sorted().toList(),
					blocking.size()));
		}
		return List.copyOf(blocked);
	}

	public record ReachableTypesResponse(
			ShopType currentType,
			List<String> currentCategories,
			List<String> reachable,
			List<BlockedType> blocked) {
	}

	public record BlockedType(
			String code,
			String label,
			/** Category labels the owner must recategorise or remove first. */
			List<String> blockingCategories,
			int blockingCount) {
	}
}
