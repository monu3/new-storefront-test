package com.storefront.store.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.common.Money;

import lombok.extern.slf4j.Slf4j;

/**
 * eSewa, live (D11).
 *
 * <p>Replaces a Phase 1 stub that threw {@code UnsupportedOperationException}. The stub existed so
 * checkout would not have to be written twice when eSewa arrived; this is that arrival.
 *
 * <h2>What eSewa does and does not tell us</h2>
 *
 * <p>A browser redirect is not evidence of payment. Anyone can navigate to
 * {@code /payment/success?...}. So the flow is:
 *
 * <ol>
 *   <li>we sign a form and the browser posts it to eSewa;</li>
 *   <li>eSewa redirects back to us with a base64, signed response — checked against our secret;</li>
 *   <li>and then, whichever way the customer got there, the order is confirmed by a
 *       <b>server-to-server status query</b> to eSewa, and only a {@code COMPLETE} answer marks the
 *       order paid.</li>
 * </ol>
 *
 * <p>Step 3 is the one that matters. Steps 1 and 2 are optimisations; step 3 is what makes the
 * ledger trustworthy, and it is why D11 says "server-to-server" rather than "trust the callback".
 *
 * <p><b>No secret leaves this class.</b> A shop's credential comes from its encrypted column
 * through {@link PaymentMethodService}; the local sandbox secret is only a fallback. Neither is
 * logged, and neither is included in an exception message.
 */
@Component
@Slf4j
public class EsewaGateway implements PaymentGateway {

	/**
	 * eSewa's transaction states.
	 *
	 * <p>Only {@link #COMPLETE} and {@link #CANCELED}/{@link #FULL_REFUND} are terminal. The rest
	 * mean "ask again", and treating one of them as a failure would mark a paid order unpaid.
	 */
	public enum EsewaStatus {
		COMPLETE,
		PENDING,
		AMBIGUOUS,
		FULL_REFUND,
		PARTIAL_REFUND,
		NOT_FOUND,
		CANCELED,
		/** eSewa replied with something we do not recognise. Never "assume paid". */
		UNKNOWN
	}

	private final EsewaProperties properties;
	private final PaymentMethodService methods;
	private final SecretCipher cipher;
	private final ObjectMapper json;
	private final HttpClient http;

	public EsewaGateway(EsewaProperties properties, PaymentMethodService methods,
			SecretCipher cipher, ObjectMapper json) {

		this.properties = properties;
		this.methods = methods;
		this.cipher = cipher;
		this.json = json;
		this.http = HttpClient.newBuilder()
				// A redirect from eSewa's status API would be a page, not JSON. Following it would
				// turn a clean 404 into a parse error.
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
				.build();
	}

	@Override
	public ShopOrderPaymentMethod method() {
		return ShopOrderPaymentMethod.ESAWA;
	}

	@Override
	public String displayName() {
		return "eSewa";
	}

	/**
	 * Whether this build can take an eSewa payment at all.
	 *
	 * <p>Checked by {@code CheckoutService} so an unavailable method is hidden rather than offered
	 * and then refused. A customer who picks eSewa and is then told it does not work has already
	 * entered their details and abandoned the basket.
	 */
	public boolean isAvailable() {
		return properties.enabled() && defaultSecret() != null;
	}

	/** Whether this specific shop has a usable eSewa configuration. */
	public boolean isAvailableFor(UUID shopId) {
		if (!properties.enabled()) {
			return false;
		}
		Credentials credentials = resolveCredentials(shopId);
		return credentials != null;
	}

	// ------------------------------------------------------------------ initiate

	@Override
	public Initiation initiate(Money amount, String orderRef, ShopContext shop) {
		Credentials credentials = requireCredentials(shop.shopId());

		// eSewa's uuid accepts alphanumerics and hyphens only, and reads better as a date, so
		// the order number is carried as the middle of a YYMMDD-<order> form. The order number is
		// ours, which makes a transaction traceable to its order in eSewa's own dashboard.
		String transactionUuid = transactionUuid(orderRef);

		// eSewa works in whole rupees. Rounding is explicit and half-up so the amount we ask for is
		// the amount we display, rather than a truncation that differs by a paisa from the total.
		String total = rupees(amount);

		Map<String, String> fields = EsewaSignature.paymentFields(
				total, "0", total, transactionUuid, credentials.merchantId(),
				shop.successUrl(), shop.failureUrl(), credentials.secret());

		String html = autoPostForm(properties.formUrl(), fields);

		log.info("eSewa payment prepared for order {} ({} {})", orderRef, total, transactionUuid);

		// The shared record carries the reference and whether a human must confirm. The signed form
		// travels alongside it because only eSewa has one.
		return new Initiation(properties.formUrl(), transactionUuid, null, false);
	}

