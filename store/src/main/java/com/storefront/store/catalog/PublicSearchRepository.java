package com.storefront.store.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Search across <em>every</em> shop, for customers.
 *
 * <p>The counterpart to {@link ProductRepository}'s shop-scoped search. An owner searching their own
 * catalogue wants their own products; a customer searching "paracetamol" wants every pharmacy that
 * sells it, across shops they have never visited. Different questions, different indexes, and a
 * shop-scoped query is not a cross-shop query with the shop clause removed.
 *
 * <p>The ranking is deliberately biased towards <b>shops</b> rather than towards individual
 * products. A customer asking for paracetamol wants to find a pharmacy, not forty competing
 * listings, so products are grouped by shop and the shop's best match is what ranks.
 */
public interface PublicSearchRepository {

	/**
	 * Full-text plus trigram search across all live shops.
	 *
	 * <p>Only {@code ACTIVE} products in {@code VERIFIED} shops. A product that has been archived
	 * must not be findable, and an unverified shop's products must not be either — the second is
	 * the one that matters for the rule that a shop is invisible until it is approved.
	 *
	 * <p>The SKU clause is exact-ish, for the reason given on
	 * {@code ProductRepository}: every SKU shares a prefix, so their trigrams overlap and a fuzzy
	 * match would return the entire catalogue.
	 *
	 * <p>Native because {@code websearch_to_tsquery} and {@code similarity()} have no JPQL spelling.
	 */
	@Query(value = """
			SELECT p.* FROM product p
			JOIN shop s ON s.id = p.shop_id
			WHERE p.deleted_at IS NULL
			  AND p.status = 'ACTIVE'
			  AND s.status = 'VERIFIED'
			  AND (
			        p.search_document @@ websearch_to_tsquery('simple', :term)
			        OR p.name ILIKE '%' || :term || '%'
			        OR p.sku_search_key ILIKE '%' || regexp_replace(lower(:term), '[^a-z0-9]', '', 'g') || '%'
			        OR similarity(p.name, :term) > 0.3
			      )
			ORDER BY
			      CASE WHEN p.search_document @@ websearch_to_tsquery('simple', :term) THEN 0 ELSE 1 END,
			      ts_rank(p.search_document, websearch_to_tsquery('simple', :term)) DESC,
			      similarity(p.name, :term) DESC,
			      p.sold_count DESC NULLS LAST,
			      p.name ASC
			""",
			countQuery = """
			SELECT count(*) FROM product p
			JOIN shop s ON s.id = p.shop_id
			WHERE p.deleted_at IS NULL
			  AND p.status = 'ACTIVE'
			  AND s.status = 'VERIFIED'
			  AND (
			        p.search_document @@ websearch_to_tsquery('simple', :term)
			        OR p.name ILIKE '%' || :term || '%'
			        OR p.sku_search_key ILIKE '%' || regexp_replace(lower(:term), '[^a-z0-9]', '', 'g') || '%'
			        OR similarity(p.name, :term) > 0.3
			      )
			""",
			nativeQuery = true)
	Page<Product> search(@Param("term") String term, Pageable pageable);

	/**
	 * Search restricted to one shop type.
	 *
	 * <p>The D17 payoff: a customer looking for medicine gets every pharmacy's products without
	 * having to know that pharmacies exist as a category, and a customer looking for a pharmacy
	 * gets every pharmacy without browsing one at a time.
	 */
	@Query(value = """
			SELECT p.* FROM product p
			JOIN shop s ON s.id = p.shop_id
			WHERE p.deleted_at IS NULL
			  AND p.status = 'ACTIVE'
			  AND s.status = 'VERIFIED'
			  AND s.shop_type = :shopType
			  AND (
			        p.search_document @@ websearch_to_tsquery('simple', :term)
			        OR p.name ILIKE '%' || :term || '%'
			        OR p.sku_search_key ILIKE '%' || regexp_replace(lower(:term), '[^a-z0-9]', '', 'g') || '%'
			        OR similarity(p.name, :term) > 0.3
			      )
			ORDER BY
			      CASE WHEN p.search_document @@ websearch_to_tsquery('simple', :term) THEN 0 ELSE 1 END,
			      ts_rank(p.search_document, websearch_to_tsquery('simple', :term)) DESC,
			      similarity(p.name, :term) DESC,
			      p.sold_count DESC NULLS LAST,
			      p.name ASC
			""",
			countQuery = """
			SELECT count(*) FROM product p
			JOIN shop s ON s.id = p.shop_id
			WHERE p.deleted_at IS NULL
			  AND p.status = 'ACTIVE'
			  AND s.status = 'VERIFIED'
			  AND s.shop_type = :shopType
			  AND (
			        p.search_document @@ websearch_to_tsquery('simple', :term)
			        OR p.name ILIKE '%' || :term || '%'
			        OR p.sku_search_key ILIKE '%' || regexp_replace(lower(:term), '[^a-z0-9]', '', 'g') || '%'
			        OR similarity(p.name, :term) > 0.3
			      )
			""",
			nativeQuery = true)
	Page<Product> searchInType(@Param("term") String term,
			@Param("shopType") String shopType, Pageable pageable);

	/**
	 * The shops that have a matching product, with how many matches each has.
	 *
	 * <p>This is what makes search a shop-finder as well as a product-finder: the D17 payoff is a
	 * customer finding all pharmacies, not a list of forty products from one pharmacy.
	 *
	 * <p><b>An interface projection, not a record.</b> A {@code select new SomeRecord(...)} over a
	 * native query binds positionally by exact JDBC type, and a {@code uuid} column into a
	 * {@code UUID} component fails at runtime with "argument type mismatch" — a message that points
	 * at neither the column nor the record. An interface projection maps by column alias and lets
	 * Hibernate convert, which is what a hand-written aggregate query needs.
	 */
	@Query(value = """
			SELECT s.id AS shopId, s.slug::text AS slug, s.name AS name,
			       s.shop_type::text AS shopType, count(p.id) AS matches
			FROM shop s
			JOIN product p ON p.shop_id = s.id
			WHERE s.status = 'VERIFIED'
			  AND p.deleted_at IS NULL
			  AND p.status = 'ACTIVE'
			  AND (
			        p.search_document @@ websearch_to_tsquery('simple', :term)
			        OR p.name ILIKE '%' || :term || '%'
			        OR p.sku_search_key ILIKE '%' || regexp_replace(lower(:term), '[^a-z0-9]', '', 'g') || '%'
			        OR similarity(p.name, :term) > 0.3
			      )
			GROUP BY s.id, s.slug, s.name, s.shop_type
			ORDER BY matches DESC, s.name ASC
			LIMIT :limit
			""",
			nativeQuery = true)
	List<ShopMatchView> shopsMatching(@Param("term") String term, @Param("limit") int limit);

	/**
	 * One shop's share of a search result.
	 *
	 * <p>Column names must match the aliases above exactly; Spring Data maps by alias here rather
	 * than by position, which is the whole reason this is an interface.
	 */
	interface ShopMatchView {
		UUID getShopId();

		String getSlug();

		String getName();

		String getShopType();

		long getMatches();
	}
}
