package com.storefront.store.checkout;

import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A sales tax rule.
 *
 * <p>The rate is in basis points (1300 = 13%) so the arithmetic is integer throughout. A rate
 * stored as a {@code double} accumulates representation error, and a tax figure that is a paisa
 * out is a support ticket and an accountant's suspicion.
 *
 * <p><b>{@link #inclusive} is the one genuinely subtle field here.</b> An inclusive tax is already
 * inside the displayed price: a Rs. 100 item at 13% inclusive contains Rs. 11.50 of tax, so the
 * tax is <em>extracted</em> from the price rather than added to it. Getting this backwards
 * overcharges every customer by the tax amount, which is why {@link #taxOn} implements both
 * directions explicitly rather than treating inclusive as a display flag.
 */
@Entity
@Table(name = "tax_rule")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaxRule extends BaseEntity {

	/** Nepal's standard VAT rate, for a shop that wants a sensible default. */
	public static final int NEPAL_VAT_BP = 1300;

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "rate_bp", nullable = false)
	private int rateBp;

	/** True when the tax is already inside the price. */
	@Column(name = "inclusive", nullable = false)
	private boolean inclusive = false;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	public TaxRule(UUID shopId, String name, int rateBp, boolean inclusive) {
		if (shopId == null) {
			throw new IllegalArgumentException("shopId is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A tax rule needs a name");
		}
		if (rateBp < 0 || rateBp > 10_000) {
			// Above 10000 bp the "tax" exceeds the price, which is never what a shop means.
			throw new IllegalArgumentException(
					"Tax rate must be between 0 and 10000 basis points, got: " + rateBp);
		}
		this.id = UUID.randomUUID();
		this.shopId = shopId;
		this.name = name.trim();
		this.rateBp = rateBp;
		this.inclusive = inclusive;
		this.active = true;
	}

	/**
	 * The tax on an amount.
	 *
	 * <p>Exclusive: added on top. Inclusive: taken out of it, which is a division rather than a
	 * multiplication — {@code amount × rate / (100% + rate)}.
	 *
	 * @param amountMinor the line total after discount, before tax
	 * @return the tax portion, in paisa
	 */
	public long taxOn(long amountMinor) {
		if (amountMinor <= 0 || rateBp == 0) {
			return 0;
		}
		if (inclusive) {
			// Extract the tax already contained in the price.
			// amount / (1 + rate) = tax, so tax = amount - amount/(1+rate).
			long net = Math.round((double) amountMinor / (1.0 + rateBp / 10_000.0));
			return Math.max(0, amountMinor - net);
		}
		return Math.round(amountMinor * (double) rateBp / 10_000.0);
	}

	/** The rate as a percentage, for display: 1300 → "13". */
	public int ratePercent() {
		return rateBp / 100;
	}

	/** "VAT (13%)" or "VAT (13%, inclusive)". */
	public String label() {
		return rateBp == 0 ? name : name + " (" + ratePercent() + "%" + (inclusive ? ", inclusive" : "") + ")";
	}
}
