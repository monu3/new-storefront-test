-- ============================================================================
-- Phase 7: the audit log and platform settings
--
-- Two tables. Neither is a nice-to-have:
--
-- * `audit_log` is Phase 7's exit criterion -- "every privileged action lands in the audit log".
--   It is built FIRST, before the admin features, so that each one records as it is written rather
--   than being retrofitted afterwards. Retrofitting auditing is how you end up with an audit log
--   that is complete for the features somebody remembered.
--
-- * `platform_setting` is where the commission rate stops being a constant in Java. Right now
--   `CommissionCalculator.DEFAULT_RATE_BP = 500` is baked in, so the platform's single most
--   consequential number cannot be changed by the people entitled to change it.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- audit_log
--
-- REPLACES the table declared in V2.
--
-- <p>V2 declared `audit_log` and nothing ever used it: there was no entity, no service, and no
-- rows, in any database. Phase 7 is the phase that implements it, and it needs two columns the V2
-- shape does not have:
--
--   * `summary`  — a one-sentence description. A log list where every row's description is a bare
--                  action name is a list nobody can scan, and the whole reason to open this screen is
--                  to read what happened without opening rows.
--   * `reason`   — required by the API for actions that take something away (suspend, reject,
--                  delete). "An admin suspended a shop" without a reason helps neither the shop,
--                  which cannot tell what to fix, nor the next admin, who has to reverse-engineer it.
--
-- <p>DROP rather than ALTER because the V2 shape also cannot express what Phase 7 needs cleanly: a
-- `bigserial` id on an append-only log, `inet` for an address the recorder does not read yet, and a
-- single `actor_role` for a record that should preserve every authority in force at the time.
--
-- <p>Safe because the table has never been written to. That is checked, not assumed: this migration
-- refuses to drop it if any row exists, so a database where something did write to it fails loudly
-- rather than losing records.
--
-- <p>What is KEPT from V2: the dedicated `shop_id` column, which makes "everything that happened to
-- this shop" a one-column index scan rather than an entity-type string match.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM audit_log) THEN
        RAISE EXCEPTION
            'audit_log has % row(s) but no code has ever written to it. Refusing to drop real records -- migrate this table by hand instead.',
            (SELECT count(*) FROM audit_log);
    END IF;

    DROP TABLE IF EXISTS audit_log;
END
$$;

CREATE TABLE audit_log (
    id             uuid PRIMARY KEY,

    -- Who. Null for a system action (an expiry sweep, a scheduled job), which is itself
    -- information: "the scheduler did this" is a different claim from "an admin did this".
    actor_user_id  uuid,
    -- Copied, not joined. An audit record that vanishes when the account is deleted is not an audit
    -- record, and deleting an admin's account is exactly when somebody would want to read what they
    -- did.
    actor_email    text,
    actor_roles    text,

    -- What was done. A closed vocabulary, enforced in Java by AuditAction rather than by a CHECK
    -- constraint: adding one is a code change, which is the point -- an unrecognised action string
    -- in a log is a typo nobody notices.
    action         text NOT NULL,

    -- Which row. Null for actions that are not about a row (a settings change).
    entity_type    text,
    entity_id      uuid,
    -- KEPT from V2: a dedicated column, not derivable from entity_type. "Everything that happened
    -- to this shop" is then a one-column index scan instead of a string match, and it is the query a
    -- shop owner most wants when they ask why they were suspended.
    shop_id        uuid REFERENCES shop (id) ON DELETE SET NULL,

    -- Before and after, for the diff view. Only the fields that actually changed are recorded:
    -- storing whole entities would make every row enormous and every diff a wall of unchanged
    -- fields with three altered ones hidden in it.
    before_state   jsonb,
    after_state    jsonb,

    -- One sentence a human reads in a list without opening the row.
    summary        text NOT NULL,

    -- Why. Required by the API for actions that take one (suspend, reject), because "an admin
    -- suspended a shop" without a reason does not help the shop or the next admin.
    reason         text,

    -- Where from. Kept because "who did this" is often really "who had this session".
    ip_address     text,
    user_agent     text,

    created_at     timestamptz NOT NULL DEFAULT now(),

    -- The third appearance of the BaseEntity trap (V11, V12, V14): every entity extends BaseEntity,
    -- which maps created_at AND updated_at, and Hibernate's schema validation fails at boot without
    -- both.
    --
    -- Included here even though this log is append-only and nothing will ever update a row, so this
    -- column will equal created_at forever. It exists for schema validation, and a reviewer seeing
    -- them always equal should read it as "nothing has ever updated a row" -- which is the point of
    -- an append-only log, not a defect.
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- The list view: newest first, optionally filtered by action, actor or entity.
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at DESC);

