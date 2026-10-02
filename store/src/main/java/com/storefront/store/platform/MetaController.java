package com.storefront.store.platform;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Publishes the hardcoded category enums so the frontend never hardcodes them.
 *
 * <p>Public and unauthenticated on purpose: the payload contains no tenant data, it is the same
 * for every caller, and both the public storefront and the shop signup form need it before a
 * user has any relationship with the platform. Cached for an hour in Redis by the caller.
 */
@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "Meta", description = "Static reference data: shop types and product categories")
public class MetaController {

	@GetMapping("/shop-types")
	@Operation(summary = "Every shop type with the product categories it permits",
			description = "Used to populate the shop-type picker at signup and the product "
					+ "category dropdown in the shop dashboard.")
	public List<ShopTypeResponse> shopTypes() {
		return java.util.Arrays.stream(ShopType.values()).map(ShopTypeResponse::from).toList();
	}

	@GetMapping("/shop-types/{code}")
	@Operation(summary = "One shop type by code")
	public ShopTypeResponse shopType(@PathVariable String code) {
		ShopType type = ShopType.fromCode(code);
		if (type == null) {
			throw new UnknownShopTypeException(code);
		}
		return ShopTypeResponse.from(type);
	}

	/**
	 * Shape returned to the client.
	 *
	 * <p>Uses records so the JSON contract is explicit and an added field is a deliberate act
	 * rather than an accidental leak of an enum's internals.
	 */
	public record ShopTypeResponse(
			String code,
			String label,
			String icon,
			String iconGroup,
			List<CategoryResponse> categories) {

		static ShopTypeResponse from(ShopType type) {
			return new ShopTypeResponse(
					type.name(),
					type.label(),
					type.icon(),
					type.iconGroup().name(),
					type.categories().stream().map(CategoryResponse::from).toList());
		}
	}

	public record CategoryResponse(String code, String label, String slug) {
		static CategoryResponse from(ProductCategory category) {
			return new CategoryResponse(category.name(), category.label(), category.slug());
		}
	}

	/** Maps to HTTP 404: an unknown shop type is a bad URL, not a bad request. */
	public static class UnknownShopTypeException extends RuntimeException {
		public UnknownShopTypeException(String code) {
			super("No such shop type: " + code);
		}
	}
}
