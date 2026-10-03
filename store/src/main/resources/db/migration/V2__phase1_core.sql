-- =============================================================================
-- V2: Phase 1 core schema
--
-- Identity, tenancy, catalog, inventory, orders, payments, wallet.
--
-- Two rules that shape everything below:
--
--  1. All money is BIGINT paisa. Never float, never NUMERIC. A rupee amount of
--     1250.50 is stored as 125050. Float arithmetic in a ledger is how rounding
--     bugs get in and stay.
--
--  2. The wallet ledger is append-only and is the sole source of truth. There is
--     no balance column anywhere. shop_wallet_balance is a read cache rebuilt
--     from SUM(...) and reconciled nightly; if it drifts, the alert fires.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Identity
-- -----------------------------------------------------------------------------

-- Mirror of Keycloak users. Keycloak owns authentication; this holds the app
-- profile. `realm` records which of the two realms issued the token (D3), so a
-- row can never be used to cross the boundary.
CREATE TABLE app_user (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    realm             text        NOT NULL CHECK (realm IN ('ops', 'customers')),
    keycloak_sub      text        NOT NULL UNIQUE,
    email             citext      NOT NULL,
    full_name         text,
    phone             text,
    avatar_url        text,
    status            text        NOT NULL DEFAULT 'ACTIVE'
                                  CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    email_verified_at timestamptz,
    last_login_at     timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_user_email ON app_user (email);
CREATE INDEX idx_app_user_status ON app_user (status) WHERE status = 'ACTIVE';

-- -----------------------------------------------------------------------------
-- Shop
-- -----------------------------------------------------------------------------

-- shop_type is a hardcoded enum code validated in Java (D17), not a foreign key.
-- There is no category table and no sub-category level (D17/D18).
CREATE TABLE shop (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id     uuid        NOT NULL UNIQUE REFERENCES app_user (id),
    slug              citext      NOT NULL UNIQUE,
    name              text        NOT NULL,
    description       text,
    shop_type         text        NOT NULL,
    logo_url          text,
    favicon_url       text,
    cover_url         text,
    status            text        NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'SUSPENDED')),
    verification_note text,
    verified_at       timestamptz,
    -- Denormalised from the active subscription. Commission is debited at order
    -- accept based on this, so it must be readable without a join.
    commission_plan   text        NOT NULL DEFAULT 'FREE'
                        CHECK (commission_plan IN ('FREE', 'SUBSCRIBED')),
    follower_count    integer     NOT NULL DEFAULT 0 CHECK (follower_count >= 0),
    published_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- Uppercase so a hand-edited row cannot smuggle in an unrecognised code; the
    -- real validation is CategoryValidator.
    CONSTRAINT shop_type_is_uppercase CHECK (shop_type = upper(shop_type))
);
CREATE INDEX idx_shop_status ON shop (status);
CREATE INDEX idx_shop_type ON shop (shop_type);
CREATE INDEX idx_shop_published ON shop (published_at) WHERE status = 'VERIFIED';

-- Long-tail settings as JSONB. The columns that are queried or constrained stay
-- relational; the rest is a bag of options that changes with the UI.
CREATE TABLE shop_setting (
    shop_id          uuid PRIMARY KEY REFERENCES shop (id) ON DELETE CASCADE,
    general          jsonb NOT NULL DEFAULT '{}'::jsonb,
    address          jsonb NOT NULL DEFAULT '{}'::jsonb,
    social           jsonb NOT NULL DEFAULT '{}'::jsonb,
    checkout         jsonb NOT NULL DEFAULT '{}'::jsonb,
    notifications    jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- next=1 is advanced inside a transaction when an order number is allocated,
    -- so two concurrent orders cannot collide on the same number.
    order_number     jsonb NOT NULL DEFAULT '{"format":"ORD-{YYYY}-{####}","next":1}'::jsonb,
    invoice_template jsonb NOT NULL DEFAULT '{}'::jsonb,
    email_templates  jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- D15: custom CSS is stored but never rendered until the sanitizer exists.
    appearance_extra jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Catalog
-- -----------------------------------------------------------------------------

CREATE TABLE shop_category (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id     uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    -- The shop's own display grouping, distinct from the hardcoded
    -- product_category. Purely cosmetic navigation.
    name        text    NOT NULL,
    slug        citext  NOT NULL,
    description text,
    image_url   text,
    sort_order  integer NOT NULL DEFAULT 0,
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shop_id, slug)
);
CREATE INDEX idx_shop_category_shop ON shop_category (shop_id, sort_order);

