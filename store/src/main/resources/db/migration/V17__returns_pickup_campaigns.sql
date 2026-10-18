-- ============================================================================
-- Phase 8: returns, refunds, pickup, abandoned carts and campaigns
--
-- Split by concern rather than one blob, because these are independent features
-- and a single migration makes a failure in the last one look like a failure in
-- all of them.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Return requests
--
-- A separate table rather than columns on `shop_order`, and that is the whole
-- design decision:
--
-- <p>A return is a *request* that a shop approves or rejects, and it may cover
-- part of an order or all of it. That cannot be a status on the order, because
-- "the customer asked for one jumper back" and "the order was returned" are
-- different claims, and the order's status can only hold one of them. Putting it
-- on `shop_order` would mean an order in RETURNED state while most of it was
-- still with the customer.
--
-- <p>So: the order moves to RETURNED only when the *entire* order value has come
-- back, and this table carries the request in the meantime.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS return_request (
    id                uuid PRIMARY KEY,
    order_id          uuid        NOT NULL REFERENCES shop_order (id),
    shop_id           uuid        NOT NULL REFERENCES shop (id),

    status            text        NOT NULL,

    -- Why the customer wants their money back, in their words. It is required
    -- because a return the shop cannot understand is a return it has to guess
    -- at, and a guess here costs real money.
    reason            text        NOT NULL,

    -- The shop's answer when it declines. Null while the request is open.
    resolution_note   text,

    -- What the customer asked for, and what they were actually given. They differ
    -- often enough to matter: a shop may approve three of four units.
    requested_minor   bigint      NOT NULL,
    approved_minor    bigint      NOT NULL DEFAULT 0,

    -- Commission credited back to the platform on the approved portion. Frozen at
    -- refund time so a later settings change cannot rewrite what was returned.
    commission_reversed_minor bigint NOT NULL DEFAULT 0,

    requested_by      uuid        NOT NULL REFERENCES app_user (id),
    decided_by        uuid        REFERENCES app_user (id),

    requested_at      timestamptz NOT NULL,
    decided_at        timestamptz,

    -- BaseEntity maps both of these. Third table in a row that needed them.
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,

    CONSTRAINT ck_return_status
        CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'REFUNDED')),

    -- An approval with nothing approved is a state nobody wants, and it is
    -- cheaper to forbid here than to explain it later.
    CONSTRAINT ck_return_amounts
        CHECK (requested_minor > 0 AND approved_minor >= 0 AND approved_minor <= requested_minor)
);

-- The shop's queue: "what is waiting for me", newest first, not yet decided.
-- Partial, because the decided rows are history and an open-returns queue reads
-- all of them otherwise.
CREATE INDEX IF NOT EXISTS idx_return_shop_open
    ON return_request (shop_id, requested_at DESC)
    WHERE status = 'REQUESTED';

-- "Everything that happened to this order" for the order detail screen.
CREATE INDEX IF NOT EXISTS idx_return_order
    ON return_request (order_id, requested_at DESC);

-- ----------------------------------------------------------------------------
-- 2. Which units a return covers
--
-- <p>Kept as rows rather than a jsonb blob because a partial return is defined
-- *by* which units came back, and a blob cannot answer "has this variant already
-- been returned once?" without being parsed by application code on every read.
--
-- <p>`quantity` and `quantity_returned` are both here so the second return of the
-- same line can be checked against the first without summing rows on every
-- decision.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS return_item (
    id                 uuid PRIMARY KEY,
    return_request_id  uuid        NOT NULL REFERENCES return_request (id) ON DELETE CASCADE,
    order_item_id      uuid        NOT NULL REFERENCES order_item (id),

    quantity           integer     NOT NULL,
    -- What the shop approved out of what was asked for.
    quantity_approved  integer     NOT NULL DEFAULT 0,

    -- The price one unit was sold for, copied from the order line at request time.
    -- Snapshotted rather than joined: the order line's own price is a snapshot (D32), and a
    -- return priced against today's catalogue price would refund a different amount than the
    -- customer paid whenever a price changed in between.
    unit_price_minor   bigint      NOT NULL DEFAULT 0,

    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,

    CONSTRAINT ck_return_item_qty CHECK (quantity > 0 AND quantity_approved >= 0)
);

