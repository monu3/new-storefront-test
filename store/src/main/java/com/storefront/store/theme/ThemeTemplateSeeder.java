package com.storefront.store.theme;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.catalog.ShopType;

import lombok.RequiredArgsConstructor;

/**
 * Publishes the built-in theme catalogue so a new shop has something to install.
 *
 * <p><b>Runs on every boot and is a no-op when the codes already exist.</b> A marketplace with
 * nothing in it makes the theme engine look broken: an owner opens the browser, sees an empty
 * grid, and concludes the feature does not work.
 *
 * <p><b>The catalogue is data, not code.</b> Each template is one JSON file under
 * {@code classpath:theme-templates/}, holding its metadata next to the config a shop installs.
 * The first version kept two documents in Java text blocks; at fourteen that becomes a file
 * nobody wants to edit, and a misplaced comma hides inside a class instead of showing up in a
 * diff. A new template is now a new file.
 *
 * <p><b>One bad file must not cost the rest of the catalogue.</b> Files are parsed and seeded
 * independently, so a config the validator rejects is logged with its filename and skipped
 * rather than aborting startup — the platform is perfectly usable with thirteen templates.
 *
 * <p><b>Reconciles shipped content, never shop content.</b> An existing {@code code} has its
 * name, description, shelf, type, order and config replaced when the file differs from the row,
 * so editing a template in the repository is enough to change what the gallery offers. The first
 * version refused to touch an existing row, which meant a shipped fix reached new databases only
 * and every developer's catalogue stayed frozen at whatever they first booted. What is
 * deliberately preserved is publication state and {@code active}: an admin who withdraws a
 * template does not find it back on the shelf after a restart. Installed themes are copies in
 * {@code shop_theme}, so no shop's storefront changes when a template is refreshed.
 *
 * <p><b>Off in tests</b> ({@code store.themes.seed=false} in the test profile). A test asserting
 * "a draft is not offered" cannot also tolerate a seeder that published a template on the way
 * into the context, and the right answer is to switch the seeder off rather than to write tests
 * that assume a fixed set of rows nobody else controls.
 */
