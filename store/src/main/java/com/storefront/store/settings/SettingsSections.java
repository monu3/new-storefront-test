package com.storefront.store.settings;

import java.time.Instant;
import java.util.Locale;

import com.storefront.store.common.Money;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The typed shape of every settings tab.
 *
 * <p><b>These records are the settings' schema.</b> Each column in {@link ShopSetting} is written
 * from one of them, and each is read back through one of them, so a tab can never drift from its
 * JSON shape: there is exactly one definition of what "Social" means.
 *
 * <p>Validation annotations are the point, not decoration. The owner is typing free text into a
 * form, and the settings they save are read by the storefront, the invoice generator and the
 * transactional emails. An unvalidated {@code supportEmail} becomes a {@code mailto:} that silently
 * does nothing; an unvalidated accent colour becomes an invalid CSS custom property on every
 * rendered page. Rejecting at the boundary is cheaper than debugging either.
 *
 * <p>Unknown fields are dropped rather than preserved. That is a deliberate choice: a settings blob
 * that accumulates whatever any past version of the UI once sent is a blob nobody can reason about.
 */
public final class SettingsSections {

	/** The {@code {#..}} counter token, used by both validation and rendering. */
	private static final java.util.regex.Pattern COUNTER_TOKEN = java.util.regex.Pattern.compile("\\{#+}");

	private SettingsSections() {
	}

	// ------------------------------------------------------------------ general

	/**
	 * Display identity, distinct from the shop's {@code name}.
	 *
	 * <p>{@code name} is the shop's identity in the database and appears on every order number,
	 * receipt and admin screen. {@code displayName} is what the storefront header shows, so a shop
	 * can trade under a different name than its legal one without renaming its own records.
	 */
	public record General(
			@Size(max = 120) String displayName,
			@Size(max = 200) String tagline,
			@Size(max = 2000) String about,
			@Email @Size(max = 200) String supportEmail,
			@Size(max = 40) String supportPhone,
			/** Always NPR in this build; the field exists so the storefront need not hardcode it. */
			@Pattern(regexp = "NPR", message = "only NPR is supported") String currency,
			/** IANA zone, e.g. {@code Asia/Kathmandu}. Used for order timestamps on receipts. */
			@Size(max = 60) String timezone) {

		public General {
			currency = currency == null || currency.isBlank() ? Money.NPR : currency;
			timezone = timezone == null || timezone.isBlank() ? "Asia/Kathmandu" : timezone;
		}

		public String effectiveDisplayName(String shopName) {
			return displayName == null || displayName.isBlank() ? shopName : displayName;
		}
	}

	// ------------------------------------------------------------------ address

	/**
	 * The shop's own address.
	 *
	 * <p>Used on invoices and as the delivery origin. Kept separate from a customer's shipping
	 * address, which is a frozen snapshot on the order and is never rewritten when this changes.
	 */
	public record Address(
			@Size(max = 200) String line1,
			@Size(max = 200) String line2,
			@Size(max = 100) String city,
			@Size(max = 100) String state,
			@Size(max = 20) String postalCode,
			@Size(max = 100) String country,
			/** Free-text because delivery notes are written for humans, not routed by machine. */
			@Size(max = 500) String deliveryInstructions) {

		/** One line for the invoice footer. Empty rather than null, so no renderer needs a null check. */
		public String oneLine() {
			// Stream.of, not List.of: every field here is nullable and List.of rejects nulls
			// before the filter below ever runs.
			return java.util.stream.Stream.of(line1, line2, city, state, postalCode, country)
					.filter(part -> part != null && !part.isBlank())
					.reduce((a, b) -> a + ", " + b)
					.orElse("");
		}

		public boolean isComplete() {
			return line1 != null && !line1.isBlank()
					&& city != null && !city.isBlank()
					&& country != null && !country.isBlank();
		}
	}

	// ------------------------------------------------------------------ social

