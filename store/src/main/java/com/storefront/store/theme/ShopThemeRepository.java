package com.storefront.store.theme;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A shop's themes.
 *
 * <p>Every method takes a {@code shopId}: a shop must never see or edit another shop's theme,
 * and naming the shop at every call site makes that structural rather than a check to remember.
 */
public interface ShopThemeRepository extends JpaRepository<ShopTheme, UUID> {

	/** The live theme, if the shop has one. What the storefront renders. */
	Optional<ShopTheme> findByShopIdAndPublishedTrue(UUID shopId);

	Optional<ShopTheme> findByIdAndShopId(UUID id, UUID shopId);

	List<ShopTheme> findByShopIdOrderByUpdatedAtDesc(UUID shopId);

	Page<ShopTheme> findByShopIdOrderByUpdatedAtDesc(UUID shopId, Pageable pageable);

	List<ShopTheme> findByShopIdAndPublishedFalseOrderByUpdatedAtDesc(UUID shopId);

	long countByShopId(UUID shopId);

	long countByShopIdAndPublishedTrue(UUID shopId);

	/** Templates a shop has built on, for showing "based on X" in the editor. */
	List<ShopTheme> findByShopIdAndTemplateId(UUID shopId, UUID templateId);
}
