package com.storefront.store.platform;

import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Runs one read for the data explorer (Phase 7).
 *
 * <p><b>A real HTTP call back into this application, not a direct service call.</b> That is
 * deliberate and it is the whole reason this is a component rather than a method on the controller.
 * The explorer must exercise the same stack a client would — security filters, method security,
 * validation, exception translation — because those are exactly the things an operator is trying to
 * see when they run a query. A direct call to the service layer would answer a different question
 * and look identical on screen.
 *
 * <p><b>The caller's own bearer token is forwarded.</b> Without it every probe would be anonymous
 * and come back 401, and an explorer that only ever shows 401 teaches nothing. Forwarding it also
 * keeps the explorer honest: it can only ever show what the caller could already see, because it
 * runs as them.
 *
 * <p><b>A probe that throws is a result, not a crash.</b> A 400 from a malformed filter is
 * information the operator asked for; a stack trace from the explorer would replace that
 * information with a dead screen.
 */
@Component
public class DataExplorerProbe {

	/**
	 * How long a probe may take.
	 *
	 * <p>Short, because the explorer holds an admin's browser open while it waits, and a slow
	 * report should look slow rather than hang.
	 */
	private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(20);

	private static final int MAX_BODY_CHARS = 200_000;

	private final RestClient http;
	private final String baseUrl;

	public DataExplorerProbe(
			RestClient.Builder builder,
			@Value("${server.servlet.context-path:}") String contextPath,
			@Value("${server.port:8080}") int port) {

		this.baseUrl = "http://localhost:" + port + (contextPath == null ? "" : contextPath);
		this.http = builder.baseUrl(this.baseUrl)
				.requestFactory(requestFactory())
				.build();
	}

	/**
	 * A factory that honours the timeout.
	 *
	 * <p>Built by hand because {@code RestClient.Builder} has no timeout knob on its own; the
	 * default client will wait indefinitely, and a request that waits forever on a screen with a
	 * spinner is indistinguishable from a hung server.
	 */
	private static org.springframework.http.client.ClientHttpRequestFactory requestFactory() {
		var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
		factory.setConnectTimeout((int) TIMEOUT.toMillis());
		factory.setReadTimeout((int) TIMEOUT.toMillis());
		return factory;
	}

	/**
	 * Runs one GET.
	 *
	 * @param bearer the caller's {@code Authorization} header value, forwarded verbatim so the
	 *               inner request is authenticated as them and can only show what they may see
	 * @return the status, the parsed body, or a message. Never throws for an HTTP error — a 400 is
	 *         an answer, and the operator asked the question
	 */
	public DataExplorerController.ProbeResult run(
			String path, Map<String, String> params, String bearer) {

		URI uri = withParams(path, params);
		String query = uri.getQuery();

		try {
			// `exchange` rather than `get`: `get()` throws on 4xx and 5xx, and this screen's job is
			// to show those.
			var response = http.get()
					.uri(uri)
					.header(HttpHeaders.AUTHORIZATION, bearer == null ? "" : bearer)
					.header(HttpHeaders.ACCEPT, "application/json")
					.exchange((req, res) -> {
						int status = res.getStatusCode().value();
						String body = new String(res.getBody().readAllBytes(),
								java.nio.charset.StandardCharsets.UTF_8);

						if (body.length() > MAX_BODY_CHARS) {
							// Truncated rather than dropped, and the reason is returned with it. A
							// silent truncation looks exactly like a small result set.
							body = body.substring(0, MAX_BODY_CHARS)
									+ "\\n... truncated at " + MAX_BODY_CHARS
									+ " characters. Narrow the query to see the rest.";
						}

						Object parsed = status >= 200 && status < 300
								? parse(body)
								: null;

						return new DataExplorerController.ProbeResult(
								path, query, status, parsed,
								parsed == null ? truncate(body) : null, status < 400);
					});

			return response;
		} catch (org.springframework.web.client.ResourceAccessException e) {
			// A timeout or a refused connection. Reported as a result, because an operator watching
			// a spinner for 20 seconds deserves to be told it did not connect rather than left there.
			return new DataExplorerController.ProbeResult(path, query, 0, null,
					"Could not reach the application: " + e.getMessage(), false);
		}
	}

	/**
	 * Parses a JSON body, falling back to the raw text.
	 *
	 * <p>Fallback rather than failure: an endpoint that answers with a CSV or an HTML error page is
	 * still information, and a parser exception would replace "here is what it said" with "here is a
	 * Jackson error".
	 */
	private static Object parse(String body) {
		if (body.isBlank()) {
			return null;
		}
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(body);
		} catch (Exception e) {
			return body;
		}
	}

	private static String truncate(String body) {
		return body.length() <= 2000 ? body : body.substring(0, 2000) + "…";
	}

	/**
	 * Appends parameters, skipping blanks.
	 *
	 * <p>Blanks are dropped rather than sent empty, because {@code ?days=} is a 400 from Spring's
	 * binding and {@code ?days} absent is the default — and the operator's blank box means "I did
	 * not set this", not "I set this to nothing".
	 *
	 * <p>Encoded by the builder, so a search term containing {@code &} or a space is a value rather
	 * than a second parameter.
	 */
	private static URI withParams(String path, Map<String, String> params) {
		var builder = UriComponentsBuilder.fromPath(path);
		if (params != null) {
			params.forEach((name, value) -> {
				if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
					builder.queryParam(name.trim(), value.trim());
				}
			});
		}
		return builder.build().encode().toUri();
	}
}