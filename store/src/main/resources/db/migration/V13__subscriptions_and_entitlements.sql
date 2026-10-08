-- ============================================================================
-- Phase 6: subscriptions, plans and entitlements (D4, D7)
--
-- Four tables plus two columns on existing ones.
--
-- Why the columns on existing tables rather than a join table: a top-up request and a ledger entry
-- are each *one* thing that may or may not be about a subscription. A separate link table would
-- make "which subscription does this top-up belong to?" a query on every admin approval, and would
-- permit the nonsense state of a top-up linked to two subscriptions. A nullable column says the
-- same thing and cannot be got wrong.
--
-- Nothing here is edited back into an earlier migration. V1-V12 have all run on the dev and test
-- databases, and Flyway checksums applied migrations.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- subscription_plan: what an admin sells
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subscription_plan (
    id             uuid PRIMARY KEY,
    -- FREE, PRO_MONTHLY, PRO_ANNUAL. A stable machine key, distinct from `name` because the name is
    -- display text and will be edited; the code is what seed data and tests refer to.
    code           text NOT NULL UNIQUE,
    name           text NOT NULL,
    description    text,

    -- NONE for FREE. Anything else must have a duration_days, enforced below: a paid plan with no
    -- duration would never expire, and a shop on it would keep its commission waiver forever
    -- because there is no date for the expiry job to find.
    billing_period text NOT NULL,
    price_minor    bigint NOT NULL DEFAULT 0,
    duration_days  integer,

    is_active      boolean NOT NULL DEFAULT true,
    -- Display order on the pricing screen. Lower first.
    sort_order     integer NOT NULL DEFAULT 0,

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT subscription_plan_period_known
        CHECK (billing_period IN ('NONE', 'MONTHLY', 'ANNUAL')),

    -- A paid plan must expire. This is the constraint that makes the expiry job's job possible, and
    -- it is checked here rather than in Java so a row written by hand -- a seed script, psql, a
    -- future import -- cannot slip past it.
    CONSTRAINT subscription_plan_paid_must_expire
        CHECK (
            billing_period = 'NONE'
            OR (duration_days IS NOT NULL AND duration_days > 0)
        ),

    -- A paid plan must cost something. A free plan must not, or MRR would count it as revenue and
    -- the dashboard would lie about the business in a way nobody would think to check.
    CONSTRAINT subscription_plan_price_matches_period
        CHECK (
            (billing_period = 'NONE' AND price_minor = 0)
            OR (billing_period <> 'NONE' AND price_minor > 0)
        )
);

COMMENT ON TABLE subscription_plan IS
    'The plans an admin sells. Entitlements live in plan_entitlement, never inline here (D4).';

-- ---------------------------------------------------------------------------
-- shop_subscription: one shop's history of subscribing
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shop_subscription (
    id               uuid PRIMARY KEY,
    shop_id          uuid NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    plan_id          uuid NOT NULL REFERENCES subscription_plan (id),

    status           text NOT NULL DEFAULT 'PENDING_PAYMENT',

    started_at       timestamptz,
    expires_at       timestamptz,

    -- Always false in v1 (D7): there is no recurring charging, so there is nothing to renew
    -- automatically. The column exists so that "this shop chose not to renew" is recorded as a
    -- fact rather than inferred from the absence of a field.
    auto_renew       boolean NOT NULL DEFAULT false,

    -- Which top-up paid for this. Set at activation, nullable because a subscription can be
    -- requested long before anybody transfers the money.
    topup_request_id uuid REFERENCES wallet_topup_request (id),

    cancelled_at     timestamptz,

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT shop_subscription_status_known
        CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'EXPIRED', 'CANCELLED')),

    -- An ACTIVE subscription must have both ends of its window. Without this, an order accepted
    -- during a subscription could be judged against a missing start date.
    CONSTRAINT shop_subscription_active_has_window
        CHECK (status <> 'ACTIVE' OR (started_at IS NOT NULL AND expires_at IS NOT NULL)),

    CONSTRAINT shop_subscription_window_ordered
        CHECK (expires_at IS NULL OR started_at IS NULL OR expires_at > started_at)
);

-- "The shop's current subscription" is the hot query: the entitlement check, the commission
-- decision and the expiry sweep all want it. Indexed by (shop_id, status) with expires_at last so
-- the expiry sweep -- status = ACTIVE and expires_at < now -- can use it too.
CREATE INDEX IF NOT EXISTS idx_shop_subscription_shop_status
    ON shop_subscription (shop_id, status, expires_at DESC);

