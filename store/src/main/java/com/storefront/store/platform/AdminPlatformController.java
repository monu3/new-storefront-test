package com.storefront.store.platform;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.storefront.store.common.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * The admin dashboard and shop management (Phase 7).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')}") (D36): every figure here is the
 * marketplace's commercial position, and none of it is a shop's business.
 *
 * <p><b>Featuring and unfeaturing are separate endpoints, and both audited in the service</b> — see
 * {@link ShopAdministrationService} for why featuring is editorial rather than a purchase.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin platform", description = "KPIs, shop management and featured shops")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminPlatformController {

	private static final int MAX_PAGE_SIZE = 100;

	private final KpiService kpis;
	private final ShopAdministrationService shopAdmin;

	/**
	 * The KPI dashboard.
	 *
	 * @param days the window. Every order and GMV figure is for it; growth and churn compare against
	 *        the equally long window before it, which is what makes "up 20%" a claim about a specific
	 *        period rather than about an arbitrary baseline
	 */
	@GetMapping("/kpis")
	@Operation(summary = "GMV, orders, commission, ARPU, churn and the revenue split by shop type")
	public KpiService.KpiDashboard dashboard(
			@RequestParam(defaultValue = "30") @Min(1) @Max(730) int days) {
		return kpis.dashboard(days);
	}

	/**
	 * The featured shops.
	 *
	 * <p>On the admin surface as well as the gallery, because an admin asks "what is featured right
	 * now?" and the answer should not require reading the gallery's ordering rules.
	 *
	 * <p>Deliberately <em>not</em> under {@code /shops}: {@link com.storefront.store.admin.AdminShopController}
	 * owns that path, and two controllers mapping {@code /api/v1/admin/shops/{id}/feature} fail at
	 * startup with an ambiguous-mapping error that does not say the cause is duplication.
	 */
	@GetMapping("/featured-shops")
	@Operation(summary = "The shops currently featured")
	public List<ShopAdministrationService.ShopView> featured() {
		return shopAdmin.featured().stream().map(ShopAdministrationService.ShopView::from).toList();
	}

}