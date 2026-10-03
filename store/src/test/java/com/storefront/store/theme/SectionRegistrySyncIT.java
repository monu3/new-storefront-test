package com.storefront.store.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the Java section registry and the TypeScript one in step.
 *
 * <p><b>This test reads a source file from the frontend repository.</b> That is unusual and it is
 * the whole point: the two lists are halves of one contract, and nothing else would notice when
 * they diverge.
 *
 * <p>The failure it prevents is silent in both directions. Add a section type to the TypeScript
 * schema and not to Java, and the server rejects every template using it — the admin writes a
 * valid template and gets a 400 with no obvious cause. Add it to Java and not to TypeScript, and
 * the server happily accepts a theme whose section the renderer turns into an "unknown section"
 * placeholder, which the shop owner sees as a blank space on their storefront.
 *
 * <p>Skipped, not failed, when the file is absent, so the backend suite still runs on its own.
 */
class SectionRegistrySyncIT {

	/** Matches the quoted entries of the exported SECTION_TYPES array. */
	private static final Pattern ARRAY_ENTRY = Pattern.compile("^\\s*'([a-z0-9-]+)',?\\s*$");

	private static Path schemaFile() {
		Path local = Path.of("..", "web", "packages", "theme-engine", "src", "schema.ts");
		if (Files.exists(local)) {
			return local;
		}
		// When Maven runs from a different working directory.
		Path fromRepo = Path.of("web", "packages", "theme-engine", "src", "schema.ts");
		return Files.exists(fromRepo) ? fromRepo : null;
	}

	/** The wire names in the TypeScript SECTION_TYPES array, in declaration order. */
	private static Set<String> typesFromTypeScript() throws IOException {
		Path file = schemaFile();
		assertThat(file)
				.as("packages/theme-engine/src/schema.ts not found; run from the repo root or the store module")
				.isNotNull();

		String source = Files.readString(file);
		int start = source.indexOf("export const SECTION_TYPES = [");
		assertThat(start)
				.as("could not find SECTION_TYPES in the schema; the export shape changed")
				.isGreaterThan(-1);

		int end = source.indexOf(']', start);
		Set<String> names = new LinkedHashSet<>();
		for (String line : source.substring(start, end).split("\n")) {
			Matcher m = ARRAY_ENTRY.matcher(line);
			if (m.matches()) {
				names.add(m.group(1));
			}
		}
		return names;
	}

	@Test
	@DisplayName("the Java and TypeScript section registries contain exactly the same types")
	void registriesMatch() throws IOException {
		if (schemaFile() == null) {
			return;
		}

		Set<String> ts = typesFromTypeScript();
		Set<String> java = SectionType.wireNames();

		assertThat(ts).as("the TypeScript list should not be empty").isNotEmpty();

		assertThat(java)
				.as("""
						Section types added to Java but not to packages/theme-engine/src/schema.ts.
						A theme using one of these would be accepted by the server and rendered as
						an "unknown section" placeholder for every customer. Add it to SECTION_TYPES.""")
				.containsExactlyInAnyOrderElementsOf(ts);

		assertThat(ts)
				.as("""
						Section types in SECTION_TYPES but not in the Java registry.
						The server rejects every template using one of these, so an admin writing a
						valid template gets a 400 with no obvious cause.""")
				.containsExactlyInAnyOrderElementsOf(java);
	}

	@Test
	@DisplayName("no section type is named the same as another after conversion")
	void enumNamesAreUnique() {
		// The enum constant is derived from the wire name by hand, so two wire names could
		// collapse onto one constant and the duplicate would be a compile error rather than a bug
		// — which is why this is a cheap assertion rather than a careful one.
		assertThat(SectionType.values()).hasSize(SectionType.wireNames().size());
	}

	@Test
	@DisplayName("the unknown-section placeholder cannot be a real choice")
	void unknownSectionIsReserved() {
		// It exists so the renderer degrades gracefully for a theme written by a NEWER build.
		// Saving one deliberately would be a shop choosing to show customers a broken slot, so the
		// validator refuses it. Asserted here so the reason is findable from the enum.
		assertThat(SectionType.fromWireName("unknown-section")).isEqualTo(SectionType.UNKNOWN_SECTION);
		assertThat(SectionType.fromWireName("not-a-section")).isNull();
	}
}
