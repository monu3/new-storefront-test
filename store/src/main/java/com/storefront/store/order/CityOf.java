package com.storefront.store.order;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a few fields out of the address snapshot JSON.
 *
 * <p>The address is stored as a single {@code jsonb} snapshot rather than as columns, because
 * its shape varies by country and is snapshotted wholesale — an edited customer address must not
 * rewrite where a shipped parcel went.
 *
 * <p>The consequence is that a list view, which wants only the city and the recipient's name,
 * would otherwise have to parse every address in the page. That parsing lives here so it happens
 * in one place and fails softly: a malformed address yields an empty city rather than a 500 that
 * takes down the whole order board. The full address is still shown on the detail view, where the
 * raw JSON is exactly what should be displayed.
 */
public final class CityOf {

	private CityOf() {
	}

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	/** The delivery city, or null when the address is absent or unparseable. */
	public static String addressCity(String addressJson) {
		return read(addressJson, "city");
	}

	/** The recipient's name, or null. */
	public static String name(String addressJson) {
		return read(addressJson, "name");
	}

	public static String phone(String addressJson) {
		return read(addressJson, "phone");
	}

	/**
	 * A display line for the board: "Ravi KC" or "Ravi KC, Kathmandu".
	 */
	public static String label(String addressJson) {
		String name = name(addressJson);
		String city = addressCity(addressJson);
		if (name == null) {
			return city;
		}
		return city == null ? name : name + ", " + city;
	}

	private static String read(String addressJson, String field) {
		if (addressJson == null || addressJson.isBlank()) {
			return null;
		}
		try {
			Map<String, Object> address = JSON.readValue(addressJson, MAP);
			Object value = address.get(field);
			return value == null ? null : String.valueOf(value);
		} catch (Exception e) {
			// Deliberately soft. A malformed address must not break the order list; the detail
			// view shows the raw JSON, so the data is still reachable for diagnosis.
			return null;
		}
	}
}