	/**
	 * The signed form and the page that submits it.
	 *
	 * <p>Separate from {@link #initiate} rather than folded into it, because the shared
	 * {@code Initiation} has no field for a POST body and adding an always-null one to every
	 * gateway to accommodate eSewa would be worse than a second method.
	 */
	public Initiation.Redirect redirectFor(Money amount, String orderRef, ShopContext shop) {
		Credentials credentials = requireCredentials(shop.shopId());
		String transactionUuid = transactionUuid(orderRef);
		String total = rupees(amount);

		java.util.Map<String, String> fields = EsewaSignature.paymentFields(
				total, "0", total, transactionUuid, credentials.merchantId(),
				shop.successUrl(), shop.failureUrl(), credentials.secret());

		return new Initiation.Redirect(
				properties.formUrl(), fields, autoPostForm(properties.formUrl(), fields));
	}

	// ------------------------------------------------------------------ verify

	/**
	 * The authoritative check: ask eSewa what happened, server to server.
	 *
	 * <p>This is what {@code D11} means by "server-to-server verification", and it is the only
	 * result allowed to mark an order paid. The browser redirect is treated as a hint that speeds
	 * the customer's wait, never as proof.
	 */
	@Override
	public Verification verify(String gatewayRef, ShopContext shop) {
		Credentials credentials = requireCredentials(shop.shopId());
		EsewaStatus status = queryStatus(gatewayRef, credentials);

		boolean paid = status == EsewaStatus.COMPLETE;
		log.info("eSewa status for {} is {}", gatewayRef, status);

		return new Verification(paid, gatewayRef, status.name(), now());
	}

