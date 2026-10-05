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
 * A browser or app install that can receive push (Phase 5).
 *
 * <p><b>Keyed by the FCM token, not by user.</b> A token identifies an <em>install</em>, not a
 * person: it is regenerated when the customer clears site data, it is replaced when they sign out
 * and back in on the same browser, and an install cannot be "logged out". Modelling it as a row per
 * device with a nullable user — which is exactly what this table is — is what lets a customer
 * register a token at page load before they have signed in, and then have it attach to their
 * account later.
 *
 * <p>That is also why there is no surrogate id. Two rows for one token would send every
 * notification twice, and the token is already unique.
 *
 * <p><b>User ids are cross-realm.</b> A token may belong to a customer (<em>or</em> a shop owner
 * browsing their own storefront), so this is deliberately not foreign-keyed to a customers table:
 * {@code app_user} holds both realms and the reference points there.
 */
@Entity
@Table(name = "customer_device")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerDevice extends BaseEntity {

	@Id
	@Column(name = "token", nullable = false, updatable = false)
	private String token;

	/** Null until the owner of this browser signs in. */
	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "platform", nullable = false)
	private String platform = "web";

	/**
	 * Cleared when FCM reports the token is dead.
	 *
	 * <p>Not deleted, because the same browser may register again and a row that knows it has been
	 * superseded is worth keeping. Cleared rather than active so a dead token stops counting
	 * towards "is this notification reaching anyone".
	 */
	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	public CustomerDevice(String token, String platform) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("A device needs a push token");
		}
		this.token = token.trim();
		this.platform = platform == null || platform.isBlank() ? "web" : platform.trim();
		this.active = true;
	}

	/** Attaches this token to an account, or detaches it on sign-out. */
	public void attachTo(UUID user) {
		this.userId = user;
	}

	/** Records that FCM rejected the token, so it is not tried again. */
	public void markDead() {
		this.active = false;
	}

	public boolean belongsTo(UUID user) {
		return user != null && user.equals(userId);
	}

	/** When this token was last registered, for the "stale device" question. */
	public Instant lastSeenAt() {
		return getUpdatedAt();
	}
}