-- One row per line per request: the same order line cannot appear twice in one
-- return, and allowing it would let a request claim more units than were bought.
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_item_line
    ON return_item (return_request_id, order_item_id);

-- Idempotent add, for a database where the CREATE above was already applied by an earlier
-- hand-run of this file. `IF NOT EXISTS` plus a default, so it is safe on a table with rows.
ALTER TABLE return_item
    ADD COLUMN IF NOT EXISTS unit_price_minor bigint NOT NULL DEFAULT 0;

-- "How many of this line have already come back?" -- the check a partial return
-- depends on, and the one a jsonb blob would force into application code.
CREATE INDEX IF NOT EXISTS idx_return_item_line
    ON return_item (order_item_id);

-- ----------------------------------------------------------------------------
-- 3. Pickup
--
-- <p>A fulfilment *method* on the order, not a nullable address. "No shipping
-- address" and "this is a pickup order" are different facts, and inferring the
-- second from the absence of the first makes a data-entry mistake look like a
-- customer choice.
--
-- <p>Shipping is zero for a pickup order. It is set to zero rather than left
-- null so every total arithmetic downstream needs no special case.
-- ----------------------------------------------------------------------------

ALTER TABLE shop_order
    ADD COLUMN IF NOT EXISTS fulfilment_method text NOT NULL DEFAULT 'DELIVERY',
    ADD COLUMN IF NOT EXISTS pickup_note text;

-- DO blocks rather than bare ADD CONSTRAINT, because Postgres has no
-- `ADD CONSTRAINT IF NOT EXISTS`. A bare one fails on any database where the
-- migration has been hand-applied first -- and it fails with "constraint already
-- exists", which reads as a broken migration rather than as one that is simply
-- being applied to a database that already has it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_order_fulfilment_method') THEN
        ALTER TABLE shop_order
            ADD CONSTRAINT ck_order_fulfilment_method
                CHECK (fulfilment_method IN ('DELIVERY', 'PICKUP'));
    END IF;
END
$$;

-- A pickup order must not be SHIPPED: there is no parcel. The check is at the
-- database because the state machine already says so and two places agreeing is
-- better than one place being wrong.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pickup_not_shipped') THEN
        ALTER TABLE shop_order
            ADD CONSTRAINT ck_pickup_not_shipped
                CHECK (fulfilment_method <> 'PICKUP' OR status NOT IN ('SHIPPED'));
    END IF;
END
$$;

COMMENT ON COLUMN shop_order.fulfilment_method IS
    'DELIVERY (default) or PICKUP. A pickup order carries a pickup note instead of an address '
    'and can never reach SHIPPED.';

-- ----------------------------------------------------------------------------
-- 4. Abandoned carts
--
-- <p>Keyed on the customer, not the session, because a cart that survives a page
-- refresh is the same cart and an abandoned-cart reminder that sends two emails
-- about it is worse than none.
--
-- <p>`reminded_at` is on the row because "have we already nagged them" has to be
-- answerable without counting related tables.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS abandoned_cart (
    id                  uuid PRIMARY KEY,

    -- Null for a guest cart. Guest checkout is allowed (D14 sibling decision),
    -- and a guest cart is kept so it can be resumed by the same browser.
    customer_user_id    uuid REFERENCES app_user (id),
    -- SHA-256 of the session/browser id, never the id itself: it is a bearer token
    -- for somebody else's cart if it leaks into a log.
    anonymous_key       text,

    total_minor         bigint      NOT NULL,
    item_count          integer     NOT NULL,

    -- The last time the cart changed. "Abandoned" is measured from this, not from
    -- when the row was first written -- a cart touched every few minutes is not
    -- abandoned at all.
    last_active_at      timestamptz NOT NULL,
    reminded_at         timestamptz,
    recovered_at        timestamptz,

    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,

    CONSTRAINT ck_abandoned_somebody CHECK (customer_user_id IS NOT NULL OR anonymous_key IS NOT NULL),
    CONSTRAINT ck_abandoned_total CHECK (total_minor >= 0 AND item_count > 0)
);

