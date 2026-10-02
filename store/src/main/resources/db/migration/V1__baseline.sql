-- =============================================================================
-- store.app — baseline migration
--
-- This migration runs before any JPA entity is validated. Schema ownership
-- belongs to Flyway for the life of the project; migrations are append-only
-- and never edited after merge.
-- =============================================================================

-- Case-insensitive email and slug columns (app_user.email, shop.slug, ...).
-- Must exist before V2 creates any table that uses citext.
CREATE EXTENSION IF NOT EXISTS citext;
