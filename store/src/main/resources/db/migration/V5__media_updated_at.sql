-- MediaAsset extends BaseEntity, which every other mutable entity also extends, so the table
-- carries updated_at too.
--
-- Media is not strictly append-only: alt_text, sort_order and the product attachment all get
-- edited after upload. "When did this shop last change this image" is a real question when
-- debugging why a customer's storefront shows the wrong product photo, and without the column
-- the only answer is the row's creation time, which is wrong the moment anything is edited.
--
-- DEFAULT now() rather than a bare NOT NULL so the column can be added to an existing table
-- without a backfill pass; every future write goes through the entity, which sets it.
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- Alt text is the accessibility affordance and the first thing missing when a shop uploads a
-- logo with no description. Indexing it is pointless (it is not filtered on), so what is
-- indexed here instead is the shop+product lookup the storefront does for every gallery.
CREATE INDEX IF NOT EXISTS idx_media_shop_product ON media (shop_id, product_id, sort_order)
    WHERE product_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- product_variant.option_values becomes an ordered array.
--
-- The column was jsonb holding an object, and jsonb does not preserve key order: it normalises
-- keys by length then bytewise. A "Red / Large" label built from that order came back
-- "red / L" after a round trip, and could differ between two reads of the same row. A jsonb
-- *array* does preserve element order, so an array of {axis, value} pairs keeps the order the
-- shop chose ("Large / Red") through the database and back.
--
-- The existing default '{}' is converted first, so no row fails to deserialise as a list.
UPDATE product_variant
   SET option_values = '[]'::jsonb
 WHERE jsonb_typeof(option_values) = 'object';

ALTER TABLE product_variant
    ALTER COLUMN option_values SET DEFAULT '[]'::jsonb;
