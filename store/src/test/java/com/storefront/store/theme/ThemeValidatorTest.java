package com.storefront.store.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Theme validation, the server's half of the zod schema contract.
 *
 * <p>A malformed theme is rejected at write time. The alternative — accepting it and rendering
 * whatever falls out — puts a shop owner in front of a customer who sees a broken storefront, and
 * the owner has no idea which change caused it.
 *
 * <p>Two shapes of check are asserted repeatedly: a **structural** problem is an error, and a
 * **cosmetic** one is reported without failing. Saying "custom CSS will not be rendered" as an
 * exception would make the feature unusable for a template that happens to carry the field; saying
 * nothing would leave a paid tier looking shipped.
 */
class ThemeValidatorTest {

	private final ThemeValidator validator = new ThemeValidator(
			new com.fasterxml.jackson.databind.ObjectMapper());

	private String theme(String body) {
		return """
				{
				  "schemaVersion": 1,
				  "tokens": {},
				  "sections": [],
				  "nav": { "menu": [] },
				  "footer": {}
				}
				""" + body;
	}

	private List<String> issuesOf(String json) {
		return org.assertj.core.api.Assertions.catchThrowableOfType(
				() -> validator.validateAndNormalise(json), ThemeValidationException.class).issues();
	}

	@Nested
	@DisplayName("accepting")
	class Accepting {

