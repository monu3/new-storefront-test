package com.storefront.store.security;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.identity.AppUser;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;

/**
 * Answers "is this caller allowed to act for this shop?".
 *
 * <p>Referenced from {@code @PreAuthorize} expressions, which is why it is a bean rather than a
 * static helper — Spring Security's expression evaluator resolves {@code @tenantGuard} against
 * the application context.
 *
 * <p>Deliberately re-reads Postgres instead of trusting a claim. The token says who the caller
 * is; only the database knows whose shop it is, and a claim could not be changed without a
 * Keycloak round trip. The read is cheap and cached in the per-request {@code TenantContext}.
 */
@Component("tenantGuard")
@RequiredArgsConstructor
public class TenantGuard {

	private final ShopRepository shops;
	private final CurrentUser currentUser;

	/**
	 * True when the caller owns the given shop.
	 *
	 * <p>Used in {@code @PreAuthorize("@tenantGuard.owns(#shopId)")} on every shop-scoped
	 * endpoint. A false result is a 403, not a 404: the shop exists, the caller simply may not
	 * see it, and pretending otherwise would be a lie the client could disprove.
	 */
	@Transactional(readOnly = true)
	public boolean owns(UUID shopId) {
		AppUser caller = currentUser.optionalUser();
		if (caller == null || shopId == null) {
			return false;
		}
		return shops.findById(shopId)
				.map(shop -> shop.getOwnerUserId().equals(caller.getId()))
				.orElse(false);
	}

	/**
	 * The shop the caller owns.
	 *
	 * @throws AccessDeniedException when they do not own one, or have no shop yet
	 */
	@Transactional(readOnly = true)
	public Shop requireOwnedShop() {
		AppUser caller = currentUser.require();
		return shops.findByOwnerUserId(caller.getId())
				.orElseThrow(() -> new AccessDeniedException(
						"This account does not own a shop. Complete shop signup first."));
	}

	/** The caller's shop id, or null. For queries that scope by shop_id. */
	@Transactional(readOnly = true)
	public UUID ownedShopId() {
		AppUser caller = currentUser.optionalUser();
		if (caller == null) {
			return null;
		}
		return shops.findByOwnerUserId(caller.getId()).map(Shop::getId).orElse(null);
	}

	/** True when the caller is a platform admin, who is not owned by any shop. */
	/**
	 * The signed-in account's id, for recording who did something.
	 *
	 * <p>Deliberately separate from {@link #requireOwnedShop()}: that one is a <em>shop</em> id and
	 * this one is an <em>account</em> id, and passing one where the other belongs produces an empty
	 * result rather than an error. An audit trail and a tenant filter are exactly the two places
	 * that distinction matters most, so it is worth a method rather than a comment.
	 */
	public UUID currentUserId() {
		AppUser caller = currentUser.optionalUser();
		if (caller == null) {
			throw new IllegalStateException("No signed-in account on this request");
		}
		return caller.getId();
	}

	/**
	 * The signed-in customer's account id, for the customer-facing endpoints.
	 *
	 * <p>Same id as {@link #currentUserId()}; named separately so a call site reads as what it
	 * means. A customer endpoint that reached for a shop id would be a different bug entirely.
	 */
	public UUID requireCustomerId() {
		return currentUserId();
	}

	public boolean isAdmin() {
		return currentUser.hasRole("ROLE_ADMIN");
	}
}
