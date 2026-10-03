-- ============================================================================
-- Theme engine (D8)
--
-- Three tables with three different jobs, which is worth keeping straight because they are
-- easy to conflate:
--
--   theme_template  what the ADMIN publishes. Shared, immutable once published, offered in a
--                    marketplace. Tagged with a shop_type so a pharmacy shop is only shown
--                    pharmacy templates (D17 payoff).
--   shop_theme      what ONE shop has installed and customised. Copies the template's config
--                    rather than referencing it, so a template changing later cannot alter a
--                    shop that already installed it.
--   theme_asset     the shop's own images and page content, referenced from a theme by a stable
--                    handle so re-uploading an image does not require editing every section.
-- ============================================================================

CREATE TABLE IF NOT EXISTS theme_template (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Stable machine name, e.g. "pharmacy-clean". Used in URLs and seed scripts, so it must be
    -- meaningful rather than a random id.
    code              text NOT NULL UNIQUE,
    name              text NOT NULL,
    description       text,
    preview_media_id  uuid REFERENCES media (id) ON DELETE SET NULL,
    -- Which shop type this suits. NULL means it suits any type, which is what a genuinely
    -- generic template (a bakery and a hardware shop can share one) wants.
    --
    -- An enum code rather than a row: the version is closed, so a foreign key would buy nothing
    -- and cost a migration every time a vertical is added.
    shop_type         text CHECK (shop_type IS NULL OR shop_type IN (
                          'PHARMACY', 'FASHION', 'GROCERY', 'TECH', 'PLANT_SHOP', 'BABY_SHOP',
                          'MOBILE_SHOP', 'COSMETICS', 'JEWELLERY', 'FURNITURE', 'HOME_DECOR',
                          'BOOKS', 'SPORTS', 'TOYS', 'PET_SUPPLIES', 'FLOWERS', 'ELECTRONICS',
                          'GROCERY_BEVERAGES', 'BEAUTY_SERVICES')),
    -- Validated by the shared zod schema on write and re-validated on read. jsonb, not columns:
    -- the shape is the template's business, and a template that could not express its own
    -- layout would not be a template system.
    config            jsonb NOT NULL,
    -- Version of the THEME SCHEMA, not of this template. Stored on both template and shop theme
    -- so a renderer change can migrate old configs rather than silently changing their meaning.
    schema_version    integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    -- FREE | SUBSCRIBED. Phase 3 renders everything; the tier gates *paid* templates in Phase 6
    -- alongside subscriptions. Recorded now so the marketplace has the field it will need.
    price_tier        text NOT NULL DEFAULT 'FREE'
                          CHECK (price_tier IN ('FREE', 'SUBSCRIBED')),
    is_active         boolean NOT NULL DEFAULT true,
    sort_order        integer NOT NULL DEFAULT 0,
    -- NULL until published. A template in the marketplace has one; a draft does not.
    published_at      timestamptz,
    created_by        uuid REFERENCES app_user (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- The marketplace listing: active, published, in the order an admin chose.
CREATE INDEX IF NOT EXISTS idx_theme_template_marketplace
    ON theme_template (sort_order, name)
    WHERE is_active AND published_at IS NOT NULL;

-- Filtering the marketplace by shop type. Partial, because that is the only query anyone runs.
CREATE INDEX IF NOT EXISTS idx_theme_template_shop_type
    ON theme_template (shop_type)
    WHERE is_active AND published_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS shop_theme (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id        uuid NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    -- Which template this came from. SET NULL on delete so retiring a template does not delete
    -- the themes every shop built from it; the provenance is nice to have, not load-bearing.
    template_id    uuid REFERENCES theme_template (id) ON DELETE SET NULL,
    name           text NOT NULL DEFAULT 'Default',
    -- A COPY of the template config, not a reference. A shop's theme must not change because the
    -- marketplace template was edited, and must not stop working because the template was retired.
    config         jsonb NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    -- Drafts are how the editor works: several themes per shop, one of them live.
    is_published   boolean NOT NULL DEFAULT false,
    published_at   timestamptz,
    created_by     uuid REFERENCES app_user (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- One live theme per shop. Partial, because the constraint only applies to published rows and a
-- full unique index on (shop_id) would stop a shop keeping several drafts.
CREATE UNIQUE INDEX IF NOT EXISTS uq_shop_theme_one_published
    ON shop_theme (shop_id)
    WHERE is_published;

CREATE INDEX IF NOT EXISTS idx_shop_theme_shop
    ON shop_theme (shop_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS theme_asset (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id    uuid NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    -- HERO | HEADER_PAGE | FOOTER | BANNER | CATALOG_LAYOUT | PRODUCT_LAYOUT | POLICY_PAGE
    kind       text NOT NULL CHECK (kind IN (
                   'HERO', 'HEADER_PAGE', 'FOOTER', 'BANNER', 'MEDIA_PAGE',
                   'CATALOG_LAYOUT', 'PRODUCT_LAYOUT', 'POLICY_PAGE')),
    -- Stable key a section refers to, e.g. "footer-main". Deliberately not an id: an asset is
    -- re-pointed by handle so replacing an image does not mean editing every section that
    -- used it, and so a section can reference an asset that does not exist yet.
    handle     text NOT NULL CHECK (handle ~ '^[a-z0-9][a-z0-9-]{0,39}$'),
    name       text NOT NULL,
    -- Shape varies by kind: an image URL, a page's blocks, a policy's body.
    config     jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shop_id, kind, handle)
);

CREATE INDEX IF NOT EXISTS idx_theme_asset_shop ON theme_asset (shop_id, kind);
