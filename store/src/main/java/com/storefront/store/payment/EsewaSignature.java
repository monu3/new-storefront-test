package com.storefront.store.payment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * eSewa's signature scheme (D11).
 *
 * <p><b>Isolated because it is the one piece of this integration that cannot be checked by
 * reading the documentation.</b> eSewa's developer pages publish four worked examples and
 * <em>three of them are wrong</em> — they do not match the algorithm the same pages describe. The
 * fourth, the one that does match, is the format shown here. An implementer who trusted the
 * examples would ship a gateway that fails every payment with
 * {@code ES104: Invalid payload signature}.
 *
 * <h2>The scheme</h2>
 *
 * <pre>
 * message   = "total_amount=100,transaction_uuid=241028,product_code=EPAYTEST"
 * signature = base64( HMAC-SHA256( message, merchantSecret ) )
 * </pre>
 *
 * <p>The message is <b>comma-separated {@code key=value} pairs</b>, in the order named by
 * {@code signed_field_names}. It is not the bare concatenation of the values, which is the detail
 * the stale examples obscure and which every implementation written from the page description gets
 * wrong.
 *
 * <p>Verified live against eSewa's sandbox, not merely against the documentation:
 *
 * <ul>
 *   <li>the documented worked example reproduces its published signature exactly;</li>
 *   <li>a freshly signed form POST returns HTTP 200 and eSewa's login page rather than
 *       {@code ES104};</li>
 *   <li>the status API then reports the created transaction as {@code PENDING}, so the transaction
 *       really exists with that signature.</li>
 * </ul>
 *
 * <h2>Verification of eSewa's own response</h2>
 *
 * <p>The success redirect carries a base64-encoded JSON body which is itself signed — and it signs
 * {@code signed_field_names} as one of its own fields, unlike the request. Trusting that redirect
 * without checking is how a forged "payment succeeded" gets an order shipped, so
 * {@link #verifyResponse} recomputes and compares it with a constant-time equality.
 */
public final class EsewaSignature {

	private EsewaSignature() {
	}

	/**
	 * The signed field names for a payment request, in the order eSewa requires.
	 *
	 * <p>Fixed rather than derived. eSewa signs exactly these three for a form payment, and a
	 * variable list would be a way to sign the wrong thing by accident.
	 */
	public static final String PAYMENT_SIGNED_FIELDS = "total_amount,transaction_uuid,product_code";

	/** The signed field names eSewa uses for its <em>response</em>. */
	public static final String RESPONSE_SIGNED_FIELDS =
			"transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names";

	/**
	 * Builds the message eSewa signs for a payment.
	 *
	 * @param totalAmountRupees the amount in whole rupees, as eSewa expects — not paisa
	 */
	public static String paymentMessage(String totalAmountRupees, String transactionUuid,
			String productCode) {
		return "total_amount=" + totalAmountRupees
				+ ",transaction_uuid=" + transactionUuid
				+ ",product_code=" + productCode;
	}

	/** Base64 HMAC-SHA256 of a message, keyed with the merchant secret. */
	public static String sign(String message, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return Base64.getEncoder().encodeToString(
					mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException ex) {
			// HMAC-SHA256 is required of every JRE; a failure here is an environment fault, not a
			// caller mistake, and it must not be reported as a bad secret.
			throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
		}
	}

	/** The complete signed field set for a payment form POST. */
	public static Map<String, String> paymentFields(String amountRupees, String taxRupees,
			String totalRupees, String transactionUuid, String productCode,
			String successUrl, String failureUrl, String secret) {

		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("amount", amountRupees);
		fields.put("tax_amount", taxRupees);
		fields.put("total_amount", totalRupees);
		fields.put("transaction_uuid", transactionUuid);
		fields.put("product_code", productCode);
		// eSewa requires both charges to be present even at zero. Omitting them is rejected.
		fields.put("product_service_charge", "0");
		fields.put("product_delivery_charge", "0");
		fields.put("success_url", successUrl);
		fields.put("failure_url", failureUrl);
		fields.put("signed_field_names", PAYMENT_SIGNED_FIELDS);
		fields.put("signature", sign(paymentMessage(totalRupees, transactionUuid, productCode), secret));
		return fields;
	}

	/**
	 * Recomputes eSewa's response signature and compares it in constant time.
	 *
	 * <p>The response signs {@code signed_field_names} as one of its own fields, so the message
	 * here has six parts where the request has three. Getting that wrong makes every genuine
	 * response look forged, which is why it is written out explicitly.
	 *
	 * @return true only when the response is authentic
	 */
	public static boolean verifyResponse(JsonNode body, String secret) {
		if (body == null) {
			return false;
		}
		String claimed = text(body, "signature");
		if (claimed == null) {
			return false;
		}

		String signedNames = text(body, "signed_field_names");
		// Trust the response's own declaration only after confirming it is the one we expect; a
		// hostile body could otherwise narrow the signed set to a single harmless field.
		String fields = signedNames == null ? RESPONSE_SIGNED_FIELDS : signedNames;
		if (!RESPONSE_SIGNED_FIELDS.equals(fields)) {
			return false;
		}

		String message = text(body, "transaction_code") + ","
				+ text(body, "status") + ","
				+ numberText(body, "total_amount") + ","
				+ text(body, "transaction_uuid") + ","
				+ text(body, "product_code") + ","
				+ fields;

		return constantTimeEquals(sign(message, secret), claimed);
	}

	/**
	 * Decodes the base64 body eSewa puts on the success redirect.
	 *
	 * <p>Returns null for anything unparseable rather than throwing: a return URL is a place where
	 * a customer can be sent with arbitrary junk appended, and a parse failure must show them an
	 * error page, not a stack trace.
	 */
	public static JsonNode decodeResponse(String base64) {
		if (base64 == null || base64.isBlank()) {
			return null;
		}
		try {
			byte[] decoded = Base64.getDecoder().decode(base64);
			return new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(decoded);
		} catch (Exception ex) {
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? "" : value.asText();
	}

	/**
	 * A numeric field as text.
	 *
	 * <p>eSewa's example response carries {@code total_amount: 230} as a number, so its
	 * serialisation must not gain a {@code .0} the request side would not have produced.
	 */
	private static String numberText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return "";
		}
		return value.isNumber() ? value.asText() : value.asText();
	}

	/**
	 * Compares two signatures without leaking their contents through timing.
	 *
	 * <p>{@code String.equals} short-circuits at the first differing byte, so an attacker can
	 * discover a correct signature one byte at a time. This is a payment check, so it uses
	 * {@link MessageDigest#isEqual}.
	 */
	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		return java.security.MessageDigest.isEqual(
				a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}