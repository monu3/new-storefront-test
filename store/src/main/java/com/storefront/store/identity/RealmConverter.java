package com.storefront.store.identity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link AppUser.Realm} using the lowercase realm name.
 *
 * <p>Two different spellings of the same thing wanted to exist: the Java constant is
 * {@code OPS}, while the Keycloak URL, the {@code iss} claim and the
 * {@code app_user_realm_check} constraint all say {@code ops}. A converter resolves that in one
 * place instead of scattering a {@code toLowerCase()} through every query that touches the
 * column.
 *
 * <p>An {@code AttributeConverter} is the right tool here rather than a {@code @PostLoad} fixup
 * or a hand-written {@code UserType}: it applies to both reads and writes, so the Java side
 * never has to know the stored spelling.
 */
@Converter(autoApply = false)
public class RealmConverter implements AttributeConverter<AppUser.Realm, String> {

	@Override
	public String convertToDatabaseColumn(AppUser.Realm realm) {
		return realm == null ? null : realm.wireValue();
	}

	@Override
	public AppUser.Realm convertToEntityAttribute(String value) {
		return AppUser.Realm.fromWire(value);
	}
}
