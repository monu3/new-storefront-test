package com.storefront.store.checkout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Discount queries.
 *
 * <p>Every lookup takes a {@code shopId}, so one shop's code cannot be redeemed at another.
 * That would be an obvious hole in a coupon system and is closed structurally rather than by a
 * check someone might forget.
 */
public interface DiscountRepository extends JpaRepository<Discount, UUID> {

	/** Case-insensitive by virtue of the citext column. */
	Optional<Discount> findByShopIdAndCodeIgnoreCase(UUID shopId, String code);

	List<Discount> findByShopIdAndActiveTrueOrderByIdAsc(UUID shopId);

	/**
	 * Every discount for the owner's console, paged.
	 *
	 * <p>Includes inactive ones: a paused campaign is something an owner needs to see and re-enable,
	 * and a list that silently hid it would leave them wondering whether it was deleted.
	 */
	Page<Discount> findAllByShopIdOrderByIdAsc(UUID shopId, Pageable pageable);

	/** Automatic discounts: no code, so they apply without the customer doing anything. */
	List<Discount> findByShopIdAndActiveTrueAndCodeIsNullOrderByIdAsc(UUID shopId);

	long countByShopIdAndActiveTrue(UUID shopId);
}
