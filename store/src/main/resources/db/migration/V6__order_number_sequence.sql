-- Per-shop order-number counter.
--
-- The alternative, MAX(order_number) + 1 over shop_order, is the classic way to produce two
-- orders with the same number: two checkouts read the same maximum and both write ORD-0042, and
-- one customer gets an order number that also appears on someone else's invoice. A dedicated
-- counter row, locked with SELECT ... FOR UPDATE, makes that impossible and lets two different
-- shops allocate numbers without waiting on each other.
CREATE TABLE IF NOT EXISTS order_number_sequence (
    shop_id    uuid        PRIMARY KEY REFERENCES shop (id) ON DELETE CASCADE,
    -- The highest number issued for this shop. Numbers are per shop, not global: the pair
    -- (shop_id, order_number) is what must be unique, and a shop's own customers are the only
    -- people who ever see its numbers.
    last_number bigint     NOT NULL DEFAULT 0,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT order_number_non_negative CHECK (last_number >= 0)
);

-- ---------------------------------------------------------------------------
-- order_item.created_at
--
-- Order lines extend BaseEntity like most entities, but the table as written had neither
-- created_at nor updated_at. created_at is genuinely needed here: the order line's position in
-- the basket is the order the customer added them, and "lines in the order they were added" is a
-- query the invoice, the order board and the customer's basket all need. There is no other column
-- that expresses it — the line's id is a random UUID, which sorts arbitrarily.
--
-- updated_at is not added, because an order line is a frozen snapshot. Nothing on it is ever
-- edited after the order is placed; a change to it would be a change to what the customer was
-- charged, which is exactly what the snapshot exists to prevent.
ALTER TABLE order_item
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
