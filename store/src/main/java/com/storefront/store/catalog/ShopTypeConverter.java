package com.storefront.store.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link ShopType} as its enum name.
 *
 * <p>The column is a plain {@code varchar} with a CHECK that the value is uppercase, which is
 * exactly {@code ShopType.name()}. That is why a converter is not strictly needed — but using
 * one here means the Java side never carries a raw String around, and a future change to the
 * stored form is a one-line edit rather than a search.
 */
@Converter(autoApply = false)
public class ShopTypeConverter implements AttributeConverter<ShopType, String> {

	@Override
	public String convertToDatabaseColumn(ShopType type) {
		return type == null ? null : type.name();
	}

	@Override
	public ShopType convertToEntityAttribute(String value) {
		return ShopType.fromCode(value);
	}
}
