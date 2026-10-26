package com.storefront.store.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The shipped catalogue, checked where it is written rather than where it is used.
 *
 * <p><b>Why this exists.</b> The seeder runs with {@code store.themes.seed=false} under test, so
 * nothing in the suite ever reads {@code theme-templates/*.json}. A typo in one of those files
 * would reach production as a log line at boot — "Could not seed theme template" — and a shop
 * owner would simply find a smaller catalogue than the one the product promised. A test that
 * validates every file against the same server-side validator the seeder uses turns that into a
 * build failure instead.
 *
 * <p>It also pins the shape of the offer: at least ten templates, several categories, and unique
 * codes. Those are product claims, not incidental details, and they are cheap to assert.
 */
class ThemeTemplateCatalogueTest {

	private static final String LOCATION = "classpath:theme-templates/*.json";

	private final ObjectMapper json = new ObjectMapper();
	private final ThemeValidator validator = new ThemeValidator(json);

	@Test
	@DisplayName("every shipped template validates and carries the metadata the gallery needs")
	void everyTemplateIsUsable() throws Exception {
		Resource[] files = new PathMatchingResourcePatternResolver().getResources(LOCATION);

		assertThat(files)
				.as("the catalogue is what makes 'choose a ready-made theme' true")
				.hasSizeGreaterThanOrEqualTo(10);

		Set<String> codes = new HashSet<>();
		Set<String> categories = new HashSet<>();

		for (Resource file : files) {
			String name = String.valueOf(file.getFilename());
			try (InputStream in = file.getInputStream()) {
				JsonNode root = json.readTree(in);

				String code = root.path("code").asText();
				assertThat(code).as("%s has a code", name).isNotBlank();
				assertThat(codes.add(code)).as("%s reuses the code '%s'", name, code).isTrue();

				assertThat(root.path("name").asText()).as("%s has a name", name).isNotBlank();
				assertThat(root.path("description").asText()).as("%s has a description", name).isNotBlank();
				assertThat(root.path("category").asText())
						.as("%s needs a shelf, or the gallery cannot group it", name)
						.isNotBlank();
				categories.add(root.path("category").asText());

				JsonNode config = root.path("config");
				assertThat(config.isObject()).as("%s has a config object", name).isTrue();

				// The same validator the seeder and the publish endpoint call. A file it rejects
				// is a file no shop can install.
				assertThat(validator.validateAndNormalise(config.toString()))
						.as("%s is a valid theme document", name)
						.isNotBlank();
			}
		}

		assertThat(categories)
				.as("a catalogue of one category is a catalogue of identical layouts")
				.hasSizeGreaterThanOrEqualTo(8);
	}

	@Test
	@DisplayName("the shop-type filter and the shelf label are different fields")
	void categoryIsNotAShopTypeFilter() throws Exception {
		Resource[] files = new PathMatchingResourcePatternResolver().getResources(LOCATION);

		// Every template except the deliberately clinical one is open to every shop type. A
		// grocery owner may want the fashion layout; the shelf label says what a template is
		// *for*, while shopType decides who may see it.
		long untagged = 0;
		for (Resource file : files) {
			try (InputStream in = file.getInputStream()) {
				JsonNode shopType = json.readTree(in).get("shopType");
				if (shopType == null || shopType.isNull()) {
					untagged++;
				}
			}
		}

		assertThat(untagged)
				.as("most templates must be offered to every shop, or the catalogue is invisible")
				.isGreaterThanOrEqualTo(files.length - 2);
	}
}
