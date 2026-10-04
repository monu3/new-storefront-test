package com.storefront.store.settings;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The owner's long-tail settings for their shop, one row per shop.
 *
 * <p><b>One column per settings tab, not one JSON blob.</b> The obvious design is a single
 * {@code document jsonb} holding everything. That was rejected because the settings screen saves
 * a section at a time: if all sections share one blob, saving the Address tab is a read-modify-write
 * of the whole document, and two tabs saved in the same second can silently lose one of the two
 * changes. A column per tab makes each save touch exactly one field, so there is nothing to lose.
 *
 * <p>The columns are JSONB rather than relational because they are options that change with the
 * UI, not data that is queried or constrained. Anything that needs an index, a foreign key or a
 * uniqueness rule is a table and lives elsewhere — payment methods, discounts, tax rules and
 * shipping zones are all separate tables for exactly that reason.
 *
 * <p><b>Stored as {@link String}, not as a Java object.</b> Hibernate will happily map a record
 * to JSONB, but then a column added to the database has no matching field and a field removed from
 * the record loses data on the next write. Raw JSON with an explicit mapper in
 * {@link ShopSettingService} keeps that migration story in Java, where it can be read, instead of
 * implicit in an annotation.
 *
 * <p>Rows are created lazily on first read rather than at shop signup. A shop that has never
 * opened its settings should not cost a row, and a missing row and a row of defaults must be
 * indistinguishable to callers — that is enforced by {@link ShopSettingService#load}.
 */
@Entity
@Table(name = "shop_setting")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopSetting extends BaseEntity {

	@Id
	@Column(name = "shop_id", nullable = false, updatable = false)
	private UUID shopId;

	/** Display name, tagline, currency, timezone, support contact. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "general", nullable = false, columnDefinition = "jsonb")
	private String general = "{}";

	/** The shop's own postal address, used on invoices and as the shipping origin. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "address", nullable = false, columnDefinition = "jsonb")
	private String address = "{}";

	/** External profile links. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "social", nullable = false, columnDefinition = "jsonb")
	private String social = "{}";

	/** How the basket may be paid for and fulfilled. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "checkout", nullable = false, columnDefinition = "jsonb")
	private String checkout = "{}";

	/** Which events the owner wants told about, and where. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "notifications", nullable = false, columnDefinition = "jsonb")
	private String notifications = "{}";

	/**
	 * The order-number format and its counter.
	 *
	 * <p>Held in JSONB with the rest of the settings rather than promoted to columns because it
	 * is advanced <em>inside the order-number allocation transaction</em>. A relational counter
	 * row would want a {@code SELECT … FOR UPDATE}, which is exactly what
	 * {@code OrderNumberSequence} already is; keeping the counter here would mean two competing
	 * implementations of the same locking rule.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "order_number", nullable = false, columnDefinition = "jsonb")
	private String orderNumber = "{}";

	/** The invoice PDF's layout options. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "invoice_template", nullable = false, columnDefinition = "jsonb")
	private String invoiceTemplate = "{}";

	/** Owner-editable copy for the transactional emails. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "email_templates", nullable = false, columnDefinition = "jsonb")
	private String emailTemplates = "{}";

	/**
	 * Custom CSS, stored but never rendered (D15).
	 *
	 * <p>Persisted so an owner does not lose their work while the sanitizer is built. Nothing
	 * reads this column into a page; that is the whole point of the decision.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "appearance_extra", nullable = false, columnDefinition = "jsonb")
	private String appearanceExtra = "{}";

	public ShopSetting(UUID shopId) {
		this.shopId = shopId;
	}

	/**
	 * The database default, kept here so a new shop and a migrated shop agree.
	 *
	 * <p>Unused by the service, which defaults through {@link SettingsSections.OrderNumber}
	 * instead: an absent key and an empty object must behave the same way, and doing that in the
	 * record means it holds however the JSON was produced.
	 */
	public static final String DEFAULT_ORDER_NUMBER =
			"{\"format\":\"" + SettingsSections.OrderNumber.DEFAULT_FORMAT + "\",\"next\":1}";

	/** True when nothing has been customised, so the API can answer without touching each column. */
	public boolean isPristine() {
		return isBlankJson(general) && isBlankJson(address) && isBlankJson(social) && isBlankJson(checkout)
				&& isBlankJson(notifications) && isBlankJson(invoiceTemplate) && isBlankJson(emailTemplates)
				&& isBlankJson(appearanceExtra);
	}

	private static boolean isBlankJson(String json) {
		return json == null || json.isBlank() || "{}".equals(json.trim());
	}

	/** When this settings row was last written. Re-exported for the settings screen. */
	public Instant touchedAt() {
		return getUpdatedAt();
	}
}