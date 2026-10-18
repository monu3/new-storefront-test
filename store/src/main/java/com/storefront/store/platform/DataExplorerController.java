package com.storefront.store.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The admin data explorer (Phase 7).
 *
 * <p><b>It reads the live OpenAPI contract rather than a hand-written catalogue.</b> A catalogue
 * drifts the moment an endpoint is added and nobody updates it, and then it lies: the explorer
 * offers an endpoint that no longer exists and omits one that does. springdoc already builds the
 * contract from the same annotations the controllers use, so this is a view over the truth.
 *
 * <p><b>Only safe, admin-owned paths are offered.</b> The contract describes the whole platform,
 * including the shop endpoints and every customer-facing route, so the explorer does not execute
 * arbitrary paths. It names which paths it will run and refuses everything else, and it refuses
 * every non-GET method regardless.
 */
@RestController
@RequestMapping("/api/v1/admin/explorer")
@Tag(name = "Admin data explorer",
		description = "Browse the live API contract and run safe admin reads against it")
@PreAuthorize("hasRole('ADMIN')")
public class DataExplorerController {

	/**
	 * The only path prefix the explorer will run.
	 *
	 * <p>{@code /api/v1/admin} plus the read-only platform endpoints. Shop and customer paths are
	 * excluded on purpose: an explorer that could call anything would be a second, less-audited way
	 * to act on every shop's data, and "I found it in the explorer" is not an answer to "who
	 * changed this".
	 */
	private static final String READABLE_PREFIX = "/api/v1/admin";

	/**
	 * HTTP methods that change something.
	 *
	 * <p>Listed by verb rather than by an allowlist of paths, so a new mutating endpoint is refused
	 * without anyone remembering to update this class.
	 */
	private static final List<String> SAFE_METHODS = List.of("GET");

	/**
	 * The application's own MVC handler mapping.
	 *
	 * <p>Qualified by name because the context holds more than one: Spring Boot's
	 * {@code requestMappingHandlerMapping} and the actuator's, and Spring cannot choose between
	 * them. The unqualified injection fails with a {@code NoUniqueBeanDefinitionException} whose
	 * message lists every candidate without saying which one serves the API.
	 */
	private final org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping routes;
	private final DataExplorerProbe probe;

	public DataExplorerController(
			@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
			org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping routes,
			DataExplorerProbe probe) {
		this.routes = routes;
		this.probe = probe;
	}

	/**
	 * The contract, as a browsable tree.
	 *
	 * <p>Grouped by tag, then by operation. Not flattened: a flat list of 200 paths is a list
	 * nobody scrolls, and the tag is how the API is already documented.
	 */
	@GetMapping("/contract")
	@Operation(summary = "The live OpenAPI contract, grouped by tag",
			description = "Read-only. Operations the explorer will not run are marked unsafe.")
	public Contract contract() {
		// Read from the live routing table, not from a hand-written list and not from the
		// springdoc bean.
		//
		// <p>The hand-written list drifts the moment an endpoint is added, and then it lies in
		// both directions at once. The springdoc `OpenAPI` bean is not usable either: springdoc uses
		// that bean for the document's *metadata* and computes the paths itself, so injecting it
		// yields an empty `paths` map and an explorer that reports "this platform has no
		// endpoints" -- which looks like a platform problem and is not one.
		//
		// <p>The routing table is what Spring itself dispatches on, so it cannot disagree with the
		// running application. Summaries and tags are read from the same annotations the controllers
		// already carry, so nothing new has to be maintained anywhere.
		Map<String, List<Endpoint>> byTag = new LinkedHashMap<>();
		int[] safe = {0};

		routes.getHandlerMethods().forEach((mapping, handler) -> {
			var methods = handler.getMethod();

			for (var pattern : mapping.getPathPatternsCondition() == null
					? java.util.List.<org.springframework.web.util.pattern.PathPattern>of()
					: mapping.getPathPatternsCondition().getPatterns()) {

				String path = pattern.getPatternString();

				for (var request : mapping.getMethodsCondition().getMethods()) {
					String verb = request.name();

					boolean runnable = isRunnable(path, verb);
					if (runnable) {
						safe[0]++;
					}

					byTag.computeIfAbsent(tagOf(methods, path), key -> new ArrayList<>())
							.add(new Endpoint(verb, path,
									summaryOf(methods),
									methods.getName(),
									runnable,
									parametersOf(methods)));
				}
			}
		});

		List<TagGroup> tags = byTag.entrySet().stream()
				.map(entry -> new TagGroup(entry.getKey(), entry.getValue().size(), entry.getValue()))
				.toList();

		if (tags.isEmpty()) {
			// Worth saying rather than rendering an empty tree: an explorer with nothing in it
			// looks broken, and the cause here would not be obvious.
			return new Contract(List.of(), 0, List.of());
		}

		return new Contract(tags, safe[0], List.copyOf(byTag.keySet()));
	}

