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

	/** Text search within a shop. Backs the console's product search box. */
	@Query("""
			SELECT p FROM Product p
			WHERE p.shopId = :shopId AND p.deletedAt IS NULL
			  AND (lower(p.name) LIKE lower(concat('%', :term, '%'))
			       OR lower(coalesce(p.shortDescription, '')) LIKE lower(concat('%', :term, '%')))
			ORDER BY p.id DESC
			""")
	Page<Product> searchInShop(@Param("shopId") UUID shopId, @Param("term") String term,
			Pageable pageable);

	/** Soft-deleted products, so a shop owner can find and restore a mistake. */
	Page<Product> findByShopIdAndDeletedAtIsNotNull(UUID shopId, Pageable pageable);
}