-- "Everything that happened to this shop" -- the question an owner asks after a suspension, and the
-- one a support agent asks when a shop says an order vanished. Kept from V2, and the reason
-- `shop_id` exists as its own column.
CREATE INDEX IF NOT EXISTS idx_audit_log_shop ON audit_log (shop_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_type, entity_id, created_at DESC);

-- "Everything this admin did", for a review or an investigation.
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON audit_log (actor_user_id, created_at DESC);

-- Filtered by action -- "show me every suspension".
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action, created_at DESC);

COMMENT ON TABLE audit_log IS
    'Every privileged action. Append-only: there is deliberately no update or delete path.';

-- ---------------------------------------------------------------------------
-- platform_setting
--
-- The table already exists, created in V2 with exactly these columns and seeded with three keys.
-- It is repeated here as `IF NOT EXISTS` for one reason: a database created from this migration
-- alone has to be able to boot, and because `CREATE TABLE IF NOT EXISTS` plus
-- `ON CONFLICT DO NOTHING` is a no-op where the table and rows are already present, the statement
-- is safe to run against the databases that have had it since Phase 1.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform_setting (
    key         text PRIMARY KEY,
    -- jsonb rather than text, because these are typed: commission_rate_bp is a number and
    -- gallery_featured_limit is a number, and a settings table that stores everything as a string
    -- hands its caller the parsing bug.
    value       jsonb NOT NULL,
    -- Who last changed it, and when. A setting nobody changed is indistinguishable from a default.
    updated_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_setting IS
    'Platform-wide configuration. Read through PlatformSettings, never queried directly.';

-- ---------------------------------------------------------------------------
-- Seeded settings
-- ---------------------------------------------------------------------------
--
-- Three of these already exist from V2 (commission_rate_bp, platform_currency, min_topup_minor) and
-- are left alone by `ON CONFLICT DO NOTHING` -- the seeds below are there so a database has a value
-- for every key the code reads, not to change what the existing ones say.
--
-- Seeded in the migration so a fresh install never handles "setting absent": code that copes with a
-- missing setting has a branch nobody exercises, and a branch nobody exercises is a branch that is
-- wrong.
--
-- commission_rate_bp is 500, which is what CommissionCalculator.DEFAULT_RATE_BP has always been.
-- Moving the rate from a constant to a setting therefore changes no behaviour, which makes it
-- provably safe rather than probably safe.
--
INSERT INTO platform_setting (key, value) VALUES
    ('commission_rate_bp',           '500'::jsonb),
    ('min_topup_minor',              '100000'::jsonb),
    ('platform_currency',            '"NPR"'::jsonb),
    ('gallery_featured_limit',       '12'::jsonb),
    ('registration_open',            'true'::jsonb),
    ('auto_approve_subscriptions',   'false'::jsonb)
ON CONFLICT (key) DO NOTHING;

-- ============================================================================
-- What is deliberately NOT here
--
-- * No `shop.is_featured` column. `shop` already has it (Phase 1, line 590 of the plan) and the
--   Java entity does not map it yet -- Phase 7 adds the mapping, not a second copy of the column.
--
-- * No partitioning. An audit log on a marketplace this size is thousands of rows a month. Partition
--   when it is tens of millions and the query is slow, not before.
--
-- * No `expires_at`. Audit records are not cache and must not expire.
-- ============================================================================