	/** The {@code @Operation(summary)} on a handler, or a description of its signature. */
	private static String summaryOf(java.lang.reflect.Method method) {
		var annotation = method.getAnnotation(io.swagger.v3.oas.annotations.Operation.class);
		if (annotation != null && !annotation.summary().isBlank()) {
			return annotation.summary();
		}
		return verbName(method) + " " + method.getName();
	}

	private static String verbName(java.lang.reflect.Method method) {
		for (var annotation : method.getAnnotations()) {
			String name = annotation.annotationType().getSimpleName();
			if (name.endsWith("Mapping") && !name.startsWith("Request")) {
				return name.replace("Mapping", "");
			}
		}
		return "Handler";
	}

	/**
	 * The tag a handler belongs to, from its class's {@code @Tag} or its method's.
	 *
	 * <p>Falls back to a path segment, so a controller without a {@code @Tag} is grouped somewhere
	 * sensible rather than dumped in an "Untagged" pile that nobody reads.
	 */
	private static String tagOf(java.lang.reflect.Method method, String path) {
		var onMethod = method.getAnnotation(io.swagger.v3.oas.annotations.tags.Tag.class);
		if (onMethod != null && !onMethod.name().isBlank()) {
			return onMethod.name();
		}

		var onClass = method.getDeclaringClass()
				.getAnnotation(io.swagger.v3.oas.annotations.tags.Tag.class);
		if (onClass != null && !onClass.name().isBlank()) {
			return onClass.name();
		}

		String[] segments = path.split("/");
		return segments.length > 3 ? segments[3] : "Untagged";
	}

	/**
	 * The query parameters a handler declares.
	 *
	 * <p>Read from the compiled parameter list rather than the annotations, because that is what
	 * Spring will actually bind: an unannotated parameter is a rejected request, and offering it
	 * would be a form that cannot be submitted successfully.
	 *
	 * <p>Simple types only. A parameter that is a body object is not a query parameter, and a
	 * screen that offers it as one submits nothing.
	 */
	/** Whether Spring will reject the request without this parameter. */
	private static boolean isRequired(java.lang.reflect.Parameter parameter) {
		var annotation = parameter.getAnnotation(
				org.springframework.web.bind.annotation.RequestParam.class);
		return annotation != null && annotation.required();
	}

	private static List<Parameters> parametersOf(java.lang.reflect.Method method) {
		return java.util.Arrays.stream(method.getParameters())
				.filter(DataExplorerController::isSimple)
				.map(parameter -> new Parameters(
						parameter.getName(),
						"query",
						parameter.getType().getSimpleName(),
						isRequired(parameter),
						null))
				.toList();
	}

	/**
	 * Whether a parameter can be bound from a query string.
	 *
	 * <p>An enum, a UUID, a number, a boolean or a String. Anything else is a request body, and
	 * the explorer's job is reads.
	 */
	private static boolean isSimple(java.lang.reflect.Parameter parameter) {
		Class<?> type = parameter.getType();
		return type.isPrimitive()
				|| type == String.class
				|| type.isEnum()
				|| Number.class.isAssignableFrom(type)
				|| type == java.util.UUID.class
				|| type == Boolean.class;
	}

	/**
	 * Whether the explorer would run this operation, and why not when it would not.
	 *
	 * <p>Reasoned here rather than sent as a bare boolean: an explorer that silently omits a button
	 * and gives no reason looks broken, and the operator's next move is to find another way to call
	 * it — which is the thing this class exists to make unnecessary.
	 */
	private static String refusal(String path, String method) {
		if (!path.startsWith(READABLE_PREFIX)) {
			return "Outside " + READABLE_PREFIX + ". Shop and customer data is reached through "
					+ "its own screens, not from here.";
		}
		if (!SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
			return method.toUpperCase(Locale.ROOT) + " changes something. This explorer only reads; "
					+ "every write on this platform is audited, and a screen is where an audited write "
					+ "belongs.";
		}
		return null;
	}

	private static boolean isRunnable(String path, String method) {
		return refusal(path, method) == null;
	}