		@Test
		@DisplayName("the generic default")
		void genericDefault() {
			assertThatCode(() -> validator.validateAndNormalise(DefaultTemplates.GENERIC))
					.as("the platform default must pass its own validator")
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("every per-type default")
		void typeDefaults() {
			// A default that fails validation would leave a new shop with an unrenderable theme,
			// which is the one outcome the defaults exist to prevent.
			for (var type : com.storefront.store.catalog.ShopType.values()) {
				assertThatCode(() -> validator.validateAndNormalise(DefaultTemplates.forShopType(type)))
						.as("the default for " + type + " must be valid")
						.doesNotThrowAnyException();
			}
		}

		@Test
		@DisplayName("a minimal empty theme")
		void minimal() {
			assertThatCode(() -> validator.validateAndNormalise("{}")).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("a theme with no schemaVersion, which means the current one")
		void versionOptional() {
			// Requiring it would add noise to hand-written configs for no safety gained, since
			// every Phase 3 theme is v1.
			assertThatCode(() -> validator.validateAndNormalise("""
					{ "tokens": {}, "sections": [] }""")).doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("rejecting a bad document")
	class Rejecting {

		@Test
		@DisplayName("not JSON at all")
		void notJson() {
			assertThatThrownBy(() -> validator.validateAndNormalise("{nope"))
					.isInstanceOf(ThemeValidationException.class)
					.hasMessageContaining("not valid JSON");
		}

		@Test
		@DisplayName("an unsupported schema version, rather than rendering it wrongly")
		void futureVersion() {
			// Rendering a v2 theme through a v1 renderer would show customers sections renamed
			// out from under them. A clear refusal is kinder.
			assertThat(issuesOf("""
					{ "schemaVersion": 99 }"""))
					.singleElement()
					.asString()
					.contains("99")
					.contains("not supported");
		}

		@Test
		@DisplayName("an unknown section type")
		void unknownSectionType() {
			assertThat(issuesOf("""
					{ "sections": [ { "id": "a", "type": "not-a-real-section", "props": {} } ] }"""))
					.singleElement().asString()
					.contains("not a known section type");
		}

		@Test
		@DisplayName("the unknown-section placeholder as a real choice")
		void placeholderNotSavable() {
			// It exists so the renderer degrades for a theme from a NEWER build. Saving one on
			// purpose would be a shop choosing to show customers a broken slot.
			assertThat(issuesOf("""
					{ "sections": [ { "id": "a", "type": "unknown-section" } ] }"""))
					.singleElement().asString()
					.contains("cannot be used in a saved theme");
		}

		@Test
		@DisplayName("two sections sharing an id")
		void duplicateSectionId() {
			// The editor addresses sections by id, so a duplicate makes "select this section"
			// ambiguous and both rows render.
			List<String> issues = issuesOf("""
					{ "sections": [
					  { "id": "hero", "type": "hero" },
					  { "id": "hero", "type": "spacer" }
					] }""");
			assertThat(issues).singleElement().asString().contains("more than one section");
		}

		@Test
		@DisplayName("a section with no id")
		void missingId() {
			assertThat(issuesOf("""
					{ "sections": [ { "type": "hero" } ] }"""))
					.singleElement().asString().contains("id: required");
		}

		@Test
		@DisplayName("a colour token that is not a 6-digit hex")
		void badColour() {
			// A named colour or rgb() works in CSS and breaks every component that reads the
			// variable, so the schema asks for one form.
			assertThat(issuesOf("""
					{ "tokens": { "primary": "rebeccapurple" } }"""))
					.singleElement().asString()
					.contains("tokens.primary")
					.contains("#7c3aed");
		}

		@Test
		@DisplayName("every problem at once, not just the first")
		void reportsAllIssues() {
			// An owner fixing a theme through a form needs to see all of it. A validator that stops
			// at the first failure turns five corrections into five round trips.
			List<String> issues = issuesOf("""
					{
					  "schemaVersion": 99,
					  "tokens": { "primary": "nope" },
					  "sections": [ { "type": "nope" } ],
					  "nav": { "menu": [ { "label": "", "href": "javascript:alert(1)" } ] }
					}""");

			assertThat(issues)
					.as("all four problems, reported together")
					.hasSizeGreaterThanOrEqualTo(4)
					.anySatisfy(i -> assertThat(i).contains("schemaVersion"))
					.anySatisfy(i -> assertThat(i).contains("tokens.primary"))
					.anySatisfy(i -> assertThat(i).contains("sections[0]"))
					.anySatisfy(i -> assertThat(i).contains("nav.menu[0].href"));
		}

		@Test
		@DisplayName("a nav link that would execute script")
		void javascriptHref() {
			// A theme is authored by a shop owner and rendered for customers, so this is stored
			// XSS in the one place a shop owner and a stranger both reach.
			assertThat(issuesOf("""
					{ "nav": { "menu": [ { "label": "Sale", "href": "javascript:alert(1)" } ] } }"""))
					.singleElement().asString()
					.contains("site-relative")
					.contains("https");
		}

		@Test
		@DisplayName("a protocol-relative link that leaves the site")
		void protocolRelativeHref() {
			assertThat(issuesOf("""
					{ "nav": { "menu": [ { "label": "x", "href": "//evil.example" } ] } }"""))
					.singleElement();
		}

		@Test
		@DisplayName("an http link, because a theme's links are content not transport")
		void httpHrefRejected() {
			assertThat(issuesOf("""
					{ "nav": { "menu": [ { "label": "x", "href": "http://insecure.example" } ] } }"""))
					.singleElement();
		}

		@Test
		@DisplayName("a footer handle that could never be created")
		void impossibleHandle() {
			// The column enforces the same pattern, so a section would reference an asset that
			// can never exist.
			assertThat(issuesOf("""
					{ "footer": { "assetHandle": "Not A Handle!" } }"""))
					.singleElement().asString()
					.contains("lowercase");
		}
	}

	@Nested
	@DisplayName("warning without failing")
	class Warning {

		@Test
		@DisplayName("custom CSS is refused with an explanation, not silently dropped")
		void customCssRefused() {
			// D15: the field exists so a saved theme does not lose data when the feature is
			// deferred. Failing outright would make the feature unusable for a template that
			// carries it; saying nothing would make a paid tier look shipped.
			List<String> issues = issuesOf("""
					{ "tokens": { "primary": "#16a34a", "customCss": ".x{}" } }""");

			assertThat(issues).singleElement().asString()
					.contains("not available yet")
					.contains("saved but ignored");
		}

		@Test
		@DisplayName("a section marked hidden is valid")
		void hiddenSection() {
			// The editor toggles visibility without deleting work, so it is a normal state.
			assertThatCode(() -> validator.validateAndNormalise("""
					{ "sections": [ { "id": "a", "type": "spacer", "hidden": true } ] }"""))
					.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("safe links")
	class SafeLinks {

		@Test
		@DisplayName("relative paths, absolute https and fragments are allowed")
		void allowed() {
			assertThat(ThemeValidator.isSafeHref("/products")).isTrue();
			assertThat(ThemeValidator.isSafeHref("#products")).isTrue();
			assertThat(ThemeValidator.isSafeHref("https://example.com")).isTrue();
			assertThat(ThemeValidator.isSafeHref("/c/rice")).isTrue();
		}

		@Test
		@DisplayName("everything else is not")
		void refused() {
			assertThat(ThemeValidator.isSafeHref("javascript:alert(1)")).isFalse();
			assertThat(ThemeValidator.isSafeHref("//evil.example")).isFalse();
			assertThat(ThemeValidator.isSafeHref("http://insecure.example")).isFalse();
			assertThat(ThemeValidator.isSafeHref("data:text/html,<script>")).isFalse();
		}
	}

	@Nested
	@DisplayName("layout")
	class Layout {

		private String withLayout(String body) {
			return """
					{ "schemaVersion": 1, "tokens": {}, "layout": %s, "sections": [] }
					""".formatted(body);
		}

		@Test
		@DisplayName("every shipped choice is accepted")
		void accepted() {
			assertThatCode(() -> validator.validateAndNormalise(withLayout("""
					{ "width": "full", "header": "bar", "card": "editorial", "bands": true, "display": "compact" }
					"""))).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("an empty block means every default")
		void empty() {
			assertThatCode(() -> validator.validateAndNormalise(withLayout("{}")))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("an unknown value is an error, not a silent default")
		void unknownValue() {
			// The browser schema would reject the document and the storefront would fall back to
			// the empty theme, so storing this would be storing a page nobody can see.
			assertThat(issuesOf(withLayout("""
					{ "header": "floating" }
					"""))).anyMatch(issue -> issue.startsWith("layout.header"));
		}

		@Test
		@DisplayName("an unknown key is an error, so typos do not vanish")
		void unknownKey() {
			assertThat(issuesOf(withLayout("""
					{ "headers": "bar" }
					"""))).anyMatch(issue -> issue.startsWith("layout.headers"));
		}

		@Test
		@DisplayName("bands must be a boolean")
		void bandsType() {
			assertThat(issuesOf(withLayout("""
					{ "bands": "yes" }
					"""))).anyMatch(issue -> issue.startsWith("layout.bands"));
		}

		@Test
		@DisplayName("the block itself must be an object")
		void blockType() {
			assertThat(issuesOf(withLayout("[]"))).contains("layout: must be an object");
		}
	}

	@Nested
	@DisplayName("handles")
	class Handles {

		@Test
		@DisplayName("lowercase, digits and dashes")
		void valid() {
			assertThat(ThemeValidator.isValidHandle("footer-main")).isTrue();
			assertThat(ThemeValidator.isValidHandle("hero1")).isTrue();
			assertThat(ThemeValidator.isValidHandle("a")).isTrue();
		}

		@Test
		@DisplayName("anything else is not")
		void invalid() {
			assertThat(ThemeValidator.isValidHandle("Footer-Main")).isFalse();
			assertThat(ThemeValidator.isValidHandle("footer main")).isFalse();
			assertThat(ThemeValidator.isValidHandle("-leading")).isFalse();
			assertThat(ThemeValidator.isValidHandle("")).isFalse();
			assertThat(ThemeValidator.isValidHandle(null)).isFalse();
		}
	}
}
