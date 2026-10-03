package com.storefront.store.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The shop dashboard (Phase 4).
 *
 * <p>Class-level {@code @PreAuthorize}: the console is for shop owners, and every figure here is
 * one shop's commercial data. No shop id is accepted — the shop comes from the token (D35), so a
 * caller cannot ask for a competitor's revenue.
 */
@RestController
@RequestMapping("/api/v1/shop/dashboard")
@Tag(name = "Shop dashboard", description = "Revenue, sales and catalogue figures for the signed-in shop")
@PreAuthorize("hasRole('SHOP')")
@RequiredArgsConstructor
public class ShopDashboardController {

	private final ShopDashboardService dashboard;
	private final TenantGuard tenantGuard;

	@GetMapping
	@Operation(summary = "The signed-in shop's dashboard figures",
			description = "Derived from the shop's own orders and catalogue. No shop id is accepted.")
	public ShopDashboardService.DashboardView dashboard() {
		Shop shop = tenantGuard.requireOwnedShop();
		return dashboard.forShop(shop).toView();
	}
}