	/** External profile links. Any that are blank are simply not rendered. */
	public record Social(
			@Size(max = 300) String facebookUrl,
			@Size(max = 300) String instagramUrl,
			@Size(max = 300) String whatsappUrl,
			@Size(max = 300) String youtubeUrl,
			@Size(max = 300) String tiktokUrl,
			@Size(max = 300) String websiteUrl) {

		public static Social defaults() {
			return new Social(null, null, null, null, null, null);
		}
	}

	// ------------------------------------------------------------------ checkout

	/**
	 * How the basket may be paid for and fulfilled.
	 *
	 * <p>{@code acceptOrders} is the shop's front door. With it off the storefront shows the shop as
	 * closed and checkout refuses — which is the behaviour a shop wants at 11pm, and far better
	 * than accepting orders nobody will fulfil.
	 */
	public record Checkout(
			boolean acceptOrders,
			/** Minimum basket in minor units; 0 means no minimum. */
			@Min(0) Long minOrderMinor,
			/** Accept orders without the owner pressing Accept, when stock allows. */
			boolean autoAcceptOrders,
			/** Show an estimated delivery window on the product page. */
			boolean showDeliveryEstimate,
			/** Allow ordering without an account. */
			boolean guestCheckoutAllowed,
			@Size(max = 1000) String checkoutNote) {
	}

	// ------------------------------------------------------------------ notifications

	/**
	 * Which events the owner hears about.
	 *
	 * <p>{@code lowStockThreshold} is per variant, not per shop: a shop that sells both a
	 * rupee-per-gram spice and a 40kg rice sack needs different numbers, and one global threshold
	 * would either spam them about the spice or hide the rice running out.
	 */
	public record Notifications(
			boolean newOrder,
			boolean lowStock,
			@Min(0) @Max(100000) Integer lowStockThreshold,
			/** Deliver to {@code supportEmail} rather than only to the in-app list. */
			boolean emailDigest) {

		public Notifications {
			lowStockThreshold = lowStockThreshold == null ? 5 : lowStockThreshold;
		}
	}

	// ------------------------------------------------------------------ order number

