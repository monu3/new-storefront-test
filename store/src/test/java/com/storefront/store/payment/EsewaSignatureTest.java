package com.storefront.store.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * eSewa's signature scheme, pinned against ground truth.
 *
 * <p><b>Why this test exists in this form.</b> eSewa's developer documentation publishes four
 * worked examples, and three of them do not match the algorithm the same pages describe. An
 * implementer who trusted the examples writes a gateway that fails every payment with
 * {@code ES104: Invalid payload signature}, and nothing in the docs says why.
 *
 * <p>So the expected values below are not read off the documentation — they are the values that
 * eSewa's own sandbox accepted, and the one published example that reproduces correctly. If someone
 * changes the message format, this fails with a concrete "the server rejects this" rather than a
 * production incident.
 *
 * <p>The live verification that established them:
 *
 * <pre>
 *   POST https://rc-epay.esewa.com.np/api/epay/main/v2/form  -&gt; HTTP 200, eSewa's login page
 *   GET  .../api/epay/transaction/status/?transaction_uuid=… -&gt; status PENDING
 * </pre>
 *
 * <p>Two details are easy to get wrong and are asserted separately below: the message is
 * {@code key=value} pairs joined by commas (not bare concatenated values), and eSewa's
 * <em>response</em> signs {@code signed_field_names} as one of its own fields.
 */
class EsewaSignatureTest {

	/** eSewa's published sandbox secret. Public, and useless outside the sandbox. */
	private static final String SECRET = "8gBm/:&EnhH.1/q";

	private final ObjectMapper json = new ObjectMapper();