CREATE TABLE product (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id           uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    shop_category_id  uuid    REFERENCES shop_category (id) ON DELETE SET NULL,
    -- Hardcoded enum code, validated against shop.shop_type (D18).
    product_category  text    NOT NULL,
    name              text    NOT NULL,
    slug              citext  NOT NULL,
    short_description text,
    description       text,
    status            text    NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    base_price_minor  bigint  NOT NULL CHECK (base_price_minor >= 0),
    compare_at_minor  bigint  CHECK (compare_at_minor IS NULL OR compare_at_minor > 0),
    -- Shop-private margin data. Never exposed in any public DTO.
    cost_price_minor  bigint  CHECK (cost_price_minor IS NULL OR cost_price_minor >= 0),
    weight_grams      integer CHECK (weight_grams IS NULL OR weight_grams >= 0),
    track_inventory   boolean NOT NULL DEFAULT true,
    allow_backorder   boolean NOT NULL DEFAULT false,
    is_featured       boolean NOT NULL DEFAULT false,
    seo_title         text,
    seo_description   text,
    sold_count        integer NOT NULL DEFAULT 0,
    view_count        integer NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    UNIQUE (shop_id, slug)
);
CREATE INDEX idx_product_shop_status ON product (shop_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_product_shop_category ON product (shop_id, product_category) WHERE deleted_at IS NULL;
CREATE INDEX idx_product_category ON product (product_category) WHERE deleted_at IS NULL AND status = 'ACTIVE';

CREATE TABLE product_variant (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id       uuid    NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sku              text,
    name             text    NOT NULL,
    option_values    jsonb   NOT NULL DEFAULT '{}'::jsonb,
    price_minor      bigint  NOT NULL CHECK (price_minor >= 0),
    compare_at_minor bigint  CHECK (compare_at_minor IS NULL OR compare_at_minor > 0),
    barcode          text,
    image_url        text,
    sort_order       integer NOT NULL DEFAULT 0,
    is_active        boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_product_variant_sku ON product_variant (product_id, sku) WHERE sku IS NOT NULL;
CREATE INDEX idx_product_variant_product ON product_variant (product_id, sort_order) WHERE is_active;

-- Every Cloudinary asset in the system (D19). Replaces the three separate media
-- tables in v1 of the plan: one table makes "the shop's media library" a single
-- query, which is a real screen in the Appearance section.
--
-- public_id is required to delete or transform an image. Storing only the URL
-- would make every image undeletable and leak storage cost forever.
CREATE TABLE media (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id            uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    product_id         uuid    REFERENCES product (id) ON DELETE CASCADE,
    variant_id         uuid    REFERENCES product_variant (id) ON DELETE CASCADE,
    usage              text    NOT NULL
                           CHECK (usage IN ('PRODUCT_IMAGE', 'VARIANT_IMAGE', 'SHOP_LOGO',
                                            'SHOP_FAVICON', 'SHOP_COVER', 'BANNER',
                                            'THEME_IMAGE', 'PAYMENT_QR', 'RECEIPT_PROOF')),
    -- Convention: shop/{shopId}/products/{productId}/... (D19)
    cloudinary_folder text    NOT NULL,
    public_id          text    NOT NULL,
    url                text    NOT NULL,
    secure_url         text    NOT NULL,
    width              integer,
    height             integer,
    format             text,
    bytes              integer,
    alt_text           text,
    sort_order         integer NOT NULL DEFAULT 0,
    created_by         uuid    REFERENCES app_user (id),
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_shop_usage ON media (shop_id, usage);
CREATE INDEX idx_media_product ON media (product_id) WHERE product_id IS NOT NULL;
-- public_id is unique per Cloudinary account; storing it this way also makes a
-- duplicate upload detectable rather than silently creating a second row.
CREATE UNIQUE INDEX uq_media_public_id ON media (public_id);

-- -----------------------------------------------------------------------------
-- Inventory
-- -----------------------------------------------------------------------------

CREATE TABLE inventory (
    product_id          uuid    NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    variant_id          uuid    NOT NULL REFERENCES product_variant (id) ON DELETE CASCADE,
    quantity_on_hand    integer NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0),
    quantity_reserved   integer NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),
    low_stock_threshold integer NOT NULL DEFAULT 5 CHECK (low_stock_threshold >= 0),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, variant_id),
    -- Reserving can never exceed what is on hand.
    CONSTRAINT inventory_reserved_within_hand CHECK (quantity_reserved <= quantity_on_hand)
);