-- The expiry sweep's own predicate, as a partial index so it only holds rows that can expire.
CREATE INDEX IF NOT EXISTS idx_shop_subscription_expiring
    ON shop_subscription (expires_at)
    WHERE status = 'ACTIVE';

-- One ACTIVE subscription per shop, at most. This is the single most important constraint in the
-- table: two active subscriptions would mean two commission waivers and two entitlements sets, and
-- whichever one the reader happened to find first would be the answer. The database refuses it
-- rather than leaving it to whichever order a query happened to return.
CREATE UNIQUE INDEX IF NOT EXISTS uq_shop_subscription_one_active
    ON shop_subscription (shop_id)
    WHERE status = 'ACTIVE';

-- A PENDING_PAYMENT subscription for the same plan is also a duplicate: a shop that double-clicks
-- "subscribe" must not queue two requests and be charged twice.
CREATE UNIQUE INDEX IF NOT EXISTS uq_shop_subscription_one_pending_per_plan
    ON shop_subscription (shop_id, plan_id)
    WHERE status = 'PENDING_PAYMENT';

COMMENT ON TABLE shop_subscription IS
    'A shop''s subscription history. At most one ACTIVE row per shop (partial unique index).';

-- ---------------------------------------------------------------------------
-- plan_entitlement: what each plan grants
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plan_entitlement (
    plan_id     uuid NOT NULL REFERENCES subscription_plan (id) ON DELETE CASCADE,
    feature_key text NOT NULL,
    enabled     boolean NOT NULL DEFAULT false,
    -- The cap for countable features (max_products = 500). NULL means "not capped"; only meaningful
    -- when enabled, which the check below enforces so a plan cannot grant a limit of 0 products
    -- while claiming the feature is off.
    limit_value bigint,

    created_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (plan_id, feature_key),

    CONSTRAINT plan_entitlement_limit_positive
        CHECK (limit_value IS NULL OR limit_value >= 0),

    -- A disabled feature has no limit. Otherwise a row would say "no custom CSS, up to 3 of them",
    -- which is a contradiction rather than a configuration.
    CONSTRAINT plan_entitlement_disabled_has_no_limit
        CHECK (enabled OR limit_value IS NULL)
);

