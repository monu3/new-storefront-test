package com.storefront.store.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.audit.AuditAction;
import com.storefront.store.audit.AuditTrail;
import com.storefront.store.identity.AppUser;

import lombok.RequiredArgsConstructor;

/**
 * Admin user management, cross-realm (Phase 7).
 *
 * <p><b>"Cross-realm" means one list, two realms, and it is not cosmetic.</b> {@code ops} holds
 * admins and shop owners; {@code customers} holds everybody else. They are separate Keycloak realms
 * with separate clients and separate token audiences, and every other surface in this platform shows
 * exactly one of them.
 *
 * <p>An admin's actual questions cross that line constantly: "this person says they cannot log in to
 * their shop" may be an {@code ops} account; "this customer is spamming orders" is a
 * {@code customers} account. A screen that shows only one realm makes them search the other by hand
 * and guess.
 *
 * <p><b>Suspending here does not delete anything in Keycloak.</b> It sets {@code app_user.status},
 * which this platform checks on every request. Keycloak is the identity provider and remains the
 * system of record for authentication; a suspension here is a platform decision, and conflating the
 * two would make "we banned them from the marketplace" indistinguishable from "they cannot log in
 * anywhere".
 */
@Service
@RequiredArgsConstructor
public class UserAdministrationService {

	private static final int MAX_PAGE_SIZE = 100;

	private final com.storefront.store.identity.AppUserRepository users;
	private final AuditTrail audit;

	/**
	 * Every account in both realms, filtered.
	 *
	 * @param realm  null for both realms. Passing one is how an admin narrows to "just customers"
	 * @param status null for every status
	 * @param search null or blank for no filter, matched on email and name
	 */
	@Transactional(readOnly = true)
	public Page<AppUser> browse(AppUser.Realm realm, AppUser.Status status, String search,
			Pageable pageable) {

		Pageable safe = pageable == null
				? org.springframework.data.domain.PageRequest.of(0, 25)
				: (pageable.getPageSize() > MAX_PAGE_SIZE
						? org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE)
						: pageable);

		return users.searchAdmin(
				realm == null ? null : realm.name(),
				status == null ? null : status.name(),
				search == null || search.isBlank() ? null : search.trim(),
				safe);
	}

	/**
	 * Suspends an account.
	 *
	 * <p>Refuses to suspend an admin, because an admin who can suspend any admin can lock the
	 * platform's operators out of it and there is no recovery path from inside the platform. The
	 * rule is checked here rather than in the controller so that every caller inherits it.
	 */
	@Transactional
	public AppUser suspend(UUID userId, String reason) {
		AuditTrail.requireReason(AuditAction.USER_SUSPEND, reason);

		AppUser user = require(userId);

		if (user.getRealm() == AppUser.Realm.OPS && isAdmin(user)) {
			throw new IllegalArgumentException(
					"That account is a platform admin. Admins cannot be suspended from the console — "
							+ "otherwise any admin could lock every operator out of the platform, and "
							+ "there is no recovery path from inside it.");
		}
		if (user.getStatus() == AppUser.Status.SUSPENDED) {
			return user;
		}

		AppUser.Status before = user.getStatus();
		user.suspend();
		AppUser saved = users.saveAndFlush(user);

		audit.record(AuditAction.USER_SUSPEND, "AppUser", saved.getId(),
				"Suspended " + saved.getRealm() + " account " + saved.getEmail(),
				reason,
				AuditTrail.field("status", before),
				AuditTrail.field("status", saved.getStatus()));

		return saved;
	}

	/** Puts an account back. Idempotent. */
	@Transactional
	public AppUser reinstate(UUID userId) {
		AppUser user = require(userId);

		if (user.getStatus() == AppUser.Status.ACTIVE) {
			return user;
		}

		AppUser.Status before = user.getStatus();
		user.reinstate();
		AppUser saved = users.saveAndFlush(user);

		audit.record(AuditAction.USER_REINSTATE, "AppUser", saved.getId(),
				"Reinstated " + saved.getRealm() + " account " + saved.getEmail(),
				null,
				AuditTrail.field("status", before),
				AuditTrail.field("status", saved.getStatus()));

		return saved;
	}

	/** Counts by realm and status, for the admin's overview. */
	@Transactional(readOnly = true)
	public List<Object[]> counts() {
		return users.countByRealmAndStatus();
	}

	private AppUser require(UUID userId) {
		return users.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("No account " + userId));
	}

	/**
	 * Whether an account holds an admin role.
	 *
	 * <p>Read from the roles stamped at last sign-in rather than asked of Keycloak, because this
	 * platform has to be able to answer it with the identity provider unreachable — a guard that
	 * fails when Keycloak is down is a guard that fails exactly when it is needed.
	 */
	private boolean isAdmin(AppUser user) {
		return user.isAdmin();
	}
}