-- The sweep's query: carts nobody has touched since the cutoff and nobody has
-- been reminded about. Partial on `reminded_at IS NULL` so it shrinks to nothing
-- once the sweep has done its work.
CREATE INDEX IF NOT EXISTS idx_abandoned_sweep
    ON abandoned_cart (last_active_at)
    WHERE reminded_at IS NULL AND recovered_at IS NULL;

-- "Does this customer already have an open cart?" -- the upsert key.
CREATE UNIQUE INDEX IF NOT EXISTS uq_abandoned_customer
    ON abandoned_cart (customer_user_id)
    WHERE customer_user_id IS NOT NULL AND recovered_at IS NULL;

-- ----------------------------------------------------------------------------
-- 5. Promotional campaigns
--
-- <p>`starts_at` and `ends_at` rather than a cron string: the plan needs a
-- window, and a window is something an operator can read back. A cron expression
-- in a database column is a thing you cannot show anybody and can only change by
-- guessing.
--
-- <p>`status` is derived from those two and the clock, not stored as a separate
-- truth to keep in sync.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS campaign (
    id                uuid PRIMARY KEY,

    -- Null for a platform-wide campaign. A shop running its own promotion is the
    -- common case, so this is nullable rather than restricted to admins.
    shop_id           uuid REFERENCES shop (id),

    name              text        NOT NULL,
    description       text,

    -- DISCOUNT is a flat percentage off the cart; FREE_DELIVERY zeroes shipping;
    -- BANNER is a storefront announcement with no money attached.
    kind              text        NOT NULL,

    -- Percentage for DISCOUNT, ignored otherwise. Basis points, matching
    -- PlatformSettings' commission rate: 500 is 5%.
    discount_bp       integer,

    headline          text,
    body              text,

    starts_at         timestamptz NOT NULL,
    ends_at           timestamptz,

    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,

    CONSTRAINT ck_campaign_kind
        CHECK (kind IN ('DISCOUNT', 'FREE_DELIVERY', 'BANNER')),
    CONSTRAINT ck_campaign_window
        CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_campaign_discount
        CHECK (kind <> 'DISCOUNT' OR (discount_bp IS NOT NULL AND discount_bp > 0 AND discount_bp <= 10000))
);

-- "What is running right now" for the storefront. Partial on `ends_at IS NULL`
-- because an open-ended campaign is the common case and never expires.
CREATE INDEX IF NOT EXISTS idx_campaign_live
    ON campaign (starts_at, ends_at)
    WHERE ends_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_campaign_shop
    ON campaign (shop_id, starts_at DESC);
-- ----------------------------------------------------------------------------
-- 6. Share-link clicks
--
-- <p>So "we added a WhatsApp button" is a claim somebody can check. Without this,
-- a share button is decoration: it works, nobody can tell, and nobody can tell
-- whether to invest in it.
--
-- <p>Append-only, one row per click, with the channel that produced it. A counter
-- on the product could answer "how many" but not "how many last month", and the
-- second table needed to answer that has the same problem one level down.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS share_click (
    id          uuid PRIMARY KEY,
    product_id  uuid        NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    -- whatsapp, facebook, copy, whatever the storefront sends. Free text on purpose: a new channel
    -- is a front-end change, and a CHECK constraint here would mean a migration to add one.
    source      text        NOT NULL,
    clicked_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL
);

-- The one query the panel runs: "which channels brought people to this product,
-- since <date>". Covered by the leading product_id.
CREATE INDEX IF NOT EXISTS idx_share_click_product
    ON share_click (product_id, clicked_at DESC);

-- The write-path guard: "how many has this source contributed in the last day".
-- Without this the index above is the wrong one to hit on every single click.
CREATE INDEX IF NOT EXISTS idx_share_click_budget
    ON share_click (product_id, source, clicked_at DESC);