-- Append-only audit of every stock movement. Never updated, never deleted: a
-- dispute about a quantity has to be answerable months later.
CREATE TABLE inventory_movement (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id     uuid        NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    variant_id     uuid        NOT NULL REFERENCES product_variant (id) ON DELETE CASCADE,
    delta          integer     NOT NULL,
    reason         text        NOT NULL
                       CHECK (reason IN ('SALE', 'RESTOCK', 'ADJUSTMENT', 'RETURN', 'CANCEL', 'IMPORT')),
    reference_type text,
    reference_id   uuid,
    note           text,
    created_by     uuid        REFERENCES app_user (id),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_inventory_movement_variant ON inventory_movement (product_id, variant_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- Customer (audience) — a customer as seen by ONE shop
-- -----------------------------------------------------------------------------

CREATE TABLE customer (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id            uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    -- Null for a guest checkout, which is why email is the natural key here.
    user_id            uuid    REFERENCES app_user (id) ON DELETE SET NULL,
    email              citext  NOT NULL,
    full_name          text,
    phone              text,
    total_orders       integer NOT NULL DEFAULT 0 CHECK (total_orders >= 0),
    total_spent_minor  bigint  NOT NULL DEFAULT 0 CHECK (total_spent_minor >= 0),
    last_order_at      timestamptz,
    -- Shop-private. Never returned to the customer.
    notes              text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shop_id, email)
);
CREATE INDEX idx_customer_shop_spend ON customer (shop_id, total_spent_minor DESC);

CREATE TABLE shop_follower (
    shop_id    uuid        NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_id, user_id)
);
CREATE INDEX idx_shop_follower_user ON shop_follower (user_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- Shipping and tax
-- -----------------------------------------------------------------------------

CREATE TABLE shipping_zone (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id           uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    name              text    NOT NULL,
    -- Empty array = the default zone, used when nothing else matches.
    cities            text[]  NOT NULL DEFAULT '{}',
    rate_minor        bigint  NOT NULL DEFAULT 0 CHECK (rate_minor >= 0),
    free_over_minor   bigint  CHECK (free_over_minor IS NULL OR free_over_minor > 0),
    min_days          integer NOT NULL DEFAULT 1 CHECK (min_days >= 0),
    max_days          integer NOT NULL DEFAULT 7 CHECK (max_days >= min_days),
    is_active         boolean NOT NULL DEFAULT true,
    sort_order        integer NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_shipping_zone_shop ON shipping_zone (shop_id) WHERE is_active;

-- rate_bp is basis points: 1300 = 13%. Integer arithmetic on rates, so there is
-- no rounding drift across a hundred thousand orders.
CREATE TABLE tax_rule (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id    uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    name       text    NOT NULL,
    rate_bp    integer NOT NULL CHECK (rate_bp >= 0 AND rate_bp <= 10000),
    inclusive  boolean NOT NULL DEFAULT false,
    is_active  boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tax_rule_shop ON tax_rule (shop_id) WHERE is_active;

CREATE TABLE discount (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id             uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    code                citext,
    name                text    NOT NULL,
    type                text    NOT NULL
                            CHECK (type IN ('PERCENT', 'FIXED', 'FREE_SHIPPING')),
    -- Percent: basis points. Fixed: minor units. Meaning depends on type.
    value               bigint  NOT NULL CHECK (value >= 0),
    min_order_minor     bigint  CHECK (min_order_minor IS NULL OR min_order_minor >= 0),
    max_discount_minor  bigint  CHECK (max_discount_minor IS NULL OR max_discount_minor >= 0),
    usage_limit         integer CHECK (usage_limit IS NULL OR usage_limit > 0),
    usage_used          integer NOT NULL DEFAULT 0 CHECK (usage_used >= 0),
    per_customer_limit  integer CHECK (per_customer_limit IS NULL OR per_customer_limit > 0),
    starts_at           timestamptz,
    ends_at             timestamptz,
    is_active           boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT discount_window_valid CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);
CREATE UNIQUE INDEX uq_discount_code ON discount (shop_id, code) WHERE code IS NOT NULL;
CREATE INDEX idx_discount_shop_active ON discount (shop_id) WHERE is_active;

-- -----------------------------------------------------------------------------
-- Payment methods (per shop; eSewa merchant config lives here)
-- -----------------------------------------------------------------------------

CREATE TABLE shop_payment_method (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id                uuid    NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    method                 text    NOT NULL CHECK (method IN ('ESAWA', 'COD', 'BANK_TRANSFER')),
    is_enabled             boolean NOT NULL DEFAULT true,
    -- eSewa merchant config, per shop. Encrypted at rest; the plaintext is never
    -- logged and never leaves the backend.
    esewa_merchant_id      text,
    esewa_secret_enc       bytea,
    bank_name              text,
    bank_account_name      text,
    bank_account_number_enc bytea,
    bank_qr_media_id       uuid    REFERENCES media (id) ON DELETE SET NULL,
    cod_fee_minor          bigint  NOT NULL DEFAULT 0 CHECK (cod_fee_minor >= 0),
    min_order_minor        bigint  NOT NULL DEFAULT 0 CHECK (min_order_minor >= 0),
    instructions           text,
    sort_order             integer NOT NULL DEFAULT 0,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shop_id, method)
);

-- -----------------------------------------------------------------------------
-- Orders
-- -----------------------------------------------------------------------------

CREATE TABLE shop_order (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number      text        NOT NULL,
    shop_id           uuid        NOT NULL REFERENCES shop (id) ON DELETE RESTRICT,
    -- Frozen at order time so a report on historical sales keeps its meaning even
    -- if the shop's type is later reclassified.
    shop_type_snapshot text        NOT NULL,
    customer_id       uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    guest_email       citext,
    guest_phone       text,
    status            text        NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'ACCEPTED', 'PROCESSING', 'SHIPPED',
                                          'DELIVERED', 'CANCELLED', 'RETURNED', 'REFUNDED')),
    payment_status    text        NOT NULL DEFAULT 'PENDING'
                        CHECK (payment_status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED',
                                                  'PARTIALLY_REFUNDED')),
    payment_method    text        NOT NULL CHECK (payment_method IN ('ESAWA', 'COD', 'BANK_TRANSFER')),
    subtotal_minor    bigint      NOT NULL CHECK (subtotal_minor >= 0),
    discount_minor    bigint      NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    tax_minor         bigint      NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
    shipping_minor    bigint      NOT NULL DEFAULT 0 CHECK (shipping_minor >= 0),
    total_minor       bigint      NOT NULL CHECK (total_minor >= 0),
    currency          char(3)     NOT NULL DEFAULT 'NPR',
    -- Commission is snapshotted at accept time. Changing the platform rate later
    -- must never retroactively alter a historical order (D2).
    commission_rate_bp integer   CHECK (commission_rate_bp IS NULL OR commission_rate_bp >= 0),
    commission_minor  bigint      CHECK (commission_minor IS NULL OR commission_minor >= 0),
    -- True when the shop is subscribed and pays no commission. Recorded explicitly
    -- rather than left as a null so the ledger entry has a reason.
    commission_waived boolean     NOT NULL DEFAULT false,
    -- Snapshotted: an edited customer address must not rewrite a shipped parcel.
    shipping_address  jsonb       NOT NULL,
    billing_address   jsonb,
    customer_note     text,
    admin_note        text,
    placed_at         timestamptz NOT NULL DEFAULT now(),
    accepted_at       timestamptz,
    shipped_at        timestamptz,
    delivered_at      timestamptz,
    cancelled_at      timestamptz,
    cancel_reason     text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shop_id, order_number),
    -- A guest order must supply contact details; a customer order uses customer_id.
    CONSTRAINT order_has_contact CHECK (customer_id IS NOT NULL OR guest_email IS NOT NULL)
);
CREATE INDEX idx_order_shop_status ON shop_order (shop_id, status, placed_at DESC);
CREATE INDEX idx_order_customer ON shop_order (customer_id, placed_at DESC) WHERE customer_id IS NOT NULL;
CREATE INDEX idx_order_placed ON shop_order (placed_at);
CREATE INDEX idx_order_open ON shop_order (shop_id) WHERE status IN ('PENDING', 'ACCEPTED', 'PROCESSING', 'SHIPPED');

