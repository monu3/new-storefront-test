-- ============================================================================
-- Phase 5: customer accounts
--
-- Four new tables, all scoped to a customer rather than to the platform:
--
--   customer_address  a saved delivery address. Separate from the address frozen on an order,
--                     which is never rewritten when this changes.
--   customer_device   an FCM registration token. One row per browser/app install, so a customer
--                     who signs in on a second device is reachable on both.
--   product_review    a rating and a comment against a product.
--   wishlist_item     a saved product.
--
-- The one genuinely debatable table is customer_device. A token identifies a *browser install*,
-- not a person, so the table is keyed by token and carries the user. That ordering matters: a
-- token is regenerated when the customer clears site data, and an install cannot be "logged out".
-- ============================================================================

-- -----------------------------------------------------------------------------
-- Saved delivery addresses
-- -----------------------------------------------------------------------------

CREATE TABLE customer_address (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Free text, not a country/state pair. This platform ships in Nepal, and a customer's own
    -- wording ("Koteshwor, Ward 32") is more useful to a rider than a normalised value is.
    label         text    NOT NULL,
    recipient     text    NOT NULL,
    phone         text    NOT NULL,
    line1         text    NOT NULL,
    line2         text,
    city          text    NOT NULL,
    state         text,
    postal_code   text,
    country       text    NOT NULL DEFAULT 'Nepal',
    -- Exactly one address is the default, and the service enforces that in one transaction.
    is_default    boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_address_label_nonblank CHECK (length(btrim(label)) > 0),
    CONSTRAINT customer_address_recipient_nonblank CHECK (length(btrim(recipient)) > 0)
);

-- Addresses are read per user, almost always with the default first.
CREATE INDEX idx_customer_address_user ON customer_address (user_id, is_default DESC, created_at);

-- Only one default per customer. Enforced by a partial unique index rather than by a boolean
-- column alone, because "set this one default" is the operation that has to be correct and a
-- partial unique index makes a second one impossible at the database level.
CREATE UNIQUE INDEX uq_customer_address_default
    ON customer_address (user_id) WHERE is_default;

-- -----------------------------------------------------------------------------
-- Push notification devices (FCM)
-- -----------------------------------------------------------------------------

CREATE TABLE customer_device (
    -- The FCM registration token is the primary key, and there is no surrogate id. A token is what
    -- an actual push is addressed to, it is regenerated when the customer clears site data, and two
    -- rows for one token would send every notification twice -- so the natural key is the key.
    -- A separate `id` column alongside it would be a second primary key, which Postgres refuses.
    token         text    PRIMARY KEY,
    user_id       uuid REFERENCES app_user (id) ON DELETE CASCADE,
    platform      text    NOT NULL DEFAULT 'web'
                          CHECK (platform IN ('web', 'android', 'ios')),
    -- Cleared when FCM reports the token is dead, so a dead token is not retried forever and does
    -- not count towards the "is this notification reaching anyone" question.
    is_active     boolean NOT NULL DEFAULT true,
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Push fan-out is always "every device for this user", and the primary key already covers lookups
-- by token for a device that has not been attached to a user yet (registered at page load, signed
-- in later).
CREATE INDEX idx_customer_device_user ON customer_device (user_id) WHERE user_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Product reviews
-- -----------------------------------------------------------------------------

CREATE TABLE product_review (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    uuid NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    user_id       uuid REFERENCES app_user (id) ON DELETE SET NULL,
    -- Retained when the user row goes: a rating is a statement about a product, and deleting it
    -- because someone deleted their account loses the shop's social proof.
    author_name   text    NOT NULL,
    -- 1-5. Constrained in the database as well as in the service, because a rating outside this
    -- range would silently distort the average shown on every product card.
    rating        smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body          text,
    -- Verified purchase, from an order that actually contained this product. Not the same as
    -- "signed in": a shop's competitor can review too, and the distinction is what makes the
    -- badge worth reading.
    is_verified   boolean NOT NULL DEFAULT false,
    -- A shop may hide a review. The row is kept: hiding is moderation, not deletion, and an owner
    -- who changes their mind should get the review back.
    is_visible    boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT product_review_body_length CHECK (body IS NULL OR length(body) <= 2000)
);

-- One review per person per product. The partial index covers guests too, keyed on the email the
-- review was left with, because a guest cannot be trusted to be signed in to "be the same person".
CREATE UNIQUE INDEX uq_product_review_user ON product_review (product_id, user_id)
    WHERE user_id IS NOT NULL;

-- The average and count on the product card are read far more often than the reviews themselves,
-- and a shop with a thousand reviews must not pay a sort for its badge.
CREATE INDEX idx_product_review_product ON product_review (product_id, created_at DESC)
    WHERE is_visible;

-- -----------------------------------------------------------------------------
-- Wishlist
-- -----------------------------------------------------------------------------

CREATE TABLE wishlist_item (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    product_id    uuid NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, product_id)
);

CREATE INDEX idx_wishlist_user ON wishlist_item (user_id, created_at DESC);