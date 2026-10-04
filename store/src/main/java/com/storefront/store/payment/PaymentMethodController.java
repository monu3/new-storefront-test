package com.storefront.store.payment;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.payment.PaymentGateway.ShopOrderPaymentMethod;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * The shop's payment configuration, including eSewa merchant credentials (Phase 4).
 *
 * <p><b>Secrets are write-only.</b> The request accepts {@code esewaSecret} and
 * {@code bankAccountNumber}; the response returns {@code hasSecret} and {@code hasAccountNumber}
 * and never the values. A console cannot redact what it is never sent, so there is no way for a
 * secret to end up in a network log, a React state dump, or a screenshot of the settings page.
 *
 * <p><b>{@code clearSecret} is explicit.</b> Saving an empty secret means "unchanged", so the only
 * way to forget a credential is a separate call — which also stops a save from silently deleting a
 * working configuration.
 */
@RestController
@RequestMapping("/api/v1/shop/payments")
@Tag(name = "Payment methods", description = "Which methods a shop offers, and the merchant credentials behind them")
@PreAuthorize("hasRole('SHOP')")
public class PaymentMethodController {

	private final PaymentMethodService service;

	public PaymentMethodController(PaymentMethodService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "This shop's payment methods",
			description = "Secrets are never returned; use hasSecret to render the field state.")
	public ListResponse methods() {
		return new ListResponse(
				service.secretsAvailable(),
				service.list().stream().map(this::toView).toList());
	}

	@PutMapping("/{method}")
	@Operation(summary = "Configure one payment method")
	public MethodView configure(
			@PathVariable ShopOrderPaymentMethod method,
			@Valid @RequestBody ConfigureRequest body) {
		return toView(service.configure(method, body.toConfiguration()));
	}

	@DeleteMapping("/{method}/secret")
	@Operation(summary = "Forget the stored secret")
	public MethodView clearSecret(@PathVariable ShopOrderPaymentMethod method) {
		return toView(service.clearSecret(method));
	}

	// ------------------------------------------------------------------ mapping

	/**
	 * A method as the console sees it.
	 *
	 * <p>{@code canBeEnabled} and {@code missingConfiguration} are what make the form honest: rather
	 * than a save failing with "eSewa needs a merchant ID" after the owner presses Save, the
	 * disabled toggle already carries the reason.
	 */
	private MethodView toView(ShopPaymentMethod m) {
		return new MethodView(
				m.getMethod(),
				m.getDisplayLabel(),
				m.customerLabel(),
				m.isEnabled(),
				m.canBeEnabled(),
				m.missingConfiguration(),
				m.getSortOrder(),
				// eSewa
				m.getEsewaMerchantId(),
				m.getEsewaSecretEnc() != null,
				// Bank transfer
				m.getBankName(),
				m.getBankAccountName(),
				m.getBankAccountNumberEnc() != null,
				// Shared
				m.getCodFeeMinor(),
				m.getMinimumMinor(),
				m.getMaxOrderMinor(),
				m.getInstructions());
	}

	// ------------------------------------------------------------------ types

	public record ListResponse(boolean secretsAvailable, List<MethodView> methods) {
	}

	public record ConfigureRequest(
			@Size(max = 120) String esewaMerchantId,
			/** Write-only. Null or empty leaves the stored secret untouched. */
			@Size(max = 300) String esewaSecret,
			@Size(max = 120) String bankName,
			@Size(max = 120) String bankAccountName,
			/** Write-only. Null or empty leaves the stored account number untouched. */
			@Size(max = 120) String bankAccountNumber,
			@Min(0) Long codFeeMinor,
			@Min(0) Long minimumMinor,
			@Min(0) Long maxOrderMinor,
			@Size(max = 80) String displayLabel,
			@Size(max = 500) String instructions,
			@Min(0) int sortOrder,
			boolean enabled) {

		PaymentMethodService.Configuration toConfiguration() {
			return new PaymentMethodService.Configuration(esewaMerchantId, esewaSecret, bankName,
					bankAccountName, bankAccountNumber, codFeeMinor, minimumMinor, maxOrderMinor,
					displayLabel, instructions, sortOrder, enabled);
		}
	}

	public record MethodView(
			ShopOrderPaymentMethod method,
			String displayLabel,
			String customerLabel,
			boolean enabled,
			boolean canBeEnabled,
			String missingConfiguration,
			int sortOrder,
			String esewaMerchantId,
			boolean hasEsewaSecret,
			String bankName,
			String bankAccountName,
			boolean hasBankAccountNumber,
			long codFeeMinor,
			long minimumMinor,
			Long maxOrderMinor,
			String instructions) {
	}
}