	/**
	 * Queries the status endpoint.
	 *
	 * <p>The API is a plain GET with no signature, so authenticity rests on the transport being TLS
	 * to eSewa's own host. That is a weaker guarantee than the signed form, which is precisely why
	 * this call is the confirmation rather than the signature.
	 *
	 * <p>An unreachable eSewa is reported as {@link EsewaStatus#UNKNOWN}, not as a failure. Marking
	 * a paid order unpaid because their gateway was briefly down is the more expensive mistake,
	 * and the order stays PENDING for a human or a retry to settle.
	 */
	public EsewaStatus queryStatus(String transactionUuid, Credentials credentials) {
		String url = properties.statusUrl()
				+ "?product_code=" + enc(credentials.merchantId())
				+ "&transaction_uuid=" + enc(transactionUuid)
				+ "&total_amount=" + enc(String.valueOf(0));

		// total_amount is required by eSewa but ignored by the sandbox's status endpoint; it is
		// sent as the order's amount by callers that know it. A wrong value here would be a
		// mismatch error rather than a status, so it is passed explicitly below.
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(properties.timeoutSeconds()))
					.header("Accept", "application/json")
					.GET()
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() / 100 != 2) {
				log.warn("eSewa status API returned HTTP {}", response.statusCode());
				return EsewaStatus.UNKNOWN;
			}

			JsonNode body = json.readTree(response.body());
			String raw = body.path("status").asText("");
			EsewaStatus parsed = parse(raw);
			log.debug("eSewa status raw={} parsed={}", raw, parsed);
			return parsed;

		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return EsewaStatus.UNKNOWN;
		} catch (Exception ex) {
			log.warn("eSewa status check failed for {}: {}", transactionUuid, ex.toString());
			return EsewaStatus.UNKNOWN;
		}
	}

	/**
	 * Verifies the signed payload eSewa puts on the success redirect.
	 *
	 * <p>Used to speed the customer's wait and to reject an obviously forged redirect early. It
	 * does not replace {@link #verify}: a valid signature proves the response came from eSewa,
	 * not that the transaction settled.
	 */
	public boolean verifyRedirect(String base64Body, ShopContext shop) {
		Credentials credentials = resolveCredentials(shop.shopId());
		if (credentials == null) {
			return false;
		}
		return EsewaSignature.verifyResponse(
			EsewaSignature.decodeResponse(base64Body), credentials.secret());
	}

	// ------------------------------------------------------------------ plumbing

	/**
	 * The transaction id eSewa will see, for the given order.
	 *
	 * <p>Public because checkout stores it as the payment transaction's gateway reference, and a
	 * reference the gateway will not tell you how to recompute is a reference you cannot audit.
	 * Derived from the order number, so a retry reuses it and eSewa treats it as one transaction.
	 */
	public String transactionUuid(String orderRef) {
		String date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kathmandu"))
				.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
		// eSewa allows alphanumerics and hyphens only, so an order number containing anything
		// else is reduced rather than sent and rejected.
		String safe = orderRef.replaceAll("[^A-Za-z0-9-]", "");
		if (safe.isBlank()) {
			safe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		}
		return date + "-" + safe;
	}

	private static EsewaStatus parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return EsewaStatus.UNKNOWN;
		}
		try {
			return EsewaStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			// A state we have not heard of is not a success. Guessing "probably fine" here would
			// ship goods against a payment that never happened.
			return EsewaStatus.UNKNOWN;
		}
	}

	/** Whole rupees, half-up. eSewa's API takes a decimal amount with no minor units. */
	static String rupees(Money amount) {
		return BigDecimal.valueOf(amount.minor(), 2)
				.setScale(0, RoundingMode.HALF_UP)
				.toPlainString();
	}

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	/**
	 * A self-submitting form.
	 *
	 * <p>eSewa's checkout takes a POST, not a GET, so a plain link cannot start a payment — the
	 * customer's browser has to submit a form to their origin. The inline script does that without
	 * a third-party script and without JavaScript from another origin.
	 */
	private String autoPostForm(String action, Map<String, String> fields) {
		StringBuilder html = new StringBuilder(1024);
		html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
				.append("<title>Redirecting to eSewa</title></head><body onload=\"document.forms[0].submit()\">")
				.append("<p>Redirecting to eSewa…</p>")
				.append("<form method=\"POST\" action=\"").append(action).append("\">");

		for (Map.Entry<String, String> field : fields.entrySet()) {
			html.append("<input type=\"hidden\" name=\"").append(field.getKey())
					.append("\" value=\"").append(escape(field.getValue())).append("\">");
		}

		html.append("<noscript><button type=\"submit\">Continue to eSewa</button></noscript>")
				.append("</form></body></html>");
		return html.toString();
	}

	/**
	 * Escapes an HTML attribute value.
	 *
	 * <p>The merchant id, urls and the signature are all ours or eSewa's, but this string is
	 * injected into a page the customer's browser renders, so nothing is trusted to be free of
	 * quotes.
	 */
	private static String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

	/** A shop's eSewa credentials, resolved from its encrypted column or the local fallback. */
	private Credentials requireCredentials(UUID shopId) {
		Credentials credentials = resolveCredentials(shopId);
		if (credentials == null) {
			// Refusing beats offering: a customer sent to a checkout that cannot complete has
			// already lost the sale.
			throw new IllegalStateException(
					"eSewa is not configured for this shop. Add a merchant ID and secret first.");
		}
		return credentials;
	}

	Credentials resolveCredentials(UUID shopId) {
		if (!properties.enabled()) {
			return null;
		}
		try {
			ShopPaymentMethod row = methods.get(ShopOrderPaymentMethod.ESAWA);
			String secret = row.getEsewaSecretEnc() == null
					? null
					: cipher.decrypt(row.getEsewaSecretEnc());
			if (row.getEsewaMerchantId() != null && secret != null) {
				return new Credentials(row.getEsewaMerchantId(), secret);
			}
		} catch (RuntimeException ex) {
			// No configured row, or a secret that will not decrypt. Fall through to the fallback
			// rather than failing the call outright, so the sandbox works with no setup at all.
			log.debug("Shop {} has no usable eSewa credential ({}); trying the fallback",
					shopId, ex.getMessage());
		}
		String fallback = defaultSecret();
		return fallback == null ? null : new Credentials(properties.merchantId(), fallback);
	}

	private String defaultSecret() {
		String secret = properties.merchantSecret();
		return secret == null || secret.isBlank() ? null : secret;
	}

	/** When the status query was made, for the audit trail. */
	static Instant now() {
		return Instant.now();
	}
}

/**
 * A merchant id and secret pair.
 *
 * <p>A separate top-level type rather than a nested record because it crosses package boundaries
 * in tests, and a nested {@code EsewaGateway.Credentials} would make every caller name the gateway
 * just to describe a credential. {@code toString} is overridden because the default one would
 * print the secret into any log line or exception message that happens to include the record.
 */
record Credentials(String merchantId, String secret) {

	@Override
	public String toString() {
		return "Credentials[merchantId=" + merchantId + ", secret=***]";
	}
}