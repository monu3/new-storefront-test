-- ============================================================================
-- Phase 4: settings, audience, payment methods
--
-- Three additive changes, none of which rewrites existing data:
--
--   shop_setting.created_at   the baseline table had only updated_at, so it could not carry the
--                             audit pair every other mutable table has.
--   customer.private_note_*   the audience screen's shop-private notes need to be attributable and
--                             timestamped; a bare TEXT column cannot say who wrote it or when.
--   shop_payment_method.*     per-shop payment configuration (Phase 4). The table exists from the
--                             baseline; this adds the columns the config screen actually needs.
-- ============================================================================

-- -----------------------------------------------------------------------------
-- Settings: bring the audit pair up to the house standard
-- -----------------------------------------------------------------------------

ALTER TABLE shop_setting
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();

-- -----------------------------------------------------------------------------
-- Audience: attributable private notes
-- -----------------------------------------------------------------------------

-- A customer row is created the first time someone orders, so this column is written long after
-- the customer table's own created_at. Keeping them separate means "when did we first hear of
-- them" and "when did an owner last annotate them" stay distinguishable.
ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS private_note_updated_at timestamptz,
    ADD COLUMN IF NOT EXISTS private_note_updated_by  uuid REFERENCES app_user (id) ON DELETE SET NULL;

-- -----------------------------------------------------------------------------
-- Payment methods (Phase 4): the per-shop configuration
-- -----------------------------------------------------------------------------

-- The baseline already has method, is_enabled, the eSewa columns, the bank columns and cod_fee.
-- These three are what a real configuration screen needs on top:
--   minimum_minor     the floor below which this method is not offered at all, so an owner does
--                     not have to reason about small orders themselves.
--   max_order_minor   the ceiling. Rarely used, but a bank transfer on a large order is a
--                     different risk than on a small one.
--   display_label     what the customer sees in the checkout list, which is not always the
--                     gateway's own name ("Cash on delivery (pay the rider)").
ALTER TABLE shop_payment_method
    ADD COLUMN IF NOT EXISTS minimum_minor  bigint NOT NULL DEFAULT 0 CHECK (minimum_minor >= 0),
    ADD COLUMN IF NOT EXISTS max_order_minor bigint CHECK (max_order_minor IS NULL OR max_order_minor >= 0),
    ADD COLUMN IF NOT EXISTS display_label  text;

-- A configured eSewa method with no merchant id is the one combination that must be impossible:
-- the owner believes they take eSewa, the storefront offers it, and checkout refuses at the last
-- moment. Enforced here rather than in the service so no code path can create it.
ALTER TABLE shop_payment_method
    ADD CONSTRAINT ck_esewa_requires_merchant
    CHECK (
        method <> 'ESAWA'
        OR (
            is_enabled = false
            OR (esewa_merchant_id IS NOT NULL AND length(btrim(esewa_merchant_id)) > 0)
        )
    ) NOT VALID;

-- NOT VALID: an existing shop may already have an enabled ESAWA row with no merchant id, and this
-- migration must not fail on it. The service refuses new ones, and a later migration validates
-- once the shops have been told to fix theirs.