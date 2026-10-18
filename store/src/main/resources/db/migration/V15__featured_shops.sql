-- ============================================================================
-- Phase 7: featured shops
--
-- The plan called for `is_featured` on `shop` (section 4, the gallery ordering). It was never
-- created, so this adds it rather than the front end inventing a second "featured" list somewhere.
--
-- Partial index: the gallery asks "the featured shops, ordered", and at most a dozen ever are, so an
-- index over the flagged rows alone is smaller than one over the whole column.
-- ============================================================================

ALTER TABLE shop
    ADD COLUMN IF NOT EXISTS is_featured boolean NOT NULL DEFAULT false;

-- The gallery reads "the featured shops, newest first". `created_at` alone rather than a sort column
-- the `shop` table does not have: a hand-ordered featured row is Phase 7's business if it is ever
-- wanted, and adding an ordering column to every shop to serve a dozen rows is a poor trade.
CREATE INDEX IF NOT EXISTS idx_shop_featured
    ON shop (created_at DESC)
    WHERE is_featured = true;

COMMENT ON COLUMN shop.is_featured IS
    'Set by an admin. Not an entitlement: a shop cannot buy its way onto the front page, because a paid
     slot that can be bought is an advertisement, and calling it a feature would be dishonest about
     what it is.';