-- ---------------------------------------------------------------------------
-- shop_entitlement: what THIS shop may actually do right now (D4)
-- ---------------------------------------------------------------------------
--
-- D4 says this table is the single source of truth. That is why the plan's entitlements are
-- *materialised* into it on activation rather than joined through at read time: one table answers
-- the question, and a shop's rights do not change under it when an admin edits a plan that nobody
-- has bought yet.
--
-- The cost is that a plan edit does not reach existing subscribers until they re-activate. That is
-- accepted: plans are versioned by making a new plan, not by editing a live one, and
-- SubscriptionService refuses to edit a plan that an active subscriber holds.
--
CREATE TABLE IF NOT EXISTS shop_entitlement (
    shop_id     uuid NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    feature_key text NOT NULL,

    enabled     boolean NOT NULL DEFAULT false,
    limit_value bigint,

    -- Copied from the subscription's end date at materialisation time. Enforced in every read as
    -- `expires_at IS NULL OR expires_at > now()`, so a lapsed entitlement stops being granted the
    -- instant its window closes -- not when the expiry job next runs. The job is a cleanup, never
    -- the enforcement point; a scheduler being down must not silently extend a customer's access.
    expires_at  timestamptz,

    updated_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (shop_id, feature_key),

    CONSTRAINT shop_entitlement_limit_positive
        CHECK (limit_value IS NULL OR limit_value >= 0),

    CONSTRAINT shop_entitlement_disabled_has_no_limit
        CHECK (enabled OR limit_value IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_shop_entitlement_expiring
    ON shop_entitlement (expires_at)
    WHERE expires_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The two columns on existing tables
-- ---------------------------------------------------------------------------

-- Which subscription a ledger entry belongs to. Nullable: most entries (commission, top-up) are
-- not subscription-related, and a NOT NULL here would mean every entry type needed a subscription.
ALTER TABLE wallet_ledger_entry
    ADD COLUMN IF NOT EXISTS subscription_id uuid REFERENCES shop_subscription (id);

ALTER TABLE wallet_topup_request
    ADD COLUMN IF NOT EXISTS plan_id uuid REFERENCES subscription_plan (id),
    ADD COLUMN IF NOT EXISTS subscription_id uuid REFERENCES shop_subscription (id);

COMMENT ON COLUMN wallet_ledger_entry.subscription_id IS
    'Set for SUBSCRIPTION_CHARGE entries. Lets revenue reconcile a charge to the plan that caused it.';

COMMENT ON COLUMN wallet_topup_request.subscription_id IS
    'Set when this top-up is paying for a subscription, so admin approval activates it.';

-- ---------------------------------------------------------------------------
-- Seed plans
-- ---------------------------------------------------------------------------
--
-- Seeded in the migration rather than in a Flyway callback so that the FREE plan exists in every
-- database the moment the schema does. An entitlement engine that reads "the shop's plan" and finds
-- no FREE row would fail on a fresh install, and a fresh install is precisely when nobody is
-- watching.
--
-- The FREE row matters even though it is free: it is how a shop's entitlements are expressed
-- without a subscription existing at all. EntitlementService falls back to FREE rather than to
-- "nothing", so the caps a free shop lives with are stated in one place and can be raised by
-- editing a plan instead of by editing code.
--
INSERT INTO subscription_plan
    (id, code, name, description, billing_period, price_minor, duration_days, is_active, sort_order)
VALUES
    ('00000000-0000-0000-0000-00000000f001', 'FREE', 'Free',
     'List up to 25 products. The platform takes 5% commission on each accepted order.',
     'NONE', 0, NULL, true, 0),

    ('00000000-0000-0000-0000-00000000f002', 'PRO_MONTHLY', 'Pro (monthly)',
     'Up to 500 products, paid templates, no commission. Renews by manual top-up each month.',
     'MONTHLY', 200000, 31, true, 1),

    ('00000000-0000-0000-0000-00000000f003', 'PRO_ANNUAL', 'Pro (annual)',
     'Everything in Pro monthly, at twelve months'' price. Two months free.',
     'ANNUAL', 2000000, 366, true, 2)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Seed the FREE plan's entitlements
-- ---------------------------------------------------------------------------
--
-- This is the baseline every shop lives under, so it is written as data. The alternative -- a
-- constant in Java -- means raising the free product cap requires a code change and a deploy, and
-- means a fresh database and a migrated database can disagree about what "free" means.
--
-- use_paid_templates and custom_css are absent rather than present-and-disabled, so that adding a
-- paid feature to a new plan does not require remembering to disable it on every other plan.
-- An absent row means "not granted", which is the safe default.
--
INSERT INTO plan_entitlement (plan_id, feature_key, enabled, limit_value)
SELECT p.id, e.feature_key, e.enabled, e.limit_value
FROM subscription_plan p
CROSS JOIN (VALUES
    ('max_products',           true,  25::bigint),
    ('remove_commission',      false, NULL),
    ('use_paid_templates',     false, NULL),
    ('custom_css',             false, NULL),
    ('custom_domain',          false, NULL),
    ('discount_codes',         false, NULL),
    ('csv_import',             false, NULL),
    ('priority_support',       false, NULL),
    ('abandoned_cart_recovery',false, NULL)
) AS e(feature_key, enabled, limit_value)
WHERE p.code = 'FREE'
ON CONFLICT (plan_id, feature_key) DO NOTHING;

INSERT INTO plan_entitlement (plan_id, feature_key, enabled, limit_value)
SELECT p.id, e.feature_key, e.enabled, e.limit_value
FROM subscription_plan p
CROSS JOIN (VALUES
    ('max_products',           true,  500::bigint),
    ('remove_commission',      true,  NULL),
    ('use_paid_templates',     true,  NULL),
    ('custom_css',             true,  NULL),
    ('custom_domain',          false, NULL),
    ('discount_codes',         true,  NULL),
    ('csv_import',             true,  NULL),
    ('priority_support',       true,  NULL),
    ('abandoned_cart_recovery',false, NULL)
) AS e(feature_key, enabled, limit_value)
WHERE p.code IN ('PRO_MONTHLY', 'PRO_ANNUAL')
ON CONFLICT (plan_id, feature_key) DO NOTHING;

-- ============================================================================
-- What is deliberately NOT here
--
-- * A `renewals` table. D7 has no recurring charging, so a renewal is a new subscription, not an
--   event on an old one.
-- * A `plan_version`. Editing a live plan is refused by SubscriptionService while an active
--   subscriber holds it, which is a stronger guarantee than a version column nobody updates.
-- * A currency column. One country (D5), one currency, `price_minor` in paisa like everything else.
-- ============================================================================
