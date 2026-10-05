package com.storefront.store.customer;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A saved delivery address (Phase 5).
 *
 * <p><b>Free text, not normalised.</b> A customer's own wording — "Koteshwor, Ward 32" — is more
 * useful to a rider than a normalised country/state/city triple is, and this platform ships in one
 * country where that is not worth the loss.
 *
 * <p><b>Never joined to an order.</b> An order snapshots its delivery address as frozen jsonb, so
 * editing or deleting this row cannot rewrite where a parcel was sent. That is the whole reason
 * the duplication exists.
 */
@Entity
@Table(name = "customer_address")
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerAddress extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	/** "Home", "Office" — the customer's own name for it, not a type enum. */
	@Column(name = "label", nullable = false)
	private String label;

	@Column(name = "recipient", nullable = false)
	private String recipient;

	@Column(name = "phone", nullable = false)
	private String phone;

	@Column(name = "line1", nullable = false)
	private String line1;

	@Column(name = "line2")
	private String line2;

	@Column(name = "city", nullable = false)
	private String city;

	@Column(name = "state")
	private String state;

	@Column(name = "postal_code")
	private String postalCode;

	@Column(name = "country", nullable = false)
	private String country = "Nepal";

	/** At most one per customer; enforced by a partial unique index, not by this flag alone. */
	@Column(name = "is_default", nullable = false)
	private boolean isDefault;

	public CustomerAddress(UUID userId, String label, String recipient, String phone,
			String line1, String line2, String city, String state,
			String postalCode, String country) {

		if (userId == null) {
			throw new IllegalArgumentException("An address needs a userId");
		}
		require(label, "label");
		require(recipient, "recipient");
		require(phone, "phone");
		require(line1, "line1");
		require(city, "city");

		this.id = UUID.randomUUID();
		this.userId = userId;
		this.label = label.trim();
		this.recipient = recipient.trim();
		this.phone = phone.trim();
		this.line1 = line1.trim();
		this.line2 = blankToNull(line2);
		this.city = city.trim();
		this.state = blankToNull(state);
		this.postalCode = blankToNull(postalCode);
		this.country = country == null || country.isBlank() ? "Nepal" : country.trim();
		this.isDefault = false;
	}

	/** One line, for the checkout picker and the "deliver to" summary. */
	public String oneLine() {
		return java.util.stream.Stream.of(line1, line2, city, state, postalCode, country)
				.filter(part -> part != null && !part.isBlank())
				.reduce((a, b) -> a + ", " + b)
				.orElse("");
	}

	public boolean belongsTo(UUID user) {
		return user != null && user.equals(userId);
	}

	private static void require(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("An address needs a " + field);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}