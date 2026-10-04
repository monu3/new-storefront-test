package com.storefront.store.payment;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.payment.PaymentGateway.ShopOrderPaymentMethod;
import com.storefront.store.security.TenantGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Configuring which payment methods a shop offers.
 *
 * <p><b>Three rows always exist per shop</b>, created on first read. A shop has to be able to
 * configure cash on delivery without first having saved an eSewa row, and a settings screen that
 * only lists rows which happen to exist would show an empty page on day one and never explain why.
 *
 * <p><b>Secrets go in, booleans come out.</b> The API accepts a merchant secret and returns
 * {@code hasSecret: true}. It never returns the secret, because the only thing the console needs
 * to render is whether a secret is set — and an API that can return a secret will eventually have
 * one logged or captured in a screenshot.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentMethodService {

	private final ShopPaymentMethodRepository methods;
	private final SecretCipher cipher;
	private final TenantGuard tenants;

	/** The three methods, in the order a shop would want to offer them. */
	private static final List<ShopOrderPaymentMethod> ALL = List.of(
			ShopOrderPaymentMethod.COD,
			ShopOrderPaymentMethod.ESAWA,
			ShopOrderPaymentMethod.BANK_TRANSFER);

	// ------------------------------------------------------------------ read

	/** The caller's shop's methods, with the default rows created if absent. */
	@Transactional
	public List<ShopPaymentMethod> list() {
		UUID shopId = tenants.requireOwnedShop().getId();
		ensureRows(shopId);
		return methods.findByShopIdOrderBySortOrderAscMethodAsc(shopId);
	}

	/**
	 * The methods offered for an order of this size, for the storefront.
	 *
	 * <p>Filters on both the enabled flag and {@link ShopPaymentMethod#appliesTo(long)}, and drops
	 * any method that is not fully configured. A half-configured method that is somehow enabled
	 * still must not reach a customer.
	 */
	@Transactional(readOnly = true)
	public List<ShopPaymentMethod> offeredFor(long orderMinor) {
		UUID shopId = tenants.requireOwnedShop().getId();
		return methods.findByShopIdAndEnabledTrue(shopId).stream()
				.filter(ShopPaymentMethod::canBeEnabled)
				.filter(m -> m.appliesTo(orderMinor))
				.sorted(Comparator.comparingInt(ShopPaymentMethod::getSortOrder))
				.toList();
	}

	/** One method, owned. */
	@Transactional(readOnly = true)
	public ShopPaymentMethod get(ShopOrderPaymentMethod method) {
		UUID shopId = tenants.requireOwnedShop().getId();
		return methods.findByShopIdAndMethod(shopId, method)
				.orElseThrow(() -> new PaymentMethodNotConfiguredException(method));
	}

	// ------------------------------------------------------------------ write

	/**
	 * Saves a method's configuration.
	 *
	 * <p>A {@code null} secret means "leave what is there". That is the only sensible reading of a
	 * form that masks a field it cannot read: the console sends back what it has, and re-saving
	 * the merchant id must not wipe the secret. Clearing a secret is a separate, explicit flag.
	 */
	@Transactional
	public ShopPaymentMethod configure(ShopOrderPaymentMethod method, Configuration draft) {
		UUID shopId = tenants.requireOwnedShop().getId();
		ShopPaymentMethod row = methods.findByShopIdAndMethod(shopId, method)
				.orElseGet(() -> methods.save(new ShopPaymentMethod(shopId, method)));

		if (draft.esewaMerchantId() != null) {
			row.setEsewaMerchantId(blankToNull(draft.esewaMerchantId()));
		}
		if (draft.esewaSecret() != null && !draft.esewaSecret().isEmpty()) {
			row.setEsewaSecretEnc(cipher.encrypt(draft.esewaSecret()));
		}
		if (draft.bankName() != null) {
			row.setBankName(blankToNull(draft.bankName()));
		}
		if (draft.bankAccountName() != null) {
			row.setBankAccountName(blankToNull(draft.bankAccountName()));
		}
		if (draft.bankAccountNumber() != null && !draft.bankAccountNumber().isEmpty()) {
			row.setBankAccountNumberEnc(cipher.encrypt(draft.bankAccountNumber()));
		}

		row.setCodFeeMinor(requireNonNegative(draft.codFeeMinor()));
		row.setMinimumMinor(requireNonNegative(draft.minimumMinor()));
		row.setMaxOrderMinor(draft.maxOrderMinor() == null ? null
				: requireNonNegative(draft.maxOrderMinor()));
		row.setDisplayLabel(blankToNull(draft.displayLabel()));
		row.setInstructions(blankToNull(draft.instructions()));
		row.setSortOrder(draft.sortOrder());

		// A window where min is above max offers the method for no order at all. That is almost
		// certainly a typo, so it is refused rather than silently stored.
		if (row.getMaxOrderMinor() != null && row.getMaxOrderMinor() < row.getMinimumMinor()) {
			throw new IllegalArgumentException(
					"The maximum order total must be greater than the minimum, or no order qualifies");
		}

		// Enabling is checked against the row *after* the draft is applied, so saving a merchant id
		// and enabling eSewa in one request works, while enabling it without one is refused.
		if (draft.enabled() && !row.canBeEnabled()) {
			throw new IllegalArgumentException(row.missingConfiguration());
		}
		row.setEnabled(draft.enabled() && row.canBeEnabled());

		return methods.save(row);
	}

	/**
	 * Explicitly forgets a secret.
	 *
	 * <p>Separate from saving a blank one because the two mean opposite things: an empty string in
	 * the form is "unchanged", while this is "delete it".
	 */
	@Transactional
	public ShopPaymentMethod clearSecret(ShopOrderPaymentMethod method) {
		ShopPaymentMethod row = get(method);
		switch (method) {
			case ESAWA -> {
				row.setEsewaSecretEnc(null);
				// An eSewa row with no secret cannot take a payment, so it stops being offered.
				row.setEnabled(false);
			}
			case BANK_TRANSFER -> row.setBankAccountNumberEnc(null);
			case COD -> {
				// COD has no secret; clearing one is a no-op rather than an error, so the console
				// can send the same clear request for every tab.
			}
		}
		return methods.save(row);
	}

	/** Whether this build can store secrets at all. The settings screen disables the fields if not. */
	public boolean secretsAvailable() {
		return cipher.isConfigured();
	}

	// ------------------------------------------------------------------ plumbing

	/** Creates the three default rows on first use, so the settings screen is never blank. */
	private void ensureRows(UUID shopId) {
		List<ShopPaymentMethod> existing = methods.findByShopIdOrderBySortOrderAscMethodAsc(shopId);
		if (existing.size() == ALL.size()) {
			return;
		}
		int order = existing.size();
		for (ShopOrderPaymentMethod method : ALL) {
			if (existing.stream().noneMatch(m -> m.getMethod() == method)) {
				ShopPaymentMethod row = new ShopPaymentMethod(shopId, method);
				row.setSortOrder(order++);
				methods.save(row);
			}
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static long requireNonNegative(Long value) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException("An amount cannot be negative: " + value);
		}
		return value == null ? 0 : value;
	}

	// ------------------------------------------------------------------ types

	/** What the console sends for one method. */
	public record Configuration(
			/** Null leaves the stored merchant id alone. */
			String esewaMerchantId,
			/** Null or empty leaves the stored secret alone. */
			String esewaSecret,
			String bankName,
			String bankAccountName,
			/** Null or empty leaves the stored account number alone. */
			String bankAccountNumber,
			Long codFeeMinor,
			Long minimumMinor,
			Long maxOrderMinor,
			String displayLabel,
			String instructions,
			int sortOrder,
			boolean enabled) {
	}

	public static class PaymentMethodNotConfiguredException extends RuntimeException {
		public PaymentMethodNotConfiguredException(ShopOrderPaymentMethod method) {
			super("No " + method + " row exists for this shop");
		}
	}
}