@Component
@ConditionalOnProperty(name = "store.themes.seed", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ThemeTemplateSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ThemeTemplateSeeder.class);

	private static final String CATALOGUE = "classpath:theme-templates/*.json";

	private final ThemeService themes;
	private final ThemeTemplateRepository templates;
	private final ThemeValidator validator;
	private final ObjectMapper json;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		List<TemplateFile> catalogue = readCatalogue();
		// sortOrder is the shelf order the gallery shows. Sorting here as well as in the query
		// keeps the startup log readable and makes "why is this one last?" answerable.
		catalogue.sort(Comparator.comparingInt(TemplateFile::sortOrder));
		for (TemplateFile file : catalogue) {
			seed(file);
		}
	}

	/** Every readable file in the catalogue, in filename order. Bad files are logged and skipped. */
	private List<TemplateFile> readCatalogue() {
		Resource[] files;
		try {
			files = new PathMatchingResourcePatternResolver().getResources(CATALOGUE);
		} catch (IOException e) {
			log.error("Could not read the theme catalogue at {}", CATALOGUE, e);
			return List.of();
		}

		List<Resource> ordered = new ArrayList<>(Arrays.asList(files));
		ordered.sort(Comparator.comparing(file -> String.valueOf(file.getFilename())));

		List<TemplateFile> parsed = new ArrayList<>();
		for (Resource file : ordered) {
			String filename = String.valueOf(file.getFilename());
			try (InputStream in = file.getInputStream()) {
				parsed.add(read(filename, json.readTree(in)));
			} catch (IOException | RuntimeException e) {
				// A file that cannot be read or does not carry the fields below is a packaging
				// mistake, not a runtime condition. Say which file, and carry on.
				log.error("Skipping theme template '{}': {}", filename, e.getMessage());
			}
		}
		return parsed;
	}

	private TemplateFile read(String filename, JsonNode root) {
		String code = required(root, "code", filename);
		String name = required(root, "name", filename);

		JsonNode shopTypeNode = root.get("shopType");
		ShopType shopType = null;
		if (shopTypeNode != null && !shopTypeNode.isNull()) {
			String raw = shopTypeNode.asText();
			shopType = ShopType.fromCode(raw);
			if (shopType == null) {
				// fromCode answers null for an unknown vertical. Seeding on that would publish a
				// template to every shop on the platform because of a typo, so treat it as a
				// broken file instead.
				throw new IllegalArgumentException("unknown shopType '" + raw + "'");
			}
		}

		JsonNode config = root.get("config");
		if (config == null || !config.isObject()) {
			throw new IllegalArgumentException("missing a 'config' object");
		}

		return new TemplateFile(
				code,
				name,
				text(root, "description"),
				text(root, "category"),
				shopType,
				root.path("sortOrder").asInt(0),
				config.toString());
	}

	private void seed(TemplateFile file) {
		var existing = templates.findByCode(file.code());
		if (existing.isPresent()) {
			refresh(file, existing.get());
			return;
		}
		try {
			ThemeTemplate template = themes.createTemplate(file.code(), file.name(),
					file.description(), file.shopType(), file.config(),
					ThemeTemplate.PriceTier.FREE, null);
			template.setCategory(file.category());
			template.setSortOrder(file.sortOrder());
			themes.publishTemplate(template.getId(), null);
			log.info("Seeded and published theme template '{}' ({})", file.code(), file.category());
		} catch (RuntimeException e) {
			// The validator rejected the document, or the code collided between two files. Neither
			// is worth stopping the boot for, but both are worth a line a developer will see.
			log.error("Could not seed theme template '{}': {}", file.code(), e.getMessage());
		}
	}

	/**
	 * Brings one existing row up to the shipped file.
	 *
	 * <p>Compared as JSON trees rather than strings: {@code jsonb} reorders keys and drops
	 * whitespace on write, so a textual comparison would report every row as stale on every boot
	 * and rewrite the whole catalogue each time. Publication state is not touched.
	 */
	private void refresh(TemplateFile file, ThemeTemplate template) {
		try {
			String config = validator.validateAndNormalise(file.config());
			JsonNode shipped = json.readTree(config);
			JsonNode stored = json.readTree(template.getConfig());

			boolean same = shipped.equals(stored)
					&& java.util.Objects.equals(template.getName(), file.name())
					&& java.util.Objects.equals(template.getDescription(), file.description())
					&& java.util.Objects.equals(template.getCategory(), file.category())
					&& java.util.Objects.equals(template.shopTypeOrNull(), file.shopType())
					&& template.getSortOrder() == file.sortOrder();
			if (same) {
				return;
			}

			template.setName(file.name());
			template.setDescription(file.description());
			template.setCategory(file.category());
			template.changeType(file.shopType());
			template.setSortOrder(file.sortOrder());
			template.setConfig(config);
			templates.save(template);
			log.info("Refreshed theme template '{}' from the shipped catalogue", file.code());
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			// A broken shipped file must not stop a boot or wipe a working row.
			log.error("Could not refresh theme template '{}': {}", file.code(), e.getMessage());
		}
	}

	private static String required(JsonNode root, String field, String filename) {
		String value = text(root, field);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(filename + " is missing '" + field + "'");
		}
		return value.trim();
	}

	private static String text(JsonNode root, String field) {
		JsonNode node = root.get(field);
		return node == null || node.isNull() ? null : node.asText();
	}

	/**
	 * One catalogue entry, already parsed.
	 *
	 * <p>{@code config} stays a JSON string rather than a tree because that is what
	 * {@link ThemeService#createTemplate} validates and stores; re-serialising a tree here would
	 * add a second place for the document's shape to drift.
	 */
	private record TemplateFile(String code, String name, String description, String category,
			ShopType shopType, int sortOrder, String config) {
	}
}
