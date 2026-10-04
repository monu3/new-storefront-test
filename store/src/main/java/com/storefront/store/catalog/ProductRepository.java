package com.storefront.store.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Product queries.
 *
 * <p>Every listing method takes a {@link Pageable}. Product lists are the largest table a shop
 * will ever have and offset pagination degrades badly on them, so the storefront pages with a
 * cursor (sort key plus id) rather than an offset.
 *
 * <p>The {@code deleted_at IS NULL} filter is repeated on each derived query rather than done
 * with a Hibernate {@code @Where}, because {@code @Where} applies to {@code findById} too — and a
 * soft-deleted product must still be loadable by id so the shop can restore it or an order can
 * resolve it.
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

	Optional<Product> findByIdAndDeletedAtIsNull(UUID id);

	Optional<Product> findByShopIdAndSlugAndDeletedAtIsNull(UUID shopId, String slug);

	boolean existsByShopIdAndSlug(UUID shopId, String slug);

	/** The owner's own catalogue, including drafts. For the console's product table. */
	Page<Product> findByShopIdAndDeletedAtIsNull(UUID shopId, Pageable pageable);

	Page<Product> findByShopIdAndStatusAndDeletedAtIsNull(UUID shopId, Product.Status status,
			Pageable pageable);

	Page<Product> findByShopIdAndProductCategoryAndStatusAndDeletedAtIsNull(
			UUID shopId, ProductCategory category, Product.Status status, Pageable pageable);

	/** Every live product in a shop, for the storefront's category navigation. */
	List<Product> findByShopIdAndStatusAndDeletedAtIsNull(UUID shopId, Product.Status status);

	/** Shop type change (D29): the categories a shop currently uses, so blocking ones can be found. */
	@Query("""
			SELECT DISTINCT p.productCategory
			FROM Product p
			WHERE p.shopId = :shopId AND p.deletedAt IS NULL
			""")
	List<ProductCategory> categoriesInUse(@Param("shopId") UUID shopId);

	long countByShopIdAndStatusAndDeletedAtIsNull(UUID shopId, Product.Status status);

	long countByProductCategoryAndStatusAndDeletedAtIsNull(ProductCategory category,
			Product.Status status);

	/** Products the platform shows in its own listing pages. */
	Page<Product> findByStatusAndDeletedAtIsNull(Product.Status status, Pageable pageable);

	/**
	 * Featured products, for the home page.
	 *
	 * <p>A query rather than a boolean filter alone because "featured" without an order is
	 * arbitrary — the shop sets the flag, the platform decides where the product appears.
	 */
	@Query("""
			SELECT p FROM Product p
			WHERE p.featured = true AND p.status = com.storefront.store.catalog.Product.Status.ACTIVE
			  AND p.deletedAt IS NULL
			ORDER BY p.soldCount DESC, p.id ASC
			""")
	Page<Product> findFeatured(Pageable pageable);

	@Query("""
			SELECT p FROM Product p
			WHERE p.shopId = :shopId AND p.deletedAt IS NULL
			ORDER BY p.soldCount DESC, p.id ASC
			""")
	Page<Product> findBestsellers(@Param("shopId") UUID shopId, Pageable pageable);

	/**
	 * Records a sale.
	 *
	 * <p>An atomic UPDATE, because bestseller ordering runs on every product page and a
	 * read-modify-write would lose counts whenever two orders completed together.
	 *
	 * <p>Incremented at order <em>accept</em>, not at payment: the counter reflects what was
	 * actually fulfilled, so a cancelled order never inflates it.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Product p SET p.soldCount = p.soldCount + :units WHERE p.id = :productId")
	int addSoldCount(@Param("productId") UUID productId, @Param("units") int units);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Product p SET p.viewCount = p.viewCount + 1 WHERE p.id = :productId")
	int addViewCount(@Param("productId") UUID productId);

	/**
	 * Text search within a shop, using full-text and trigram matching together.
	 *
	 * <p>Native, because {@code websearch_to_tsquery} and {@code similarity()} have no JPQL
	 * spelling at all. The ranking is deliberate:
	 *
	 * <ol>
	 *   <li><b>name matches rank above everything.</b> Someone searching "rice" wants the product
	 *       named rice, not the one whose description mentions it.</li>
	 *   <li><b>Full-text ranks above trigram.</b> An exact word match is a stronger signal than
	 *       a fuzzy one, so "rice" beats "ricee".</li>
	 *   <li><b>Within trigram, similarity decides.</b> This is what makes "paracetmol" find
	 *       "Paracetamol" — the typos that actually happen.</li>
	 *   <li><b>Sales break ties.</b> Popular products first among equals, which is what a shop
	 *       owner expects from their own search box.</li>
	 * </ol>
	 *
	 * <p>Both halves are needed. Full-text alone is too strict — a misspelling returns nothing.
	 * Trigram alone cannot help a two-character query, because a 2-gram index has no useful
	 * selectivity at that length.
	 *
	 * <p>{@code websearch_to_tsquery} rather than {@code plainto_tsquery} so a shop owner can
	 * search {@code "sona masoori"} as a phrase, prefix a word with {@code *} for autocomplete,
	 * and exclude with {@code -}. It also never throws on odd input, where
	 * {@code to_tsquery} would fail the whole request on a stray character.
	 *
	 * <p>{@code ILIKE '%term%'} is kept as a floor for queries too short for either index to
	 * help, so a one or two character search still returns something rather than nothing.
	 *
	 * <p>The SKU list is searched as well as the name, and <b>exactly rather than fuzzily</b>.
	 * Full text tokenises {@code SKU-0042} into two lexemes and {@code SKU0042} into one, so a code
	 * copied off a delivery note without its hyphen matched nothing. Trigram was tried first and
	 * was wrong: every SKU shares the "SKU-" prefix, so their trigrams overlap heavily and
	 * {@code similarity} came out around 0.57 between <em>any</em> two of them — searching
	 * "SKU-0042X" returned all 1,000 products that have a SKU. Codes are not words: they either
	 * match or they do not. Hence {@code sku_search_key}, the SKU list with every non-alphanumeric
	 * character stripped, compared with the query term stripped the same way.
	 */
	@Query(value = """
			SELECT p.* FROM product p
			WHERE p.shop_id = :shopId
			  AND p.deleted_at IS NULL
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
			      p.sold_count DESC,
			      p.id
			""", nativeQuery = true)
	Page<Product> searchInShop(@Param("shopId") UUID shopId, @Param("term") String term,
			Pageable pageable);

	/**
	 * Search within one category, for a storefront category page that has its own search box.
	 *
	 * <p>Same ranking as {@link #searchInShop}; the category filter is applied in SQL so the
	 * composite category index can serve it rather than filtering after paging.
	 */
	@Query(value = """
			SELECT p.* FROM product p
			WHERE p.shop_id = :shopId
			  AND p.product_category = :category
			  AND p.status = 'ACTIVE'
			  AND p.deleted_at IS NULL
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
			      p.sold_count DESC,
			      p.id
			""", nativeQuery = true)
	Page<Product> searchInCategory(@Param("shopId") UUID shopId,
			@Param("category") String category, @Param("term") String term, Pageable pageable);

	/**
	 * Refreshes a product's denormalised SKU list.
	 *
	 * <p>Called after any variant write, inside the same transaction. The column exists so search
	 * can match a bare SKU; it is derived by a database function rather than passed in, so no
	 * caller can get it wrong by forgetting.
	 *
	 * <p>An inline UPDATE rather than calling the migration's {@code refresh_product_sku}
	 * function. A {@code RETURNS void} function still yields a row when selected, which JPA
	 * rejects with "a result was returned when none was expected" — so the function exists for
	 * the migration's backfill and the live path writes the statement directly.
	 *
	 * <p>The SET column is unqualified: Postgres allows an alias in UPDATE but rejects a qualified
	 * target column ("column \"p\" of relation \"product\" does not exist").
	 *
	 * <p><b>Deliberately not {@code clearAutomatically}.</b> This runs in the middle of a service
	 * method that is still holding the product entity it is working on, and clearing the
	 * persistence context detaches it — after which the caller reads stale field values and writes
	 * them back over the row. That is how {@code refreshMinimumPrice} came to be silently
	 * ignored: the test read a detached copy of the old price. Only {@code flushAutomatically} is
	 * needed, so the pending variant insert is visible to this aggregate UPDATE.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE product p
			   SET sku_of_product = coalesce((
			           SELECT string_agg(coalesce(v.sku, ''), ' ' ORDER BY v.sort_order, v.name)
			             FROM product_variant v
			            WHERE v.product_id = p.id AND v.sku IS NOT NULL AND v.sku <> ''
			       ), ''),
			       sku_search_key = coalesce((
			           SELECT string_agg(
			                      regexp_replace(lower(coalesce(v.sku, '')), '[^a-z0-9]', '', 'g'),
			                      ' ' ORDER BY v.sort_order, v.name)
			             FROM product_variant v
			            WHERE v.product_id = p.id AND v.sku IS NOT NULL AND v.sku <> ''
			       ), '')
			 WHERE p.id = :productId
			""", nativeQuery = true)
	void refreshSkuIndex(@Param("productId") UUID productId);

	/**
	 * Product counts per category, for the console's category filter.
	 *
	 * <p>One grouped query rather than a count per category: a shop with 500 SKUs across ten
	 * categories would otherwise be ten round trips on every filter render.
	 *
	 * <p>Native because {@code GROUP BY} over an enum column returns rows the console needs
	 * aligned with the full category list, including categories with a count of zero.
	 */
	@Query(value = """
			SELECT product_category, count(*) FROM product
			WHERE shop_id = :shopId AND deleted_at IS NULL
			GROUP BY product_category
			""", nativeQuery = true)
	List<Object[]> countByCategoryForShop(@Param("shopId") UUID shopId);

	/** Soft-deleted products, so a shop owner can find and restore a mistake. */
	Page<Product> findByShopIdAndDeletedAtIsNotNull(UUID shopId, Pageable pageable);

	/**
	 * Sellable units across a product's variants (Phase 4).
	 *
	 * <p>Available, not on hand. The product table's Stock column is what an owner can promise a
	 * customer, and a figure including reserved stock overstates it.
	 */
	@Query(value = """
			SELECT coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0)
			FROM inventory i
			JOIN product_variant v ON v.id = i.variant_id
			WHERE v.product_id = :productId
			""", nativeQuery = true)
	long totalAvailableStock(@Param("productId") UUID productId);
}