	/**
	 * The verbs the explorer will run, uppercase.
	 *
	 * <p>Case matters here and the mismatch is silent: {@code SAFE_METHODS.contains(method
	 * .toLowerCase())} against a list of uppercase verbs is always false, so every operation is
	 * marked unsafe and the screen renders correctly and uselessly.
	 */

	/**
	 * The query parameters of one operation.
	 *
	 * <p>Only {@code in: query}. Header and cookie parameters are the caller's transport, not the
	 * operation's contract, and offering them in a form the explorer cannot fill is a form that
	 * submits a wrong value.
	 */
	private static List<Parameters> parameters(io.swagger.v3.oas.models.Operation op) {
		if (op.getParameters() == null) {
			return List.of();
		}
		return op.getParameters().stream()
				.filter(param -> "query".equals(param.getIn()))
				.map(param -> new Parameters(
						param.getName(),
						param.getIn(),
						param.getSchema() == null ? "string" : param.getSchema().getType(),
						Boolean.TRUE.equals(param.getRequired()),
						param.getExample() == null ? null : String.valueOf(param.getExample())))
				.toList();
	}

	private static String tagOf(io.swagger.v3.oas.models.Operation op, String path) {
		if (op.getTags() != null && !op.getTags().isEmpty()) {
			return op.getTags().get(0);
		}
		// Untagged operations still need somewhere to live. Deriving the tag from the path means
		// a new controller without a @Tag is grouped sensibly rather than dumped in "Untagged".
		String[] segments = path.split("/");
		return segments.length > 3 ? segments[3] : "Untagged";
	}

	// ------------------------------------------------------------------ types

	public record Contract(List<TagGroup> tags, int runnableCount, List<String> tagNames) {
	}

	public record TagGroup(String name, int operations, List<Endpoint> items) {
	}

	public record Endpoint(
			String method,
			String path,
			String summary,
			String operationId,
			boolean runnable,
			List<Parameters> parameters) {
	}

	/** One query parameter, as the explorer shows it. */
	public record Parameters(String name, String in, String type, boolean required, String example) {
	}

	/**
	 * A read to run.
	 *
	 * <p>The path is pattern-constrained rather than merely checked in code, so a malformed path is
	 * rejected before it reaches anything that could act on it.
	 */
	public record ProbeRequest(
			@NotNull
			@Pattern(regexp = "^/api/v1/admin/[A-Za-z0-9/_-]*$",
					message = "Path must be under /api/v1/admin, with letters, digits, /, _ and - only")
			String path,

			@Size(max = 20)
			Map<String, String> params) {
	}

	/**
	 * What one probe returned.
	 *
	 * <p><b>{@code ok} is a record component, not a {@code ok()} method.</b> A convenience accessor
	 * on a record is not a component, so Jackson does not serialise it — and the browser receives
	 * an object with no {@code ok} field at all. The screen then reads {@code undefined} for every
	 * response, renders a successful 200 as a red error, and the bug reads as "the probe never
	 * works" rather than "a field is missing from the JSON".
	 */
	public record ProbeResult(
			String path,
			String query,
			int status,
			Object body,
			String error,
			boolean ok) {

		/** A refusal: nothing was called, so there is no status from the platform. */
		public static ProbeResult refused(String path, String why) {
			return new ProbeResult(path, null, 403, null, why, false);
		}

		public ProbeResult {
			ok = status >= 200 && status < 300;
		}
	}

	// ------------------------------------------------------------------ running one

	/**
	 * Runs one admin read and returns its JSON.
	 *
	 * <p>The caller's own bearer token is forwarded, so a probe can only ever return what the
	 * caller could already see. An operator using this is an admin; an admin's token happens to
	 * authorise everything here, which is why the path restriction above is a restriction rather
	 * than a convenience.
	 *
	 * <p>Refusal is re-checked here, not just when the contract was built. The contract is a
	 * snapshot: it was read at some point, and someone could have posted a form with a path typed by
	 * hand. The check that matters is the one on the request.
	 */
	@PostMapping("/probe")
	@Operation(summary = "Run one safe admin read and return the JSON",
			description = "GET only, under /api/v1/admin. Runs as the calling admin.")
	public ProbeResult probe(@Valid @RequestBody ProbeRequest request,
			HttpServletRequest servlet) {

		String why = refusal(request.path(), "get");
		if (why != null) {
			// Refused with the reason, not a 403 and not an empty body: an operator who typed a
			// path needs to know the rule, not just that it failed.
			return ProbeResult.refused(request.path(), why);
		}

		String bearer = servlet.getHeader(HttpHeaders.AUTHORIZATION);
		return probe.run(request.path(), request.params(), bearer);
	}
}