CREATE TABLE order_item (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id         uuid    NOT NULL REFERENCES shop_order (id) ON DELETE CASCADE,
    product_id       uuid    REFERENCES product (id) ON DELETE SET NULL,
    variant_id       uuid    REFERENCES product_variant (id) ON DELETE SET NULL,
    -- Everything a deleted product would otherwise erase. A historical invoice
    -- must remain readable after the catalogue changes.
    product_name     text    NOT NULL,
    variant_name     text,
    sku              text,
    image_url        text,
    product_category text    NOT NULL,
    unit_price_minor bigint  NOT NULL CHECK (unit_price_minor >= 0),
    quantity         integer NOT NULL CHECK (quantity > 0),
    discount_minor   bigint  NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    tax_minor        bigint  NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
    line_total_minor bigint  NOT NULL CHECK (line_total_minor >= 0)
);
CREATE INDEX idx_order_item_order ON order_item (order_id);
CREATE INDEX idx_order_item_product ON order_item (product_id) WHERE product_id IS NOT NULL;

-- Append-only timeline. Powers the customer-facing status tracker and settles
-- any dispute about when something happened.
CREATE TABLE order_status_history (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    uuid        NOT NULL REFERENCES shop_order (id) ON DELETE CASCADE,
    from_status text,
    to_status   text        NOT NULL,
    changed_by  uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_history_order ON order_status_history (order_id, created_at);

CREATE TABLE payment_transaction (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id          uuid        NOT NULL REFERENCES shop_order (id) ON DELETE CASCADE,
    shop_id           uuid        NOT NULL REFERENCES shop (id) ON DELETE RESTRICT,
    method            text        NOT NULL CHECK (method IN ('ESAWA', 'COD', 'BANK_TRANSFER')),
    amount_minor      bigint      NOT NULL CHECK (amount_minor >= 0),
    gateway           text        NOT NULL CHECK (gateway IN ('ESEWA', 'COD', 'BANK', 'MANUAL')),
    gateway_ref       text,
    gateway_status    text        NOT NULL DEFAULT 'PENDING'
                        CHECK (gateway_status IN ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED')),
    -- Raw gateway response, kept for dispute resolution.
    request_payload   jsonb,
    verified_at       timestamptz,
    verified_by       uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    receipt_media_id  uuid        REFERENCES media (id) ON DELETE SET NULL,
    -- Makes a retried payment request a no-op rather than a second charge.
    idempotency_key   text        NOT NULL UNIQUE,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_tx_order ON payment_transaction (order_id);
CREATE INDEX idx_payment_tx_ref ON payment_transaction (gateway_ref) WHERE gateway_ref IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Wallet — the highest-risk area of the system (D2)
--
-- wallet_ledger_entry is append-only and is the ONLY source of truth. There is
-- no balance column. shop_wallet_balance exists purely as a read cache, written
-- in the same transaction as the ledger entry, and reconciled against SUM()
-- by a scheduled job that alerts on any drift.
-- -----------------------------------------------------------------------------

CREATE TABLE shop_wallet_balance (
    shop_id       uuid PRIMARY KEY REFERENCES shop (id) ON DELETE CASCADE,
    -- May go negative. That is allowed and alerted on, rather than blocked,
    -- because an admin override is a legitimate recovery path.
    balance_minor bigint NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wallet_ledger_entry (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id                uuid        NOT NULL REFERENCES shop (id) ON DELETE RESTRICT,
    entry_type             text        NOT NULL
                               CHECK (entry_type IN ('TOPUP_APPROVED', 'COMMISSION_DEBIT',
                                                     'COMMISSION_REVERSAL', 'SUBSCRIPTION_CHARGE',
                                                     'ADJUSTMENT_CREDIT', 'ADJUSTMENT_DEBIT',
                                                     'REFUND_DEBIT', 'PAYOUT')),
    direction              text        NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    -- Always positive; the sign comes from direction, so a negative amount can
    -- never be confused for a reversal.
    amount_minor           bigint      NOT NULL CHECK (amount_minor > 0),
    -- Running balance at the time of writing, for auditability without replaying
    -- the whole ledger.
    balance_after_minor    bigint      NOT NULL,
    order_id               uuid        REFERENCES shop_order (id) ON DELETE RESTRICT,
    payment_transaction_id uuid        REFERENCES payment_transaction (id) ON DELETE RESTRICT,
    -- Links a reversal to the debit it reverses.
    reversal_of            uuid        REFERENCES wallet_ledger_entry (id) ON DELETE RESTRICT,
    -- UNIQUE is what makes "exactly once" true. A retried accept cannot double-debit.
    idempotency_key        text        NOT NULL UNIQUE,
    description            text        NOT NULL,
    actor_user_id          uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    metadata               jsonb,
    created_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_wallet_ledger_shop ON wallet_ledger_entry (shop_id, created_at DESC);
CREATE INDEX idx_wallet_ledger_order ON wallet_ledger_entry (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_wallet_ledger_reversal ON wallet_ledger_entry (reversal_of) WHERE reversal_of IS NOT NULL;
-- A shop's balance is the sum of its ledger. This index is what keeps that sum
-- cheap enough to run nightly.
CREATE INDEX idx_wallet_ledger_balance ON wallet_ledger_entry (shop_id)
    INCLUDE (direction, amount_minor);

CREATE TABLE wallet_topup_request (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id         uuid        NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    method          text        NOT NULL CHECK (method IN ('ESAWA', 'BANK_TRANSFER')),
    reference       text,
    proof_media_id  uuid        REFERENCES media (id) ON DELETE SET NULL,
    note            text,
    status          text        NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by     uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    reviewed_at     timestamptz,
    review_note     text,
    ledger_entry_id uuid        REFERENCES wallet_ledger_entry (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_topup_shop_status ON wallet_topup_request (shop_id, status, created_at DESC);
CREATE INDEX idx_topup_pending ON wallet_topup_request (created_at) WHERE status = 'PENDING';

-- -----------------------------------------------------------------------------
-- Outbox — transactional event publishing (R5)
--
-- Publishing straight to RabbitMQ after a commit has a window where the DB has
-- committed and the message has not: the order exists but the customer is never
-- emailed. Writing the event in the same transaction as the state change, then
-- relaying it, closes that window completely.
-- -----------------------------------------------------------------------------

CREATE TABLE outbox_event (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    last_error     text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
-- Partial index on unpublished rows: the relay polls constantly, and the vast
-- majority of rows are already published.
CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- -----------------------------------------------------------------------------
-- Notifications and audit
-- -----------------------------------------------------------------------------

CREATE TABLE notification (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    audience          text        NOT NULL CHECK (audience IN ('SHOP', 'CUSTOMER', 'ADMIN')),
    recipient_user_id uuid        REFERENCES app_user (id) ON DELETE CASCADE,
    recipient_shop_id uuid        REFERENCES shop (id) ON DELETE CASCADE,
    channel           text        NOT NULL CHECK (channel IN ('EMAIL', 'PUSH', 'IN_APP')),
    type              text        NOT NULL,
    title             text        NOT NULL,
    body              text        NOT NULL,
    data              jsonb,
    status            text        NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts          integer     NOT NULL DEFAULT 0,
    last_error        text,
    sent_at           timestamptz,
    read_at           timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_user ON notification (recipient_user_id, created_at DESC);
CREATE INDEX idx_notification_shop ON notification (recipient_shop_id, created_at DESC)
    WHERE recipient_shop_id IS NOT NULL;
CREATE INDEX idx_notification_pending ON notification (created_at) WHERE status = 'PENDING';

CREATE TABLE audit_log (
    id            bigserial PRIMARY KEY,
    actor_user_id uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    actor_role    text,
    action        text        NOT NULL,
    entity_type   text        NOT NULL,
    entity_id     uuid,
    shop_id       uuid        REFERENCES shop (id) ON DELETE SET NULL,
    before_state  jsonb,
    after_state   jsonb,
    ip            inet,
    user_agent    text,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_shop ON audit_log (shop_id, created_at DESC);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_action ON audit_log (action, created_at DESC);

CREATE TABLE platform_setting (
    key         text PRIMARY KEY,
    value       jsonb       NOT NULL,
    updated_by  uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Seed the commission rate the plan fixes at 5% (D2). Editing it later must not
-- touch historical orders, because the rate is snapshotted onto each one.
INSERT INTO platform_setting (key, value)
VALUES ('commission_rate_bp', '500'::jsonb),
       ('platform_currency', '"NPR"'::jsonb),
       ('min_topup_minor', '100000'::jsonb)
ON CONFLICT (key) DO NOTHING;
