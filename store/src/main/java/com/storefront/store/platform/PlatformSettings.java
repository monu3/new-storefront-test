package com.storefront.store.platform;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;
import com.storefront.store.security.CurrentUser;

import lombok.RequiredArgsConstructor;

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
 * Platform-wide configuration (Phase 7).
 *
 * <p><b>The commission rate stops being a constant.</b> It was
 * {@code CommissionCalculator.DEFAULT_RATE_BP = 500}, which meant the platform's single most
 * consequential number could not be changed by the people entitled to change it — not by an admin,
 * not by an operator, not at all without a rebuild and a deploy.
 *
 * <p><b>Seeded at the same value the constant had</b> (500bp), so moving it from code to data
 * changes no behaviour. That is what makes the change provably safe rather than probably safe.
 *
 * <p><b>Read through this service, never queried directly.</b> A caller that reads the table itself
 * has to know the column is jsonb and parse it, and every such caller parses it slightly differently.
 */
@Service
@RequiredArgsConstructor
public class PlatformSettings {

	/** The keys this platform knows. Closed, so a typo is a compile error. */
	public enum Key {
		/** Basis points. 500 = 5%. Read by every commission calculation. */
		COMMISSION_RATE_BP("commission_rate_bp"),
		/** The smallest top-up a shop may submit, in paisa. */
		MIN_TOPUP_MINOR("min_topup_minor"),
		/** ISO code. One country (D5), one currency. */
		PLATFORM_CURRENCY("platform_currency"),
		/** How many featured shops the gallery shows. */
		GALLERY_FEATURED_LIMIT("gallery_featured_limit"),
		/** Whether new accounts may register at all. An emergency brake. */
		REGISTRATION_OPEN("registration_open"),
		/**
		 * Whether an admin may skip manual approval of subscriptions.
		 *
		 * <p>Seeded false and expected to stay false. It exists so the flag is a decision rather than
		 * an assumption: the day somebody wants auto-approval, the switch is already understood, and
		 * turning it on is a deliberate act with a name attached.
		 */
		AUTO_APPROVE_SUBSCRIPTIONS("auto_approve_subscriptions");

		private final String key;

		Key(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}

	private final PlatformSettingRepository settings;
	private final CurrentUser currentUser;

	// ------------------------------------------------------------------ reads

	/**
	 * The commission rate, in basis points.
	 *
	 * <p>Falls back to {@code 500} if the row is somehow missing, because refusing to charge
	 * commission is the more expensive failure for the platform and the less harmful one for a shop.
	 */
	@Transactional(readOnly = true)
	public int commissionRateBp() {
		return intValue(Key.COMMISSION_RATE_BP, 500);
	}

	/**
	 * The rate as a percentage, for display.
	 *
	 * <p>Divided by 100 at the boundary rather than stored, because basis points are the storage
	 * format and a percentage is a presentation of it. Two places each dividing would produce two
	 * rates.
	 */
	@Transactional(readOnly = true)
	public double commissionPercent() {
		return commissionRateBp() / 100.0;
	}

	@Transactional(readOnly = true)
	public long minTopupMinor() {
		return longValue(Key.MIN_TOPUP_MINOR, 100_000L);
	}

	@Transactional(readOnly = true)
	public String currency() {
		return stringValue(Key.PLATFORM_CURRENCY, "NPR");
	}

	@Transactional(readOnly = true)
	public int galleryFeaturedLimit() {
		return intValue(Key.GALLERY_FEATURED_LIMIT, 12);
	}

	@Transactional(readOnly = true)
	public boolean registrationOpen() {
		return booleanValue(Key.REGISTRATION_OPEN, true);
	}

	@Transactional(readOnly = true)
	public boolean autoApproveSubscriptions() {
		return booleanValue(Key.AUTO_APPROVE_SUBSCRIPTIONS, false);
	}

