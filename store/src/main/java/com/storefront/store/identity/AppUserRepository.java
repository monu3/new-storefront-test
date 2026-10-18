package com.storefront.store.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** User lookups keyed on the Keycloak subject, which is the natural identity (D3). */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	Optional<AppUser> findByKeycloakSub(String keycloakSub);

	Optional<AppUser> findByEmail(String email);

	boolean existsByKeycloakSub(String keycloakSub);

	/**
	 * The admin's people list, across both realms (Phase 7).
	 *
	 * <p><b>One query over both realms, with nulls meaning "all".</b> An admin's questions cross the
	 * realm line constantly — "this person cannot log in to their shop" is an {@code ops} account,
	 * "this customer is spamming orders" is a {@code customers} one — and a screen that shows only
	 * one makes them search the other by hand and guess.
	 *
	 * @param realm  null for both realms
	 * @param status null for every status
	 * @param search null or blank for no filter, matched on email and full name
	 */
	//
	// NATIVE, for the same reason as ShopRepository.searchAdmin: `realm` carries a converter and
	// `:realm` binds as a String, which JPQL refuses to validate. `slug`/`email` are citext, hence
	// the `::text` casts so `lower()` has something it can lower.
	//
	@Query(value = """
			SELECT u.* FROM app_user u
			WHERE (:realm IS NULL OR u.realm = :realm)
			  AND (:status IS NULL OR u.status = :status)
			  AND (:search IS NULL
			       OR lower(u.email::text) LIKE lower('%' || :search || '%')
			       OR lower(coalesce(u.full_name, '')) LIKE lower('%' || :search || '%'))
			ORDER BY u.created_at DESC
			""", nativeQuery = true)
	Page<AppUser> searchAdmin(@Param("realm") String realm,
			@Param("status") String status,
			@Param("search") String search,
			Pageable pageable);

	/** Counts by realm and status, for the admin overview. */
	@Query("select u.realm, u.status, count(u.id) from AppUser u group by u.realm, u.status")
	List<Object[]> countByRealmAndStatus();
}
