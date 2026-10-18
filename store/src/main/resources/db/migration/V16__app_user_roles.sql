-- ============================================================================
-- Phase 7: roles on app_user
--
-- The admin's user list has to answer "is this account an admin?", and there was nowhere to ask.
-- `app_user` had a realm (ops / customers) but no roles, so an `ops` account that is a platform
-- admin was indistinguishable from an `ops` account that owns a shop.
--
-- That mattered concretely: the guard that stops an admin suspending another admin needs to know who
-- is an admin, and with no roles column the only honest options were to omit the guard (any admin
-- could lock every operator out of the platform) or to ask Keycloak on every check (a dependency on
-- the identity provider for a question this platform can answer itself).
--
-- Populated from the token by IdentitySync on every request, so it is as current as the roles are.
-- ============================================================================

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS roles text;

COMMENT ON COLUMN app_user.roles IS
    'Comma-separated authorities from the token at last sign-in, e.g. ROLE_ADMIN,ROLE_SHOP. Nullable:
     an account created by seed data rather than a login has none yet.';

-- The admin's people list filters by role, so a partial index on the admins is small and exact.
CREATE INDEX IF NOT EXISTS idx_app_user_admin
    ON app_user (realm, status)
    WHERE roles LIKE '%ROLE_ADMIN%';