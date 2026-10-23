-- ============================================================================
-- Phase 8.5: shop onboarding
--
-- One column, and it exists because the alternative was a guess.
--
-- <p>Without it, "has this owner finished signing up?" could only be answered by checking whether
-- the shop is still called "My shop". That is a string comparison standing in for a fact, and it
-- fails in two directions at once: an owner who genuinely calls their shop "My shop" looks like they
-- never signed up and are sent through the wizard again, and any change to the placeholder text
-- silently breaks every brand new shop in the platform.
--
-- <p>Set once, by the sign-up call that validates a real name and a real vertical. Never cleared.
-- ============================================================================

ALTER TABLE shop
    ADD COLUMN IF NOT EXISTS profile_completed_at timestamptz;

-- Idempotent add, for a database where the ADD above has already been applied by a hand run.
-- There is deliberately no default: `NULL` means "has not completed", and a default of now() would
-- make every shop look onboarded the moment the migration ran.
ALTER TABLE shop
    ADD COLUMN IF NOT EXISTS profile_completed_at timestamptz;

COMMENT ON COLUMN shop.profile_completed_at IS
    'When the owner finished shop sign-up. NULL for a shop still carrying the auto-provisioned '
    'placeholder, which is what the console uses to route somebody to the onboarding step. Set once '
    'and never cleared: a shop that has traded is never un-onboarded.';

-- ----------------------------------------------------------------------------
-- Backfill -- and this is the part that matters
--
-- <p>Without it, **every existing shop owner is locked out of their own console.** A verified,
-- trading shop created before this feature has a NULL timestamp, the onboarding guard reads that
-- as "has never signed up", and redirects them to the sign-up form -- from which they cannot get
-- back to their products, orders or wallet. The screen still renders; it just renders the wrong
-- screen for everybody who already had a shop.
--
-- <p>The obvious backfill -- stamp every row -- is wrong for the placeholder shops, which are
-- genuinely unfinished. The one that is right distinguishes them by the auto-generated name, and
-- the fact that it leans on a string is the point rather than a flaw: this is a **one-shot**,
-- at-the-moment-the-migration-runs judgement about which rows predate a feature, and "did this row
-- come from the provisioner or from a person" is exactly what the placeholder name records.
-- Runtime never compares that string -- it reads the timestamp this sets, which is why the two
-- approaches can coexist without the fragile one becoming load-bearing.
-- ----------------------------------------------------------------------------

UPDATE shop
SET profile_completed_at = now()
WHERE profile_completed_at IS NULL
  AND (name IS DISTINCT FROM 'My shop' OR status <> 'DRAFT');

COMMENT ON COLUMN shop.profile_completed_at IS
    'When the owner finished shop sign-up. Backfilled at V18 for every shop that predates the '
    'sign-up flow, which is everything except the auto-provisioned placeholder -- a verified shop '
    'created before this feature was obviously set up, and leaving its timestamp NULL would lock '
    'its owner out of their own console.';