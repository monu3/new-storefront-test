package com.storefront.store.payment;

import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One payment method a shop offers, and the configuration behind it.
 *
 * <p><b>The row exists whether or not the method is enabled.</b> A shop that has turned eSewa off
 * has not forgotten its merchant id, so disabling is a flag rather than a delete. That matters
 * because rotating a merchant id while eSewa is live is how a shop ends up unable to verify a
 * payment it has already taken.
 *
 * <p><b>Secrets are stored as ciphertext and exposed as booleans.</b> The merchant secret and the
 * bank account number live in {@code bytea} columns written by {@link SecretCipher}. This entity
 * has no accessor that returns them as plaintext; {@link PaymentMethodService} decrypts only at
 * the point of use and the API answers with {@code hasSecret} booleans. A secret that can be read
 * by accident is a secret that eventually is.
 *
 * <p>The enabling rule lives in {@link #canBeEnabled} rather than in a setter, because "enabled"
 * for eSewa means something different from "enabled" for cash on delivery: one needs a merchant id
 * and the others do not.
 */
@Entity
@Table(name = "shop_payment_method")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopPaymentMethod extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false)
	private PaymentGateway.ShopOrderPaymentMethod method;

	@Column(name = "is_enabled", nullable = false)
	private boolean enabled = false;

	// ------------------------------------------------------------------ eSewa

	/** Not a secret, so it is stored in the clear and shown back for editing. */
	@Column(name = "esewa_merchant_id")
	private String esewaMerchantId;

	/** Ciphertext. Never returned by the API, only by the gateway at the moment it is needed. */
	@Column(name = "esewa_secret_enc")
	private byte[] esewaSecretEnc;

	// ------------------------------------------------------------------ bank transfer

	@Column(name = "bank_name")
	private String bankName;

	@Column(name = "bank_account_name")
	private String bankAccountName;

	/** Ciphertext, for the same reason as the eSewa secret. */
	@Column(name = "bank_account_number_enc")
	private byte[] bankAccountNumberEnc;

	// ------------------------------------------------------------------ limits

	/**
	 * A surcharge for cash on delivery, in minor units.
	 *
	 * <p>Many shops charge a small fee for COD because the rider's time and the failed-delivery
	 * attempts are real costs. It is per-order, not per-item, and is zero by default.
	 */
	@Column(name = "cod_fee_minor", nullable = false)
	private long codFeeMinor = 0;

	/** Below this order total the method is not offered at all. */
	@Column(name = "minimum_minor", nullable = false)
	private long minimumMinor = 0;

	/** Above this order total the method is not offered. Null means no ceiling. */
	@Column(name = "max_order_minor")
	private Long maxOrderMinor;

	/** What the customer sees in the checkout list. Null means the gateway's own name. */
	@Column(name = "display_label")
	private String displayLabel;

	/** Shown under the method in checkout: what the customer should do next. */
	@Column(name = "instructions")
	private String instructions;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder = 0;

	public ShopPaymentMethod(UUID shopId, PaymentGateway.ShopOrderPaymentMethod method) {
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.method = method;
		this.enabled = false;
	}

	/**
	 * Whether this method has everything it needs to actually take a payment.
	 *
	 * <p>This is the check that stops a shop offering eSewa with no merchant id: the storefront
	 * would list a method the gateway refuses at the last moment, which for the customer means
	 * filling in their details and then being told it does not work.
	 */
	public boolean canBeEnabled() {
		return switch (method) {
			case ESAWA -> esewaMerchantId != null && !esewaMerchantId.isBlank() && esewaSecretEnc != null;
			case BANK_TRANSFER -> bankAccountName != null && !bankAccountName.isBlank()
					&& bankAccountNumberEnc != null;
			case COD -> true;
		};
	}

	/** Why {@link #canBeEnabled()} is false, for the settings screen to show. */
	public String missingConfiguration() {
		if (canBeEnabled()) {
			return null;
		}
		return switch (method) {
			case ESAWA -> "eSewa needs a merchant ID and a merchant secret";
			case BANK_TRANSFER -> "Bank transfer needs an account name and an account number";
			case COD -> null;
		};
	}

	/** True when the method is offered for an order of this size. */
	public boolean appliesTo(long orderMinor) {
		return orderMinor >= minimumMinor
				&& (maxOrderMinor == null || orderMinor <= maxOrderMinor);
	}

	/** The extra amount this method adds to an order, currently only COD's fee. */
	public long surchargeFor(long orderMinor) {
		return method == PaymentGateway.ShopOrderPaymentMethod.COD ? codFeeMinor : 0;
	}

	/** The label to show the customer: the owner's wording, or the gateway's name. */
	public String customerLabel() {
		if (displayLabel != null && !displayLabel.isBlank()) {
			return displayLabel;
		}
		return switch (method) {
			case ESAWA -> "eSewa";
			case COD -> "Cash on delivery";
			case BANK_TRANSFER -> "Bank transfer";
		};
	}
}