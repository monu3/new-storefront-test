package com.storefront.store.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Push registrations.
 *
 * <p>The interesting query is {@link #tokensFor}, which is the one that runs on every order-status
 * change. It is keyed by user and filtered on {@code is_active}, so a token FCM has already
 * rejected is never sent to again.
 */
public interface CustomerDeviceRepository extends JpaRepository<CustomerDevice, String> {

	Optional<CustomerDevice> findByToken(String token);

	/** Every live token for a user: the fan-out list for a push. */
	List<CustomerDevice> findByUserIdAndActiveTrue(UUID userId);

	/**
	 * Detaches every token for a user.
	 *
	 * <p>On sign-out. <b>Modified in place rather than deleted</b>: the browser is still the same
	 * install and will register again, and a row that remembers it has been signed out is what
	 * stops a stale token being attributed to whoever signs in next on that device.
	 */
	@Modifying
	@Query("update CustomerDevice d set d.userId = null where d.userId = :userId")
	int detachAllFor(@Param("userId") UUID userId);

	/** Marks tokens dead in one statement, so a bulk deactivation is not a loop of updates. */
	@Modifying
	@Query("update CustomerDevice d set d.active = false where d.token in :tokens")
	int markDead(@Param("tokens") List<String> tokens);

	long countByUserIdAndActiveTrue(UUID userId);
}