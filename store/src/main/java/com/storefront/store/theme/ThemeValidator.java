package com.storefront.store.theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Server-side validation of a theme document, mirroring the shared zod schema (D8).
 *
 * <p>The zod schema in {@code packages/theme-engine} is the contract; this is the server's copy of
 * it. Both exist because the two run in different places and neither can call the other: the
 * browser cannot be trusted to have validated a template before an admin publishes it, and the
 * server cannot reach into the storefront's module graph.
 *
 * <p><b>Validation is strict about structure and permissive about a section's own props.</b> A
 * strict union of 24 prop shapes would need a discriminated union the moment two sections share
 * a field name, and every new prop would become a breaking schema change. So the envelope is
 * checked properly — version, tokens, section shape, nav — and each section type validates its own
 * props in the renderer, which is the only place that knows what a prop means.
 *
 * <p>What <em>is</em> strict is anything that would be dangerous or confusing: an unknown section
 * type, a nav link pointing off-site over http, and a custom-CSS blob (accepted and dropped, D15).
 */
@Component
@RequiredArgsConstructor
public class ThemeValidator {

	/** The schema version this build writes and understands. */
	public static final int SCHEMA_VERSION = 1;

	/** Versions this build can still render, so an old theme does not stop working. */
	private static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1);

	private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");
	private static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

	/** Token names that are colours, and so must be a 6-digit hex. */
	private static final Set<String> COLOUR_TOKENS = Set.of(
			"primary", "background", "foreground", "muted", "accent", "destructive", "border", "ring");

	private final ObjectMapper json;

	/**
	 * Validates and returns the theme as a JSON string ready for storage.
	 *
	 * @return the canonical JSON, so what is validated is exactly what is persisted
	 * @throws ThemeValidationException with every problem found
	 */
	public String validateAndNormalise(String rawJson) {
		JsonNode root;
		try {
			root = json.readTree(rawJson);
		} catch (Exception e) {
			throw new ThemeValidationException("The theme is not valid JSON: " + e.getMessage());
		}
		return validateAndNormalise(root).toString();
	}

	public JsonNode validateAndNormalise(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw new ThemeValidationException("A theme must be a JSON object");
		}

		List<String> issues = new ArrayList<>();

		validateVersion(root, issues);
		validateTokens(root, issues);
		validateSections(root, issues);
		validateNav(root, issues);
		validateFooter(root, issues);

		if (!issues.isEmpty()) {
			throw new ThemeValidationException(issues);
		}
		return root;
	}

	// ------------------------------------------------------------------ version

	private void validateVersion(JsonNode root, List<String> issues) {
		JsonNode version = root.get("schemaVersion");

		if (version == null || version.isNull()) {
			// Absent means "the current version". Every Phase 3 theme is v1, and requiring the
			// field would add noise to hand-written configs for no safety gained.
			return;
		}
		if (!version.isInt() || version.asInt() <= 0) {
			issues.add("schemaVersion: must be a positive whole number");
			return;
		}
		if (!SUPPORTED_VERSIONS.contains(version.asInt())) {
			// Better a clear refusal than rendering a v2 theme through a v1 renderer and showing
			// the customer sections that were renamed out from under it.
			issues.add("schemaVersion: version " + version.asInt() + " is not supported by this build. "
					+ "Supported: " + SUPPORTED_VERSIONS);
		}
	}

	// ------------------------------------------------------------------ tokens

	private void validateTokens(JsonNode root, List<String> issues) {
		JsonNode tokens = root.get("tokens");
		if (tokens == null || tokens.isNull()) {
			return;
		}
		if (!tokens.isObject()) {
			issues.add("tokens: must be an object");
			return;
		}

		tokens.fields().forEachRemaining(entry -> {
			String name = entry.getKey();
			JsonNode value = entry.getValue();

			if (value.isNull()) {
				return;
			}
			if (!value.isTextual()) {
				issues.add("tokens." + name + ": must be a string");
				return;
			}
			String text = value.asText();
			if (COLOUR_TOKENS.contains(name) && !HEX_COLOUR.matcher(text).matches()) {
				// A named colour or an rgb() here would work in CSS and break every component
				// that reads the variable, so the schema asks for one form.
				issues.add("tokens." + name + ": use a 6-digit hex colour like #7c3aed");
			}
			if ("radius".equals(name) && (text.isBlank() || text.length() > 20)) {
				issues.add("tokens.radius: must be a length like 0.75rem");
			}
			if (("fontHeading".equals(name) || "fontBody".equals(name))
					&& (text.isBlank() || text.length() > 80)) {
				issues.add("tokens." + name + ": must be a font family name");
			}
			if ("mode".equals(name)
					&& !List.of("light", "dark", "system").contains(text)) {
				issues.add("tokens.mode: must be light, dark or system");
			}
			if ("customCss".equals(name)) {
				// Accepted and stored so a shop does not lose the field when the feature is
				// deferred (D15), but the renderer strips it. Flagging it here is deliberate:
				// a paid tier that has not shipped yet should not look like it has.
				issues.add("tokens.customCss: custom CSS is not available yet and will not be "
						+ "rendered. It is saved but ignored.");
			}
		});
	}

	// ------------------------------------------------------------------ sections

	private void validateSections(JsonNode root, List<String> issues) {
		JsonNode sections = root.get("sections");
		if (sections == null || sections.isNull()) {
			return;
		}
		if (!sections.isArray()) {
			issues.add("sections: must be an array");
			return;
		}

		Set<String> seenIds = new java.util.HashSet<>();

		for (int i = 0; i < sections.size(); i++) {
			JsonNode section = sections.get(i);
			String path = "sections[" + i + "]";

			if (!section.isObject()) {
				issues.add(path + ": must be an object");
				continue;
			}

			String id = text(section, "id");
			if (id == null || id.isBlank()) {
				issues.add(path + ".id: required and must not be blank");
			} else if (!seenIds.add(id)) {
				// Ids are how the editor addresses a section. A duplicate means two rows render and
				// the editor's "select this section" is ambiguous.
				issues.add(path + ".id: '" + id + "' is used by more than one section");
			}

			String typeName = text(section, "type");
			if (typeName == null || typeName.isBlank()) {
				issues.add(path + ".type: required");
				continue;
			}
			SectionType type = SectionType.fromWireName(typeName);
			if (type == null) {
				issues.add(path + ".type: '" + typeName + "' is not a known section type");
				continue;
			}
			if (type == SectionType.UNKNOWN_SECTION) {
				// The placeholder exists so the renderer degrades gracefully for a theme written by
				// a NEWER build. Persisting one deliberately would be a shop choosing to show
				// customers a broken slot.
				issues.add(path + ".type: unknown-section cannot be used in a saved theme");
			}

			JsonNode props = section.get("props");
			if (props != null && !props.isNull() && !props.isObject()) {
				issues.add(path + ".props: must be an object");
			}

			JsonNode hidden = section.get("hidden");
			if (hidden != null && !hidden.isNull() && !hidden.isBoolean()) {
				issues.add(path + ".hidden: must be true or false");
			}
		}
	}

	// ------------------------------------------------------------------ nav and footer

	private void validateNav(JsonNode root, List<String> issues) {
		JsonNode nav = root.get("nav");
		if (nav == null || nav.isNull()) {
			return;
		}
		if (!nav.isObject()) {
			issues.add("nav: must be an object");
			return;
		}
		JsonNode menu = nav.get("menu");
		if (menu == null || menu.isNull()) {
			return;
		}
		if (!menu.isArray()) {
			issues.add("nav.menu: must be an array");
			return;
		}

		for (int i = 0; i < menu.size(); i++) {
			JsonNode item = menu.get(i);
			String path = "nav.menu[" + i + "]";

			if (!item.isObject()) {
				issues.add(path + ": must be an object");
				continue;
			}
			String label = text(item, "label");
			if (label == null || label.isBlank()) {
				issues.add(path + ".label: required");
			}
			String href = text(item, "href");
			if (href == null || href.isBlank()) {
				issues.add(path + ".href: required");
			} else if (!isSafeHref(href)) {
				// A theme is authored by a shop owner and rendered for customers, so a javascript:
				// link here is stored XSS. Relative paths and absolute https only.
				issues.add(path + ".href: must be a site-relative path or an absolute https URL");
			}
		}
	}

	private void validateFooter(JsonNode root, List<String> issues) {
		JsonNode footer = root.get("footer");
		if (footer == null || footer.isNull()) {
			return;
		}
		if (!footer.isObject()) {
			issues.add("footer: must be an object");
			return;
		}
		JsonNode columns = footer.get("columns");
		if (columns != null && columns.isInt()) {
			int value = columns.asInt();
			if (value < 1 || value > 6) {
				issues.add("footer.columns: must be between 1 and 6");
			}
		}
		String handle = text(footer, "assetHandle");
		if (handle != null && !HANDLE.matcher(handle).matches()) {
			// The handle is a key into theme_asset, and a key that does not match the column's
			// pattern could never be created, so a section would reference something that can
			// never exist.
			issues.add("footer.assetHandle: must be lowercase letters, digits and dashes");
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Site-relative, absolute https, or a fragment. Nothing else. */
	public static boolean isSafeHref(String href) {
		String value = href.trim().toLowerCase(java.util.Locale.ROOT);
		if (value.startsWith("#")) {
			return true;
		}
		if (value.startsWith("/") && !value.startsWith("//")) {
			// "//evil.com" is protocol-relative and would leave the site.
			return !value.startsWith("/\\");
		}
		return value.startsWith("https://");
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || !value.isTextual() ? null : value.asText();
	}

	/** Validates a theme asset handle against the same rule the column enforces. */
	public static boolean isValidHandle(String handle) {
		return handle != null && HANDLE.matcher(handle).matches();
	}
}
