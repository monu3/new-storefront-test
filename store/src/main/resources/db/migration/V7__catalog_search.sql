-- ============================================================================
-- Catalog search: Postgres full-text plus trigram typo tolerance.
--
-- Two mechanisms, because they fail differently and a shop owner notices both.
--
-- FTS answers "rice" -> products containing RICE. It is exact about words and fast.
-- But an owner typing "paracetmol" (a very common misspelling of paracetamol) gets
-- nothing back and concludes the shop has no paracetamol. Trigrams answer that: they
-- match character sequences, so typos and near-misses still land.
--
-- Both are needed. FTS alone is too strict; trigram alone ranks poorly and cannot
-- help a two-character query, since a 2-gram index has no useful selectivity there.
-- ============================================================================

-- Trigram matching. Requires superuser or database-owner rights; locally Postgres
-- runs as that role. If it is unavailable the migration fails loudly rather than
-- silently degrading search, because a quietly-worse search box is much harder to
-- notice than a failed boot.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================================================
-- A SKU-indexed denormalisation on product
--
-- The search document wants the product's SKUs, but they live on product_variant
-- and a generated column cannot reference another table. Rather than a trigger that
-- has to be kept in step with three services, the SKU list is a small denormalised
-- text column maintained inside the same transaction as any variant change. It
-- exists purely so search can match "RICE-5" to its product.
--
-- Derived by a function rather than passed in by callers, so the value cannot be
-- wrong because some code path forgot to set it.
-- ============================================================================
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS sku_of_product text NOT NULL DEFAULT '';

-- The separator-free form of the same list, for matching a code typed without its hyphens.
--
-- Declared BEFORE refresh_product_sku, because that function writes this column and a PL/pgSQL
-- body is only checked when it runs. Declaring it afterwards makes the migration "succeed" and
-- then fail at runtime the first time the function is called, which is a far more confusing
-- failure than a migration error. That ordering mistake cost a cycle and is recorded so it is
-- not repeated.
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS sku_search_key text NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_product_sku_key ON product (sku_search_key);

CREATE OR REPLACE FUNCTION refresh_product_sku(product_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE product p
       SET sku_of_product = coalesce((
               SELECT string_agg(coalesce(v.sku, ''), ' ' ORDER BY v.sort_order, v.name)
                 FROM product_variant v
                WHERE v.product_id = p.id AND v.sku IS NOT NULL AND v.sku <> ''
           ), ''),
           -- The same list with every non-alphanumeric character removed, so a code read off a
           -- delivery note matches whether or not it carries its separators.
           sku_search_key = coalesce((
               SELECT string_agg(regexp_replace(lower(coalesce(v.sku, '')), '[^a-z0-9]', '', 'g'),
                                 ' ' ORDER BY v.sort_order, v.name)
                 FROM product_variant v
                WHERE v.product_id = p.id AND v.sku IS NOT NULL AND v.sku <> ''
           ), '')
     WHERE p.id = product_id;
END;
$$;

-- ============================================================================
-- Weighted search document
--
-- 'simple' rather than 'english' deliberately. English stemming would suit an
-- English-only catalogue but mangles every non-Latin token: a Nepali or Hindi product
-- name gets stemmed or dropped, and this platform sells across 19 verticals in a
-- multilingual market. 'simple' does no stemming, so it never destroys a token it
-- does not understand. Typo tolerance comes from trigram instead, which is
-- language-agnostic.
--
-- Weights let a name match outrank a mention elsewhere. Someone searching "rice"
-- wants the product NAMED rice, not the one whose description mentions it.
--
-- Generated rather than maintained in Java so it cannot drift from the columns it
-- indexes. A trigger-maintained vector would need updating by every service that
-- touches a name, and the one that forgets is invisible until a search is wrong.
-- ============================================================================
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS search_document tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(name, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(short_description, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(sku_of_product, '')), 'C')
    ) STORED;

-- GIN for the full-text half.
CREATE INDEX IF NOT EXISTS idx_product_search_document
    ON product USING gin (search_document);

-- Trigram on the name, for typo tolerance. gin_trgm_ops supports LIKE, ILIKE and
-- similarity() as well as the % operator.
--
-- ILIKE is what makes this useful: an owner typing "Rice" and one typing "rice" must
-- get the same results, and citext does not help here because this is a pattern match
-- rather than an equality comparison.
CREATE INDEX IF NOT EXISTS idx_product_name_trgm
    ON product USING gin (name gin_trgm_ops);

-- Category is filtered on nearly every listing, alongside status. A composite index
-- serves both the storefront (shop + category + active) and the console's filter,
-- where a shop with 500 SKUs would otherwise scan its whole catalogue per request.
CREATE INDEX IF NOT EXISTS idx_product_shop_category_status
    ON product (shop_id, product_category, status)
    WHERE deleted_at IS NULL;

-- Bestseller ordering appears on every product page. Ordering by sold_count within a
-- shop lets Postgres use an index for the sort instead of sorting 500 rows per page.
CREATE INDEX IF NOT EXISTS idx_product_shop_sold
    ON product (shop_id, sold_count DESC)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';

-- Trigram on variant names too: "Sona Masori" should find "Sona Masoori".
CREATE INDEX IF NOT EXISTS idx_variant_name_trgm
    ON product_variant USING gin (name gin_trgm_ops);

-- Every product created before this migration has an empty sku_of_product. Backfill
-- from the variants that already exist, so search finds existing stock immediately
-- rather than only newly created products.
SELECT refresh_product_sku(id) FROM product;

-- The normalised key was added after the tsvector, so the backfill above is what fills it.

-- ---------------------------------------------------------------------------
-- CSV import lookup indexes
--
-- The import resolves each row's SKU by joining variant to product and filtering on the shop.
-- Without an index on lower(sku) that is a sequential scan per row, and a 1,000-row import
-- becomes a million scans. Found by running the Phase 2 exit criterion against a 500-SKU
-- catalogue: the import did not finish.
--
-- The index is on the lower() expression because the lookup is case-insensitive, so a plain
-- index on sku would not be used for it.
CREATE INDEX IF NOT EXISTS idx_variant_sku_lower ON product_variant (lower(sku))
    WHERE sku IS NOT NULL;

-- The same shape for the name fallback, for a supplier's sheet with no codes.
CREATE INDEX IF NOT EXISTS idx_variant_name_lower ON product_variant (lower(name));
