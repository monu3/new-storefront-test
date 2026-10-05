package com.storefront.store.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Saved addresses.
 *
 * <p><b>{@link #clearDefaultFor} exists because the "one default" rule needs clearing as much as
 * setting.</b> A {@code save} that sets a new default cannot demote the old one, because it only
 * sees its own row; without this query, promoting a second address would trip the partial unique
 * index and surface to the owner as an opaque constraint violation.
 */
public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, UUID> {

	/** Default first, then newest — the order the picker shows. */
	List<CustomerAddress> findByUserIdOrderByIsDefaultDescCreatedAtDesc(UUID userId);

	Optional<CustomerAddress> findFirstByUserIdAndIsDefaultTrue(UUID userId);

	Optional<CustomerAddress> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

	long countByUserId(UUID userId);

	/**
	 * Demotes whichever address is currently the default.
	 *
	 * <p>A bulk update rather than a load-and-save loop, so promoting a default is one statement
	 * regardless of how many addresses the customer has.
	 */
	@Modifying
	@Query("update CustomerAddress a set a.isDefault = false where a.userId = :userId and a.isDefault = true")
	int clearDefaultFor(@Param("userId") UUID userId);
}