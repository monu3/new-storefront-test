package com.storefront.store.settings;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.settings.SettingsSections.Address;
import com.storefront.store.settings.SettingsSections.Checkout;
import com.storefront.store.settings.SettingsSections.EmailTemplates;
import com.storefront.store.settings.SettingsSections.General;
import com.storefront.store.settings.SettingsSections.InvoiceTemplate;
import com.storefront.store.settings.SettingsSections.Notifications;
import com.storefront.store.settings.SettingsSections.OrderNumber;
import com.storefront.store.settings.SettingsSections.Social;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads and writes the owner's settings.
 *
 * <p><b>Lazy rows, defaults in Java.</b> A shop with no settings row and a shop whose row is full
 * of {@code {}} must be indistinguishable to every caller, or half the codebase needs a null check
 * and the other half forgets one. {@link #load} creates the row on first read and
 * {@link #defaults} supplies the values, so callers never see either state.
 *
 * <p>Every section is read through a typed record and every write goes back through one. That
 * round trip is what validates the JSON: a column written by an older version of the UI that has
 * a field this version dropped does not blow up, it falls back to the default for that field. The
 * alternative -- handing callers raw {@code JsonNode} -- pushes that failure to the storefront.
 */
@Service
@Slf4j
public class ShopSettingService {

	private final ShopSettingRepository settings;

	/**
	 * A mapper that tolerates what it finds.
	 *
	 * <p>A <em>copy</em> of the application's mapper, not the bean itself. Two settings decisions
	 * require it, and neither may be imposed on every other part of the system:
	 *
	 * <ul>
	 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} off, so a settings document written by a newer
	 *       console survives a round trip through an older backend instead of 500-ing on read.</li>
	 *   <li>{@code FAIL_ON_NULL_FOR_PRIMITIVES} off, because every section record has primitive
	 *       {@code boolean} fields and an absent JSON key must mean the default, not an error.</li>
	 * </ul>
	 */
	private final ObjectMapper json;

	public ShopSettingService(ShopSettingRepository settings, ObjectMapper appMapper) {
		this.settings = settings;
		this.json = appMapper.copy()
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	}

	/** What a shop sees before it has customised anything. */
	public static General defaultGeneral() {
		return new General(null, null, null, null, null, "NPR", "Asia/Kathmandu");
	}

	public static Address defaultAddress() {
		return new Address(null, null, null, null, null, null, null);
	}

	public static Social defaultSocial() {
		return new Social(null, null, null, null, null, null);
	}

	public static Checkout defaultCheckout() {
		return new Checkout(true, 0L, false, true, true, null);
	}

	public static Notifications defaultNotifications() {
		return new Notifications(true, true, 5, false);
	}

	public static InvoiceTemplate defaultInvoiceTemplate() {
		return new InvoiceTemplate(true, true, true, true, "#1f2937", null, null);
	}

	// ------------------------------------------------------------------ read

	/** The shop's settings row, created on first use. Never null. */
	@Transactional
	public ShopSetting load(UUID shopId) {
		return settings.findByShopId(shopId).orElseGet(() -> settings.save(new ShopSetting(shopId)));
	}

	/**
	 * Reads one section, falling back to its default.
	 *
	 * <p>Used on the hot paths -- checkout, the invoice generator, the mailer -- where a missing
	 * or unreadable section must degrade to the default rather than fail the customer's order.
	 * An owner who typed something the backend cannot parse should not be able to take their shop
	 * offline.
	 */
	@Transactional(readOnly = true)
	public <T> T section(UUID shopId, Class<T> type, T fallback, String column) {
		return settings.findByShopId(shopId)
				.map(row -> read(raw(row, column), type, fallback, shopId))
				.orElse(fallback);
	}

	/** The order-number format, for {@code OrderNumberService}. */
	@Transactional(readOnly = true)
	public OrderNumber orderNumber(UUID shopId) {
		return section(shopId, OrderNumber.class, OrderNumber.DEFAULT, "orderNumber");
	}

	public General general(UUID shopId) {
		return section(shopId, General.class, defaultGeneral(), "general");
	}

	public Address address(UUID shopId) {
		return section(shopId, Address.class, defaultAddress(), "address");
	}

	public Social social(UUID shopId) {
		return section(shopId, Social.class, defaultSocial(), "social");
	}

	public Checkout checkout(UUID shopId) {
		return section(shopId, Checkout.class, defaultCheckout(), "checkout");
	}

	public Notifications notifications(UUID shopId) {
		return section(shopId, Notifications.class, defaultNotifications(), "notifications");
	}

	public InvoiceTemplate invoiceTemplate(UUID shopId) {
		return section(shopId, InvoiceTemplate.class, defaultInvoiceTemplate(), "invoiceTemplate");
	}

	public EmailTemplates emailTemplates(UUID shopId) {
		return section(shopId, EmailTemplates.class, EmailTemplates.DEFAULT, "emailTemplates");
	}

	/** Every tab at once, for the settings screen's single GET. */
	@Transactional(readOnly = true)
	public SettingsView view(UUID shopId) {
		return settings.findByShopId(shopId)
				.map(row -> new SettingsView(
						read(raw(row, "general"), General.class, defaultGeneral(), shopId),
						read(raw(row, "address"), Address.class, defaultAddress(), shopId),
						read(raw(row, "social"), Social.class, defaultSocial(), shopId),
						read(raw(row, "checkout"), Checkout.class, defaultCheckout(), shopId),
						read(raw(row, "notifications"), Notifications.class, defaultNotifications(), shopId),
						read(raw(row, "orderNumber"), OrderNumber.class, OrderNumber.DEFAULT, shopId),
						read(raw(row, "invoiceTemplate"), InvoiceTemplate.class, defaultInvoiceTemplate(), shopId),
						read(raw(row, "emailTemplates"), EmailTemplates.class, EmailTemplates.DEFAULT, shopId)))
				.orElseGet(() -> new SettingsView(
						defaultGeneral(), defaultAddress(), defaultSocial(), defaultCheckout(),
						defaultNotifications(), OrderNumber.DEFAULT, defaultInvoiceTemplate(),
						EmailTemplates.DEFAULT));
	}

	/** Every settings tab, for one GET. */
	public record SettingsView(
			General general,
			Address address,
			Social social,
			Checkout checkout,
			Notifications notifications,
			OrderNumber orderNumber,
			InvoiceTemplate invoiceTemplate,
			EmailTemplates emailTemplates) {
	}

	// ------------------------------------------------------------------ write

	/**
	 * Writes one section and leaves the rest of the row untouched.
	 *
	 * <p>Scoped to a single column on purpose. Saving the Address tab must not be able to rewrite
	 * the Social tab from a form that never loaded it.
	 */
	@Transactional
	public <T> void writeSection(UUID shopId, Class<T> type, T value, String column) {
		ShopSetting row = load(shopId);
		writeRaw(row, column, write(value, type, shopId));
		settings.save(row);
	}

	/**
	 * Writes one raw column, bypassing the typed round trip.
	 *
	 * <p>This exists so a test can seed JSON the application would never produce — a field from a
	 * newer backend, or a corrupt string — and assert what the read path does with it. It is
	 * package-private because nothing in the application should call it; keeping it off the public
	 * surface is what makes that true rather than merely intended.
	 */
	void writeRawForTest(UUID shopId, String column, String json) {
		ShopSetting row = load(shopId);
		writeRaw(row, column, json);
		settings.save(row);
	}

	// ------------------------------------------------------------------ json plumbing

	/** Reads a column by its section name. */
	String raw(ShopSetting row, String column) {
		return switch (column) {
			case "general" -> row.getGeneral();
			case "address" -> row.getAddress();
			case "social" -> row.getSocial();
			case "checkout" -> row.getCheckout();
			case "notifications" -> row.getNotifications();
			case "orderNumber" -> row.getOrderNumber();
			case "invoiceTemplate" -> row.getInvoiceTemplate();
			case "emailTemplates" -> row.getEmailTemplates();
			default -> throw new IllegalArgumentException("Unknown settings section: " + column);
		};
	}

	/** Writes a column by its section name. The only place a settings column is ever assigned. */
	private void writeRaw(ShopSetting row, String column, String value) {
		switch (column) {
			case "general" -> row.setGeneral(value);
			case "address" -> row.setAddress(value);
			case "social" -> row.setSocial(value);
			case "checkout" -> row.setCheckout(value);
			case "notifications" -> row.setNotifications(value);
			case "orderNumber" -> row.setOrderNumber(value);
			case "invoiceTemplate" -> row.setInvoiceTemplate(value);
			case "emailTemplates" -> row.setEmailTemplates(value);
			// appearance_extra is never written from the UI (D15): custom CSS is stored but not
			// rendered, and there is deliberately no path that sets it.
			default -> throw new IllegalArgumentException("Unknown settings section: " + column);
		}
	}

	private <T> T read(String raw, Class<T> type, T fallback, UUID shopId) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			T parsed = json.readValue(raw, type);
			return parsed == null ? fallback : parsed;
		} catch (Exception ex) {
			// Logged, not thrown. A settings document the backend cannot parse must not be able to
			// stop a customer checking out; the owner sees the default and can re-enter the value.
			log.warn("Shop {} has unreadable settings JSON for {} ({}); using defaults",
					shopId, type.getSimpleName(), ex.getMessage());
			return fallback;
		}
	}

	private <T> String write(T value, Class<T> type, UUID shopId) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception ex) {
			throw new IllegalArgumentException("Could not serialise settings section " + type.getSimpleName());
		}
	}
}