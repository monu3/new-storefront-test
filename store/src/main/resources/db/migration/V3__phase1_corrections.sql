-- =============================================================================
-- V3: corrections found by running the Phase 1 entities
--
-- Migrations are append-only, so these are forward fixes rather than edits to V2.
-- =============================================================================

-- shop_wallet_balance inherited BaseEntity in the entity model, which supplies
-- created_at as well as updated_at. The V2 table only had updated_at, so
-- `ddl-auto: validate` correctly refused to start. Every other table has both.
ALTER TABLE shop_wallet_balance
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

-- The wallet ledger is append-only, so created_at is the row's own timestamp and
-- must not be rewritten. stated explicitly because BaseEntity would otherwise let
-- it drift.
COMMENT ON COLUMN wallet_ledger_entry.created_at IS
    'Immutable. The ledger is append-only, so this never changes after insert.';

COMMENT ON TABLE shop_wallet_balance IS
    'READ CACHE. Not the source of truth. The authoritative balance is '
    'SUM(CREDIT amount - DEBIT amount) over wallet_ledger_entry. This table is '
    'rebuilt by WalletService.reconcile and must never be edited directly.';

COMMENT ON TABLE wallet_ledger_entry IS
    'Append-only. There is no UPDATE or DELETE path. A correction is a new row '
    'pointing at the original through reversal_of. idempotency_key is UNIQUE, which '
    'is what makes double-charging impossible.';

COMMENT ON COLUMN shop_order.commission_rate_bp IS
    'Basis points (500 = 5%), snapshotted at accept time so a later platform rate '
    'change cannot rewrite historical orders.';

COMMENT ON COLUMN shop_order.commission_waived IS
    'True when the shop is subscribed and pays no commission. Recorded explicitly '
    'rather than left as a null so the reason is auditable.';
