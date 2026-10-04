package com.storefront.store.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.security.TenantGuard;
import com.storefront.store.settings.ShopSettingService.SettingsView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The owner's settings, one endpoint per tab.
 *
 * <p>The shop is resolved from the token and never taken as a parameter (D35), so there is no
 * cross-tenant question to answer here — the only thing a caller can address is their own shop's
 * settings, because {@link TenantGuard} resolves which shop that is.
 *
 * <p><b>One PUT per tab, not one PUT for the document.</b> A settings form saves a section at a
 * time, and a single endpoint for the whole document would mean every save re-writes all eight
 * sections from a form that only loaded some of them. Splitting by section makes each write touch
 * exactly one column.
 */
@RestController
@RequestMapping("/api/v1/shop/settings")
@Tag(name = "Settings", description = "General, address, social, checkout, notifications, order numbers, invoice and email templates")
@PreAuthorize("hasRole('SHOP')")
public class ShopSettingController {

	private final ShopSettingService settings;
	private final TenantGuard tenants;

	public ShopSettingController(ShopSettingService settings, TenantGuard tenants) {
		this.settings = settings;
		this.tenants = tenants;
	}

	/** Every tab at once, so the settings screen needs one request rather than eight. */
	@GetMapping
	@Operation(summary = "All settings, with defaults filled in")
	public SettingsView all() {
		return settings.view(tenants.requireOwnedShop().getId());
	}

	@PutMapping("/general")
	@Operation(summary = "Save the General tab")
	public SettingsSections.General general(
			@Valid @RequestBody SettingsSections.General body) {
		settings.writeSection(shopId(), SettingsSections.General.class, body, "general");
		return body;
	}

	@PutMapping("/address")
	@Operation(summary = "Save the Address tab")
	public SettingsSections.Address address(
			@Valid @RequestBody SettingsSections.Address body) {
		settings.writeSection(shopId(), SettingsSections.Address.class, body, "address");
		return body;
	}

	@PutMapping("/social")
	@Operation(summary = "Save the Social tab")
	public SettingsSections.Social social(
			@Valid @RequestBody SettingsSections.Social body) {
		settings.writeSection(shopId(), SettingsSections.Social.class, body, "social");
		return body;
	}

	@PutMapping("/checkout")
	@Operation(summary = "Save the Checkout tab")
	public SettingsSections.Checkout checkout(
			@Valid @RequestBody SettingsSections.Checkout body) {
		settings.writeSection(shopId(), SettingsSections.Checkout.class, body, "checkout");
		return body;
	}

	@PutMapping("/notifications")
	@Operation(summary = "Save the Notifications tab")
	public SettingsSections.Notifications notifications(
			@Valid @RequestBody SettingsSections.Notifications body) {
		settings.writeSection(shopId(), SettingsSections.Notifications.class, body, "notifications");
		return body;
	}

	/**
	 * The order-number format.
	 *
	 * <p>The {@code next} counter is ignored on write. It is advanced inside the allocation
	 * transaction, and accepting a client-supplied value would let a form that loaded the settings
	 * an hour ago and saved them now rewind the sequence and reissue a number that already exists.
	 */
	@PutMapping("/order-number")
	@Operation(summary = "Save the order-number format",
			description = "The counter is ignored on write; only the format changes existing behaviour.")
	public SettingsSections.OrderNumber orderNumber(
			@Valid @RequestBody SettingsSections.OrderNumber body) {
		// The counter is read back, never taken from the request.
		settings.writeSection(shopId(), SettingsSections.OrderNumber.class,
				new SettingsSections.OrderNumber(body.format(), settings.orderNumber(shopId()).next()),
				"orderNumber");
		return settings.orderNumber(shopId());
	}

	@PutMapping("/invoice")
	@Operation(summary = "Save the invoice template tab")
	public SettingsSections.InvoiceTemplate invoice(
			@Valid @RequestBody SettingsSections.InvoiceTemplate body) {
		settings.writeSection(shopId(), SettingsSections.InvoiceTemplate.class, body, "invoiceTemplate");
		return body;
	}

	@PutMapping("/emails")
	@Operation(summary = "Save the email templates tab")
	public SettingsSections.EmailTemplates emails(
			@Valid @RequestBody SettingsSections.EmailTemplates body) {
		settings.writeSection(shopId(), SettingsSections.EmailTemplates.class, body, "emailTemplates");
		return body;
	}

	// ------------------------------------------------------------------ plumbing

	private java.util.UUID shopId() {
		return tenants.requireOwnedShop().getId();
	}
}