	/**
	 * The order-number format and its counter.
	 *
	 * <p>Tokens: {@code {YYYY}} four-digit year, {@code {YY}} two-digit, {@code {MM}} month,
	 * {@code {DD}} day, and {@code {#…}} a zero-padded counter whose width is the number of
	 * {@code #} characters. Anything else is rejected rather than passed through, because a typo
	 * in a format string produces order numbers that look fine and collide.
	 *
	 * <p><b>The pattern allows any number of tokens, and requires at least one counter.</b> An
	 * earlier version permitted exactly one token, which rejected the perfectly reasonable
	 * {@code ORD-{YYYY}-{####}}. Requiring {@code {#..}} is the load-bearing half: a format with no
	 * counter renders the same string for every order, and the shop's uniqueness constraint then
	 * rejects the second one at checkout.
	 *
	 * <p><b>Changing the format does not renumber existing orders.</b> A number is an identifier
	 * that appears on printed invoices and in customer messages; renumbering them would break every
	 * reference to them. New orders get the new format from the next allocation onward.
	 */
	public record OrderNumber(
			@NotBlank @Size(max = 40) @Pattern(
					regexp = "^(?:[^{}]|\\{YYYY\\}|\\{YY\\}|\\{MM\\}|\\{DD\\}|\\{#{1,8}\\})*\\{#{1,8}\\}"
							+ "(?:[^{}]|\\{YYYY\\}|\\{YY\\}|\\{MM\\}|\\{DD\\}|\\{#{1,8}\\})*$",
					message = "use {YYYY}, {YY}, {MM}, {DD} and {#..} only, and include at least one {#..}")
			String format,
			@Min(1) Long next) {

		/**
		 * Fills in a missing format and counter.
		 *
		 * <p>Not cosmetic. A settings row is created lazily with {@code order_number = '{}'}, so an
		 * owner who opens the settings screen and saves <em>any</em> tab leaves a row whose order
		 * number deserialises to a null format — and every checkout then throws a
		 * {@code NullPointerException} while rendering the next order number. Defaulting here
		 * means an empty section is indistinguishable from an absent one, which is the invariant
		 * the whole read path assumes.
		 */
		public OrderNumber {
			format = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
			next = next == null || next < 1 ? 1L : next;
		}

		/** The format a shop gets before it chooses one. */
		public static final String DEFAULT_FORMAT = "ORD-{####}";

		public static final OrderNumber DEFAULT = new OrderNumber(DEFAULT_FORMAT, 1L);

		/** The literal text this format produces for the given date and counter value. */
		public String render(long counter, Instant at) {
			java.time.ZoneId zone;
			try {
				zone = java.time.ZoneId.of("Asia/Kathmandu");
			} catch (java.time.DateTimeException ex) {
				zone = java.time.ZoneOffset.UTC;
			}
			java.time.LocalDate date = at.atZone(zone).toLocalDate();

			// `replaceAll` treats `$` and `\` in the replacement specially, so the counter is
			// substituted last and is always digits -- nothing it could contain is significant.
			return format
					.replace("{YYYY}", String.format("%04d", date.getYear()))
					.replace("{YY}", String.format("%02d", date.getYear() % 100))
					.replace("{MM}", String.format("%02d", date.getMonthValue()))
					.replace("{DD}", String.format("%02d", date.getDayOfMonth()))
					.replaceAll("\\{#+}", pad(counter));
		}

		/**
		 * Zero-pads the counter to the width the format's {@code {#..}} token asks for, so
		 * {@code ORD-{###}} renders 42 as 042 and {@code ORD-{#####}} renders it as 00042.
		 *
		 * <p>Falls back to four digits for a format with no counter token at all. Such a format
		 * would produce the same string for every order, which the uniqueness constraint then
		 * rejects — but padding to a sane width is still better than an empty substitution.
		 */
		private String pad(long counter) {
			java.util.regex.Matcher m = COUNTER_TOKEN.matcher(format);
			int width = m.find() ? m.group().length() - 2 : 4;
			return String.format(Locale.ROOT, "%0" + Math.max(width, 1) + "d", counter);
		}
	}

	// ------------------------------------------------------------------ invoice

	/** Invoice PDF layout. */
	public record InvoiceTemplate(
			boolean showLogo,
			boolean showSignatureLine,
			boolean showTaxBreakdown,
			boolean showPaymentMethod,
			@Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "a six-digit hex colour, e.g. #1f2937")
			String accentColor,
			@Size(max = 300) String footerNote,
			@Size(max = 120) String businessTaxId) {

		public InvoiceTemplate {
			accentColor = accentColor == null || accentColor.isBlank() ? "#1f2937" : accentColor;
		}
	}

	// ------------------------------------------------------------------ email templates

	/**
	 * Owner-editable copy for the transactional emails.
	 *
	 * <p>Placeholders are {@code {name}}, {@code {orderNumber}}, {@code {total}} and {@code {shopName}}.
	 * They are substituted by the mailer, not evaluated — the owner's text is never executed, and
	 * an unknown {@code {...}} token is left visible rather than blanked, so a typo in the editor is
	 * obvious in the preview instead of silently eating a customer's name.
	 */
	public record EmailTemplates(
			@Size(max = 200) String orderConfirmationSubject,
			@Size(max = 4000) String orderConfirmationBody,
			@Size(max = 200) String orderShippedSubject,
			@Size(max = 4000) String orderShippedBody,
			@Size(max = 200) String lowStockSubject,
			@Size(max = 4000) String lowStockBody) {

		public static final EmailTemplates DEFAULT = new EmailTemplates(
				"Your order {orderNumber} is confirmed",
				"Hello {name},\n\nWe have received your order {orderNumber} for {total}.\n\n{shopName}",
				"Your order {orderNumber} is on its way",
				"Hello {name},\n\nOrder {orderNumber} has shipped.\n\n{shopName}",
				"Low stock alert from {shopName}",
				"{shopName}\n\nThe following products are running low:\n\n{body}");
	}
}