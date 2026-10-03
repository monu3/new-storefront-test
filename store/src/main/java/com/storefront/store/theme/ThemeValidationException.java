package com.storefront.store.theme;

import java.util.List;

/**
 * A theme document that does not satisfy the schema.
 *
 * <p>Carries every problem, not just the first. A shop owner fixing a theme through a form needs
 * to see all of it at once; a validator that stops at the first failure turns five corrections into
 * five round trips.
 *
 * <p>Maps to HTTP 400 with the list, so the editor can attach each message to a field.
 */
public class ThemeValidationException extends RuntimeException {

	private final List<String> issues;

	public ThemeValidationException(List<String> issues) {
		super(summary(issues));
		this.issues = List.copyOf(issues);
	}

	public ThemeValidationException(String issue) {
		this(List.of(issue));
	}

	/** Every problem found, each naming its path. */
	public List<String> issues() {
		return issues;
	}

	private static String summary(List<String> issues) {
		if (issues.isEmpty()) {
			return "The theme is not valid";
		}
		if (issues.size() == 1) {
			return "The theme is not valid: " + issues.get(0);
		}
		return "The theme is not valid: " + issues.size() + " problems, starting with "
				+ issues.get(0);
	}
}