	/**
	 * Every setting, for the admin screen.
	 *
	 * <p>Missing keys are filled with their default rather than omitted, so the admin sees the whole
	 * set and can tell "set to 500" from "not set".
	 */
	@Transactional(readOnly = true)
	public Map<Key, Object> all() {
		Map<Key, Object> every = new LinkedHashMap<>();
		for (Key key : Key.values()) {
			every.put(key, rawValue(key).orElse(defaultValue(key)));
		}
		return every;
	}

	// ------------------------------------------------------------------ writes

	/**
	 * Sets one setting, as an admin.
	 *
	 * @throws IllegalArgumentException when the value is not valid for that key. Validated here
	 *         rather than by casting at the read, because a commission rate of 50000bp stored as a
	 *         number is valid JSON and an invalid business — and the failure would otherwise appear
	 *         only when somebody tried to charge it.
	 */
	@Transactional
	public Setting set(Key key, Object value) {
		validate(key, value);

		UUID actor = currentUser.optionalUserId();
		Setting saved = settings.save(new Setting(
				key.key(), value, actor == null ? null : currentUser.require().getId()));

		// Deliberately not audited here. The auditing belongs to the caller that changes business
		// state; this is the mechanism, and a mechanism that logged itself would double every row.
		// AdminSettingsController records SETTING_UPDATE.
		return saved;
	}

	/** Refuses a value the business cannot use, naming the key and the rule. */
	private void validate(Key key, Object value) {
		switch (key) {
			case COMMISSION_RATE_BP -> {
				int rate = asInt(key, value);
				// Negative commission would pay the shop to sell. Over 100% would take more than the
				// order is worth, which is not a discount and produces negative order totals.
				if (rate < 0 || rate > 10_000) {
					throw new IllegalArgumentException(
							"Commission must be between 0 and 10000 basis points (0% to 100%), not "
									+ rate + ". Negative commission pays a shop to sell; over 100% takes"
									+ " more than the order is worth.");
				}
			}
			case MIN_TOPUP_MINOR -> {
				long minor = asLong(key, value);
				if (minor < 0) {
					throw new IllegalArgumentException(
							"The minimum top-up cannot be negative: " + minor);
				}
			}
			case GALLERY_FEATURED_LIMIT -> {
				int limit = asInt(key, value);
				if (limit < 0 || limit > 200) {
					throw new IllegalArgumentException(
							"The featured limit must be between 0 and 200, not " + limit + ".");
				}
			}
			case PLATFORM_CURRENCY -> {
				String currency = String.valueOf(value);
				if (!currency.matches("[A-Z]{3}")) {
					throw new IllegalArgumentException(
							"A currency is a three-letter ISO code such as NPR, not '" + currency + "'.");
				}
			}
			default -> {
				// Booleans need no range check.
			}
		}
	}

	// ------------------------------------------------------------------ reading values

	/** The raw stored value, as text — a jsonb scalar reads back as its JSON literal. */
	@Transactional(readOnly = true)
	/**
	 * The raw stored value for a key.
	 *
	 * <p>{@code getValue()}, not the field: returning the whole {@link Setting} here made every
	 * reader call {@code String.valueOf(...)} on an entity, get
	 * {@code com...PlatformSettings$Setting@134b2645}, fail to parse it as a number, and silently
	 * fall back to the default. Every read returned 500 and every write appeared to do nothing —
	 * which is the worst possible failure for a settings screen, because it looks like the feature
	 * simply is not taking effect.
	 */
	public Optional<Object> rawValue(Key key) {
		return settings.findById(key.key()).map(PlatformSettings.Setting::getValue);
	}

