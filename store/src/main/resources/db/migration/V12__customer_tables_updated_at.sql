-- ============================================================================
-- Phase 5: audit columns on the new customer tables
--
-- V10 created customer_address, wishlist_item and product_review with `created_at` only, while all
-- three entities extend BaseEntity, which maps both created_at and updated_at. customer_device had
-- the same gap, fixed in V11; this does the other three together rather than one migration per
-- table.
--
-- A V12 rather than an edit to V10, because V10 has already run on the dev and test databases and
-- changing an applied migration breaks Flyway's checksum.
-- ============================================================================

ALTER TABLE customer_address
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE wishlist_item
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE product_review
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- An address that is the default is ordered by `updated_at`, so backfill it to created_at rather
-- than letting every existing row default to "now" and appear to have just been edited.
UPDATE customer_address SET updated_at = created_at WHERE updated_at > created_at;