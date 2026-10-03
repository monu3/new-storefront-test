package com.storefront.store.identity;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A person, mirrored from Keycloak.
 *
 * <p>Keycloak owns authentication; this holds the application profile. {@link #realm} records
 * which of the two realms issued the token, so a customer row can never be used to reach a shop
 * endpoint and vice versa (D3).
 *
 * <p>The row is created on first authenticated request. There is no registration endpoint here —
 * a user comes into existence by logging in through Keycloak.
 *
 * <p>Lombok supplies the accessors; {@code status} has no public setter on purpose, because
 * suspension is an administrative act that belongs in an explicit method, not in a field anyone
 * can write.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser extends BaseEntity {

	/**
	 * Which Keycloak realm this user belongs to.
	 *
	 * <p>Persisted as the lowercase realm name, matching both the Keycloak URL, the {@code iss}
	 * claim and the {@code app_user_realm_check} constraint. {@link RealmConverter} handles the
	 * difference so the Java side never has to know the stored spelling.
	 */
	public enum Realm {
		/** Holds ADMIN and SHOP. */
		OPS("ops"),
		/** Holds CUSTOMER. */
		CUSTOMERS("customers");

		private final String wireValue;

		Realm(String wireValue) {
			this.wireValue = wireValue;
		}

		public String wireValue() {
			return wireValue;
		}

		/** Reads a realm name from the database or a token issuer. */
		public static Realm fromWire(String value) {
			if (value == null) {
				return null;
			}
			for (Realm realm : values()) {
				if (realm.wireValue.equalsIgnoreCase(value)) {
					return realm;
				}
			}
			return null;
		}
	}

	public enum Status {
		ACTIVE, SUSPENDED, DELETED
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Convert(converter = RealmConverter.class)
	@Column(name = "realm", nullable = false)
	private Realm realm;

	/** The JWT {@code sub}. Unique across both realms, so it is the natural identity. */
	@Column(name = "keycloak_sub", nullable = false, unique = true)
	private String keycloakSub;

	// citext: case-insensitive, so "A@B.COM" and "a@b.com" are the same account. Without it
	// every login path has to remember LOWER(), and the one that forgets creates a duplicate.
	@Column(name = "email", nullable = false, columnDefinition = "citext")
	private String email;

	@Column(name = "full_name")
	private String fullName;

	@Column(name = "phone")
	private String phone;

	@Column(name = "avatar_url")
	private String avatarUrl;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.ACTIVE;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	public AppUser(Realm realm, String keycloakSub, String email, String fullName) {
		this.id = UUID.randomUUID();
		this.realm = realm;
		this.keycloakSub = keycloakSub;
		this.email = email;
		this.fullName = fullName;
		this.status = Status.ACTIVE;
	}

	/** Called on every authenticated request so an admin can see who is actually active. */
	public void recordLogin() {
		this.lastLoginAt = Instant.now();
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	/** Administrative suspension. Explicit rather than a setter, so it is always deliberate. */
	public void suspend() {
		this.status = Status.SUSPENDED;
	}
}
