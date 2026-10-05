-- ============================================================================
-- Phase 5: customer_device audit column
--
-- V10 created customer_device with `created_at` and `last_seen_at` but no `updated_at`, while
-- CustomerDevice extends BaseEntity, which maps both. Hibernate's schema validation caught it at
-- boot with "missing column [updated_at] in table [customer_device]".
--
-- A V11 rather than an edit to V10, because V10 has already run on the dev and test databases and
-- changing a applied migration breaks Flyway's checksum.
--
-- `updated_at` is what `last_seen_at` reads from, so the two are the same value in practice.
-- Kept as separate columns because they answer different questions: when a device row was created,
-- and when the token was last presented. They differ for a token that was registered once and has
-- never re-registered.
-- ============================================================================

ALTER TABLE customer_device
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- Backfill from last_seen_at so the new column is truthful for tokens registered before it
-- existed, rather than defaulting to "now" for every historical row.
UPDATE customer_device
   SET updated_at = last_seen_at
 WHERE updated_at > last_seen_at;