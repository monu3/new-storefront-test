package com.storefront.store.theme;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Template queries.
 *
 * <p>Every listing takes a {@code shopId} or scopes to the marketplace explicitly. A template
 * list is public data — its config, name and preview — so there is nothing tenant-specific to
 * leak, but the shop-scoped methods still name the shop so a filter cannot be forgotten.
 */
public interface ThemeTemplateRepository extends JpaRepository<ThemeTemplate, UUID> {

	Optional<ThemeTemplate> findByCode(String code);

	boolean existsByCode(String code);

	/** The marketplace: published and active, in the order an admin chose. */
	Page<ThemeTemplate> findByActiveTrueAndPublishedAtIsNotNullOrderBySortOrderAscNameAsc(
			Pageable pageable);

	/**
	 * Templates offered to one shop.
	 *
	 * <p>Either tagged with the shop's type, or tagged with nothing at all — a generic template.
	 * Both are fetched and filtered in memory, because the "untagged" half of the OR has no index
	 * and the catalogue is tens of rows, not thousands. Written as one query so the two halves
	 * cannot disagree about which is published.
	 */
	List<ThemeTemplate> findByActiveTrueAndPublishedAtIsNotNullOrderBySortOrderAscNameAsc();

	/** Admin's own list: drafts included. */
	Page<ThemeTemplate> findByOrderBySortOrderAscNameAsc(Pageable pageable);

	Page<ThemeTemplate> findByShopTypeOrderBySortOrderAscNameAsc(String shopType, Pageable pageable);

	long countByActiveTrueAndPublishedAtIsNotNull();

	long countByShopTypeAndActiveTrueAndPublishedAtIsNotNull(String shopType);

}
