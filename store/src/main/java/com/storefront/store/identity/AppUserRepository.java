package com.storefront.store.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** User lookups keyed on the Keycloak subject, which is the natural identity (D3). */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	Optional<AppUser> findByKeycloakSub(String keycloakSub);

	Optional<AppUser> findByEmail(String email);

	boolean existsByKeycloakSub(String keycloakSub);
}