	private int intValue(Key key, int fallback) {
		Object value = rawValue(key).orElse(null);
		if (value == null) {
			return fallback;
		}
		try {
			// jsonb numbers come back as text ("500"), and jsonb booleans as "true".
			return Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private long longValue(Key key, long fallback) {
		Object value = rawValue(key).orElse(null);
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private String stringValue(Key key, String fallback) {
		Object value = rawValue(key).orElse(null);
		if (value == null) {
			return fallback;
		}
		String text = String.valueOf(value).trim();
		// jsonb strings come back quoted; unwrap rather than returning '"NPR"'.
		return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
				? text.substring(1, text.length() - 1)
				: text;
	}

	private boolean booleanValue(Key key, boolean fallback) {
		Object value = rawValue(key).orElse(null);
		if (value == null) {
			return fallback;
		}
		String text = String.valueOf(value).trim();
		return "true".equalsIgnoreCase(text);
	}

	private Object defaultValue(Key key) {
		return switch (key) {
			case COMMISSION_RATE_BP -> 500;
			case MIN_TOPUP_MINOR -> 100_000L;
			case PLATFORM_CURRENCY -> "NPR";
			case GALLERY_FEATURED_LIMIT -> 12;
			case REGISTRATION_OPEN, AUTO_APPROVE_SUBSCRIPTIONS -> Boolean.TRUE.equals(null)
					? Boolean.FALSE : Boolean.FALSE;
		};
	}

	private int asInt(Key key, Object value) {
		try {
			return value instanceof Number number
					? number.intValue()
					: Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException | NullPointerException bad) {
			throw new IllegalArgumentException(key.key() + " must be a number, not '" + value + "'.");
		}
	}

	private long asLong(Key key, Object value) {
		try {
			return value instanceof Number number
					? number.longValue()
					: Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException | NullPointerException bad) {
			throw new IllegalArgumentException(key.key() + " must be a number, not '" + value + "'.");
		}
	}

	// ------------------------------------------------------------------ the entity

	/**
	 * One platform setting.
	 *
	 * <p>{@code value} is a jsonb scalar and so is typed {@code String} — the only scalars this
	 * platform stores are numbers, booleans and short strings, and a jsonb number arrives as its text
	 * literal. A typed column per key is not an option because the keys are data.
	 */
	@Entity
	@Table(name = "platform_setting")
	@Getter
	@Setter(AccessLevel.PACKAGE)
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class Setting {

		@Id
		@Column(name = "key", nullable = false)
		private String key;

		/**
		 * @JdbcTypeCode(SqlTypes.JSON) is required: the column is jsonb and Hibernate cannot infer a
		 * JDBC type for a String, so schema validation fails at boot with "wrong column type
		 * encountered in column [value] ... expecting varchar(255)" -- a message that reads like a
		 * migration mistake rather than a missing annotation.
		 */
		@JdbcTypeCode(SqlTypes.JSON)
		@Column(name = "value", nullable = false)
		private String value;

		@Column(name = "updated_by")
		private UUID updatedBy;

		@Column(name = "updated_at", nullable = false)
		private Instant updatedAt = Instant.now();

		Setting(String key, Object value, UUID updatedBy) {
			this.key = key;
			this.value = serialise(value);
			this.updatedBy = updatedBy;
			this.updatedAt = Instant.now();
		}

		/** Stored as the jsonb literal it is, so jsonb booleans stay booleans and not the text "true". */
		private static String serialise(Object value) {
			if (value instanceof Boolean flag) {
				return flag ? "true" : "false";
			}
			if (value instanceof Number number) {
				return String.valueOf(number);
			}
			String text = String.valueOf(value);
			return text.startsWith("\"") ? text : "\"" + text + "\"";
		}
	}

	/** Kept out of the entity's own file so the settings service owns its table. */
	// NOTE: Spring Data does not scan nested repository interfaces unless
	// `considerNestedRepositories = true`, so this is declared top-level in
	// PlatformSettingRepository.java and referenced from here.

	/** The currency, for formatting a money amount the way this platform does. */
	@Transactional(readOnly = true)
	public String format(long minor) {
		return Money.ofMinor(minor).toDisplay();
	}
}