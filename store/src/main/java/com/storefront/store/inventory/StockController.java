package com.storefront.store.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.security.CurrentUser;
import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The shop's own stock control.
 *
 * <p>No shop id anywhere — the shop is the caller's own (D35) — and every variant is checked for
 * ownership through {@link ProductService} before a quantity is touched.
 *
 * <p><b>Stock counts are deliberately not public.</b> A storefront that showed "only 2 left"
 * would leak a shop's throughput to its competitors and pressure customers into buying. The
 * customer-facing surface exposes a boolean {@code purchasable}; this one is the shop's own view.
 *
 * <p>Low-stock is a <em>warning</em>, never an auto-depleting rule. The shop knows its stock; the
 * platform has no business deciding when a shop has "too little" of something it intends to
 * restock tomorrow.
 */
@RestController
@RequestMapping("/api/v1/shop/stock")
@Tag(name = "Stock", description = "Restock, stocktake and low-stock warnings")
@PreAuthorize("hasRole('SHOP')")
@RequiredArgsConstructor
public class StockController {

	private final InventoryService inventory;
	private final ProductService products;
	private final TenantGuard tenantGuard;
	private final CurrentUser currentUser;

	/** Stock for every variant of one product. */
	@GetMapping("/products/{productId}")
	@Operation(summary = "Stock for every variant of a product")
	public List<StockView> forProduct(@PathVariable UUID productId) {
		Shop shop = tenantGuard.requireOwnedShop();
		// Ownership checked by the service: a variant belonging to another shop is a 404 here.
		products.getOwned(shop, productId);

		return inventory.forProduct(productId).stream()
				.map(row -> toView(products.getOwnedVariant(shop, row.getVariantId()), row))
				.toList();
	}

	/**
	 * Adds units.
	 *
	 * <p>A delta rather than an absolute quantity, because the movement log records a delta and a
	 * log of "+30" is what makes a discrepancy answerable a month later.
	 */
	@PostMapping("/products/{productId}/variants/{variantId}/restock")
	@Operation(summary = "Add units to a variant",
			description = "Logged as a RESTOCK movement with the delta.")
	public StockView restock(@PathVariable UUID productId, @PathVariable UUID variantId,
			@Valid @RequestBody RestockRequest request) {

		Shop shop = tenantGuard.requireOwnedShop();
		ProductVariant variant = requireOwnedVariant(shop, productId, variantId);

		Inventory row = inventory.restock(variant.getProductId(), variant.getId(),
				request.units(), currentUser.optionalUserId(), request.note());
		return toView(variant, row);
	}

	/**
	 * Sets an absolute count, for a stocktake.
	 *
	 * <p>Logged as an {@code ADJUSTMENT} carrying the difference, so a stocktake that finds 37
	 * where the system said 40 leaves a trace of the 3 that went missing.
	 *
	 * <p>Refused when the new count is below what placed orders have already reserved — silently
	 * accepting it would oversell those customers.
	 */
	@PutMapping("/products/{productId}/variants/{variantId}")
	@Operation(summary = "Set the count outright, for a stocktake",
			description = "Logged as the difference. Refused if below what open orders have reserved.")
	public StockView setCount(@PathVariable UUID productId, @PathVariable UUID variantId,
			@Valid @RequestBody SetCountRequest request) {

		Shop shop = tenantGuard.requireOwnedShop();
		ProductVariant variant = requireOwnedVariant(shop, productId, variantId);

		Inventory row = inventory.setOnHand(variant.getProductId(), variant.getId(),
				request.quantity(), currentUser.optionalUserId(), request.note());
		return toView(variant, row);
	}

	/** The shop's low-stock list: everything at or below its own threshold. */
	@GetMapping("/low")
	@Operation(summary = "Variants at or below their low-stock threshold",
			description = "A warning only. The platform never auto-depletes a shop's stock.")
	public List<StockView> low() {
		Shop shop = tenantGuard.requireOwnedShop();

		return inventory.lowStock().stream()
				.filter(row -> ownedBy(shop, row.getProductId()))
				.map(row -> toView(
						products.getOwnedVariant(shop, row.getVariantId()), row))
				.toList();
	}

	/**
	 * The movement log for one variant.
	 *
	 * <p>This is the screen that settles "you say I ordered 40 and you say I only got 30" — months
	 * later, when nobody remembers.
	 */
	@GetMapping("/products/{productId}/variants/{variantId}/movements")
	@Operation(summary = "Stock movements for a variant, newest first")
	public ResponseEntity<java.util.Map<String, Object>> movements(
			@PathVariable UUID productId, @PathVariable UUID variantId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) int size) {

		Shop shop = tenantGuard.requireOwnedShop();
		ProductVariant variant = requireOwnedVariant(shop, productId, variantId);

		var result = inventory.movements(variant.getProductId(), variant.getId(),
				PageRequest.of(page, Math.min(size, 100)));

		return ResponseEntity.ok(java.util.Map.of(
				"content", result.getContent().stream().map(StockController::toMovement).toList(),
				"page", result.getNumber(),
				"size", result.getSize(),
				"totalElements", result.getTotalElements(),
				"totalPages", result.getTotalPages(),
				"last", result.isLast()));
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * The variant, with ownership of both the variant and its product confirmed.
	 *
	 * <p>Both are checked because a variant id alone does not say which shop it belongs to, and
	 * restocking another shop's variant would be a write straight across a tenant boundary.
	 */
	private ProductVariant requireOwnedVariant(Shop shop, UUID productId, UUID variantId) {
		products.getOwned(shop, productId);
		return products.getOwnedVariant(shop, variantId);
	}

	private boolean ownedBy(Shop shop, UUID productId) {
		return products.getOwned(shop, productId) != null;
	}

	private StockView toView(ProductVariant variant, Inventory row) {
		return new StockView(
				variant.getProductId(),
				variant.getId(),
				variant.getName(),
				variant.getSku(),
				row.getQuantityOnHand(),
				row.getQuantityReserved(),
				row.available(),
				row.getLowStockThreshold(),
				row.isLowStock());
	}

	private static MovementView toMovement(InventoryMovement movement) {
		return new MovementView(
				movement.getProductId(),
				movement.getVariantId(),
				movement.getDelta(),
				movement.getReason().name(),
				movement.getNote(),
				movement.getCreatedAt());
	}

	// ------------------------------------------------------------------ requests

	public record RestockRequest(
			@NotNull @Min(1) Integer units,
			@Size(max = 200) String note) {
	}

	public record SetCountRequest(
			@NotNull @Min(0) Integer quantity,
			@Size(max = 200) String note) {
	}

	// ------------------------------------------------------------------ responses

	/**
	 * A variant's stock, from the shop's own point of view.
	 *
	 * <p>{@code available} is the number that matters: {@code onHand − reserved}. Showing only
	 * on-hand would tell an owner they can sell more than they can.
	 */
	public record StockView(
			UUID productId,
			UUID variantId,
			String variantName,
			String sku,
			int onHand,
			int reserved,
			int available,
			int lowStockThreshold,
			boolean lowStock) {
	}

	public record MovementView(
			UUID productId,
			UUID variantId,
			int delta,
			String reason,
			String note,
			java.time.Instant at) {
	}
}
