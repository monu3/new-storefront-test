-- =============================================================================
-- V4: align column types with the JPA mappings
--
-- Found by `ddl-auto: validate`, which is doing its job: it refuses to start when an
-- entity and its table disagree.
-- =============================================================================

-- currency was char(3), which Hibernate maps as varchar and reports as a type mismatch.
-- varchar(3) is also the better choice: char(3) pads with spaces, so 'NPR ' != 'NPR'
-- in any comparison or index. ISO codes are fixed length anyway, so no information
-- is lost by relaxing the blank padding.
ALTER TABLE shop_order
    ALTER COLUMN currency TYPE varchar(3) USING btrim(currency);

-- item_category_snapshot was in the plan's schema (section 4.4) but missing from V2.
-- It is what keeps "how many KURTA orders in March" correct after a shop is reclassified,
-- so it is added here rather than left out.
ALTER TABLE shop_order
    ADD COLUMN IF NOT EXISTS item_category_snapshot jsonb;

COMMENT ON COLUMN shop_order.guest_email IS
    'Contact for a guest checkout, where customer_id is null. citext so a lookup '
    'matches regardless of how the address was typed.';

COMMENT ON COLUMN shop_order.item_category_snapshot IS
    'Frozen category per line, so a later shop reclassification cannot rewrite '
    'historical reports.';
