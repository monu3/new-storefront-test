package com.storefront.store.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link ProductCategory} as its enum name.
 *
 * <p>Mirrors {@link ShopTypeConverter} on purpose. Both categories are hardcoded enums stored
 * in a plain {@code text} column (D18), so there is no table to join to and no foreign key to
 * keep consistent — the stored form is simply the enum constant name.
 *
 * <p>The converter is what lets {@link ProductCategory#fromCode(String)} be the single place
 * that knows what a valid stored code looks like, so an unknown value in the database surfaces
 * as one clear failure rather than as a null category that quietly reads as "no category".
 */
@Converter(autoApply = false)
public class ProductCategoryConverter implements AttributeConverter<ProductCategory, String> {

	@Override
	public String convertToDatabaseColumn(ProductCategory category) {
		return category == null ? null : category.name();
	}

	@Override
	public ProductCategory convertToEntityAttribute(String value) {
		return value == null ? null : ProductCategory.fromCode(value);
	}
}