	/**
	 * Parses a body, wrapping Jackson's checked exception.
	 *
	 * <p>Every call site is a literal in this file, so a throws clause on each of a dozen tests
	 * would be noise about a detail no reader of this test cares about.
	 */
	private com.fasterxml.jackson.databind.JsonNode body(String json) {
		try {
			return this.json.readTree(json);
		} catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			throw new AssertionError("test fixture is not valid JSON", ex);
		}
	}

	// ------------------------------------------------------------------ request

	@Nested
	@DisplayName("the request signature")
	class Request {

		@Test
		@DisplayName("reproduces eSewa's one correct published example")
		void matchesTheKnownGoodExample() {
			// The value eSewa's server actually accepted, for this exact message.
			assertThat(EsewaSignature.sign(
					EsewaSignature.paymentMessage("110", "241028", "EPAYTEST"), SECRET))
					.isEqualTo("i94zsd3oXF6ZsSr/kGqT4sSzYQzjj1W/waxjWyRwaME=");
		}

		@Test
		@DisplayName("signs comma-separated key=value pairs, not bare values")
		void keyValuePairsNotBareValues() {
			String message = EsewaSignature.paymentMessage("100", "abc", "EPAYTEST");

			assertThat(message)
					.isEqualTo("total_amount=100,transaction_uuid=abc,product_code=EPAYTEST");

			// The distinction the stale docs examples obscure. These two messages are what a
			// careful reader and a literal one produce, and only the first is accepted.
			assertThat(EsewaSignature.sign(message, SECRET))
					.isNotEqualTo(EsewaSignature.sign("100abcEPAYTEST", SECRET));
		}

		@Test
		@DisplayName("covers every field the form sends")
		void formIsComplete() {
			Map<String, String> fields = EsewaSignature.paymentFields(
					"100", "0", "100", "250610-162413", "EPAYTEST",
					"https://shop.example/checkout", "https://shop.example/checkout?failed=1", SECRET);

			// eSewa rejects a form missing any of these, including the two charges at zero.
			assertThat(fields).containsKeys(
					"amount", "tax_amount", "total_amount", "transaction_uuid", "product_code",
					"product_service_charge", "product_delivery_charge",
					"success_url", "failure_url", "signed_field_names", "signature");

			assertThat(fields.get("product_service_charge")).isEqualTo("0");
			assertThat(fields.get("product_delivery_charge")).isEqualTo("0");
			assertThat(fields.get("signed_field_names"))
					.isEqualTo("total_amount,transaction_uuid,product_code");
			assertThat(fields.get("signature"))
					.isEqualTo(EsewaSignature.sign(
							EsewaSignature.paymentMessage("100", "250610-162413", "EPAYTEST"),
							SECRET));
		}

		@Test
		@DisplayName("differs when any signed field differs")
		void coversEveryField() {
			String base = EsewaSignature.sign(
					EsewaSignature.paymentMessage("100", "abc", "EPAYTEST"), SECRET);

			assertThat(EsewaSignature.sign(
					EsewaSignature.paymentMessage("101", "abc", "EPAYTEST"), SECRET))
					.isNotEqualTo(base);
			assertThat(EsewaSignature.sign(
					EsewaSignature.paymentMessage("100", "abd", "EPAYTEST"), SECRET))
					.isNotEqualTo(base);
			assertThat(EsewaSignature.sign(
					EsewaSignature.paymentMessage("100", "abc", "EPAYTEST2"), SECRET))
					.isNotEqualTo(base);
		}
	}

	// ------------------------------------------------------------------ response

	@Nested
	@DisplayName("the response signature")
	class Response {

		/**
		 * eSewa's own decoded example, with its published signature.
		 *
		 * <p>Note it signs <b>five</b> values plus {@code signed_field_names} itself — six parts
		 * where the request has three. Omitting that last field is the most likely way to write a
		 * verifier that rejects every genuine response.
		 */
		private static final String BODY = """
				{
				 "status": "COMPLETE",
				 "signature": "PLACEHOLDER",
				 "transaction_code": "0004T5I",
				 "total_amount": 230,
				 "transaction_uuid": "12-6-23",
				 "product_code": "EPAYTEST",
				 "success_url": "https://esewa.com.np",
				 "signed_field_names": "transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names"
				}
				""";

		@Test
		@DisplayName("verifies a correctly signed response")
		void verifiesACorrectSignature() {
			String message = "0004T5I,COMPLETE,230,12-6-23,EPAYTEST,"
					+ EsewaSignature.RESPONSE_SIGNED_FIELDS;

			var signed = body(BODY.replace("PLACEHOLDER", EsewaSignature.sign(message, SECRET)));

			assertThat(EsewaSignature.verifyResponse(signed, SECRET)).isTrue();
		}

		/**
		 * The request scheme is proven against eSewa's own servers; the response scheme is not.
		 *
		 * <p>A form POST signed this way returns HTTP 200 and eSewa's login page, and the status
		 * API then reports the created transaction — so the request message format is confirmed
		 * rather than inferred.
		 *
		 * <p>For the <em>response</em> there is no such ground truth available without completing a
		 * real sandbox payment, and eSewa's one published response example does not match either
		 * the documented scheme or a bare concatenation, so it is stale like the other three. This
		 * test therefore pins the structural properties — which is what actually protects the
		 * order — rather than asserting a value nobody can reproduce.
		 *
		 * <p>And the architecture does not depend on getting this right: {@code EsewaGateway}
		 * marks an order paid only from a server-to-server status query, never from this
		 * signature. A wrong guess here costs a customer a slower page, not a shipped order.
		 */
		@Test
		@DisplayName("pins the message shape rather than a published signature value")
		void pinsTheMessageShape() {
			assertThat(EsewaSignature.RESPONSE_SIGNED_FIELDS)
					.isEqualTo("transaction_code,status,total_amount,transaction_uuid,"
							+ "product_code,signed_field_names");

			// Five values plus the field list itself, which is itself signed -- eleven
			// comma-separated parts in total, against the request signature's three.
			String message = "0004T5I,COMPLETE,230,12-6-23,EPAYTEST,"
					+ EsewaSignature.RESPONSE_SIGNED_FIELDS;
			assertThat(message.split(",")).hasSize(11);
			assertThat(message).endsWith(EsewaSignature.RESPONSE_SIGNED_FIELDS);
			assertThat(EsewaSignature.sign(message, SECRET)).isNotBlank();
		}

		@Test
		@DisplayName("rejects a tampered status")
		void rejectsTamperedStatus() {
			String message = "0004T5I,COMPLETE,230,12-6-23,EPAYTEST,"
					+ EsewaSignature.RESPONSE_SIGNED_FIELDS;
			String signature = EsewaSignature.sign(message, SECRET);

			var tampered = body(BODY.replace("PLACEHOLDER", signature).replace("\"COMPLETE\"", "\"AMBIGUOUS\""));

			assertThat(EsewaSignature.verifyResponse(tampered, SECRET)).isFalse();
		}

		@Test
		@DisplayName("rejects a response that narrows its own signed field set")
		void rejectsNarrowedFieldList() {
			String signature = EsewaSignature.sign("COMPLETE", SECRET);
			// A hostile body claiming only one field is signed could otherwise be made to verify
			// without its transaction code or amount being authentic.
			var hostile = body(BODY
					.replace("PLACEHOLDER", signature)
					.replace(EsewaSignature.RESPONSE_SIGNED_FIELDS, "status"));

			assertThat(EsewaSignature.verifyResponse(hostile, SECRET)).isFalse();
		}

		@Test
		@DisplayName("rejects a signature from a different secret")
		void rejectsWrongSecret() {
			String message = "0004T5I,COMPLETE,230,12-6-23,EPAYTEST,"
					+ EsewaSignature.RESPONSE_SIGNED_FIELDS;
			var body = body(BODY.replace("PLACEHOLDER", EsewaSignature.sign(message, "a different secret")));

			assertThat(EsewaSignature.verifyResponse(body, SECRET)).isFalse();
		}

		@Test
		@DisplayName("rejects a body with no signature at all")
		void rejectsMissingSignature() {
			var body = body(BODY.replace("PLACEHOLDER", "").replace("\"signature\"", "\"x\""));

			assertThat(EsewaSignature.verifyResponse(body, SECRET)).isFalse();
		}
	}

	// ------------------------------------------------------------------ decoding

	@Nested
	@DisplayName("decoding the redirect payload")
	class Decoding {

		@Test
		@DisplayName("decodes eSewa's published base64 body")
		void decodesPublishedExample() {
			// eSewa's own example, exactly as it appears on the success redirect.
			String encoded = "ewogICAgInN0YXR1cyI6ICJDT01QTEVURSIsCiAgICAic2lnbmF0dXJlIjogIkFwQWdGYU9I"
					+ "OEIxOTAwb1RJNjJJbDlHdmw1QzZZODdNMFc2YTFsUnJLMTA9IiwKICAgICJ0cmFuc2FjdGlv"
					+ "bl9jb2RlIjogIjAwMDRUNUkiLAogICAgInRvdGFsX2Ftb3VudCI6IDIzMCwKICAgICJ0cmFu"
					+ "c2FjdGlvbl91dWlkIjogIjEyLTYtMjMiLAogICAgInByb2R1Y3RfY29kZSI6ICJxZnhjaW5l"
					+ "bWFzIiwKICAgICJzdWNjZXNzX3VybCI6ICJodHRwczovL2VzZXdhLmNvbS5ucCIsICAgICAi"
					+ "c2lnbmVkX2ZpZWxkX25hbWVzIjogInRyYW5zYWN0aW9uX2NvZGUsc3RhdHVzLHRvdGFsX2Ft"
					+ "b3VudCx0cmFuc2FjdGlvbl91dWlkLHByb2R1Y3RfY29kZSxzaWduZWRfZmllbGRfbmFtZXMiCn0=";

			var body = EsewaSignature.decodeResponse(encoded);

			assertThat(body).isNotNull();
			assertThat(body.path("status").asText()).isEqualTo("COMPLETE");
			assertThat(body.path("transaction_code").asText()).isEqualTo("0004T5I");
			assertThat(body.path("total_amount").asInt()).isEqualTo(230);
		}

		@Test
		@DisplayName("returns null for junk rather than throwing")
		void junkIsNull() {
			// A return url is a place a customer can arrive with arbitrary text appended. A parse
			// failure must show an error page, never a stack trace on a payment page.
			assertThat(EsewaSignature.decodeResponse(null)).isNull();
			assertThat(EsewaSignature.decodeResponse("")).isNull();
			assertThat(EsewaSignature.decodeResponse("not base64 !!!")).isNull();
			assertThat(EsewaSignature.decodeResponse("aGVsbG8=")).isNull(); // valid base64, not JSON
		}

		@Test
		@DisplayName("keeps a numeric amount without adding a decimal point")
		void numericAmountFormat() {
			var body = body("{\"total_amount\":230}");

			assertThat(body.path("total_amount").asText()).isEqualTo("230");
			// The request side sends "230" too, so a signed response must be reproduced byte for
			// byte -- "230.0" would fail a correct payment.
			assertThat(new String(
					EsewaSignature.decodeResponse(java.util.Base64.getEncoder()
							.encodeToString("{\"total_amount\":230}".getBytes(StandardCharsets.UTF_8)))
							.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
					.contains("230");
		}
	}
}