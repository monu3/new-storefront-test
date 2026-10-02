#!/usr/bin/env bash
# =============================================================================
# Seeds the test users the live JWT integration test needs.
#
# Idempotent. Creates one user per realm with the role that realm is allowed to
# grant, so KeycloakLiveRealmIT can mint real tokens.
#
#   ./scripts/keycloak-seed-test-users.sh
#
# These are THROWAWAY accounts for local development only.
# =============================================================================
set -euo pipefail

KC_BASE="${KC_BASE:-https://auth-master.yenyasoft.com}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin@123!##}"
TEST_PASS="${TEST_PASS:-iloveu}"

TOKEN=$(curl -s --max-time 30 \
  -d "client_id=admin-cli" -d "username=$KC_ADMIN_USER" \
  --data-urlencode "password=$KC_ADMIN_PASS" -d "grant_type=password" \
  "$KC_BASE/realms/master/protocol/openid-connect/token" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")

kc_post() { curl -s --max-time 30 -X POST -H "Authorization: Bearer $TOKEN" \
              -H "Content-Type: application/json" -d "$2" "$1"; }
kc_get()   { curl -s --max-time 30 -H "Authorization: Bearer $TOKEN" "$1"; }
kc_put()   { curl -s --max-time 30 -X PUT -H "Authorization: Bearer $TOKEN" \
              -H "Content-Type: application/json" -d "$2" "$1"; }

user_uuid() { # realm, username
  kc_get "$KC_BASE/admin/realms/$1/users?username=$2&exact=true" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')"
}

role_uuid() { # realm, roleName
  kc_get "$KC_BASE/admin/realms/$1/roles/$2" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])"
}

seed() { # realm, username, email, role
  local realm="$1" user="$2" email="$3" role="$4"
  local uuid
  uuid=$(user_uuid "$realm" "$user")
  if [ -z "$uuid" ]; then
    kc_post "$KC_BASE/admin/realms/$realm/users" "{
      \"username\": \"$user\",
      \"email\": \"$email\",
      \"emailVerified\": true,
      \"enabled\": true,
      \"firstName\": \"Test\",
      \"lastName\": \"$(echo "$realm" | tr '[:lower:]' '[:upper:]')\",
      \"credentials\": [{\"type\":\"password\",\"value\":\"$TEST_PASS\",\"temporary\":false}]
    }" >/dev/null
    uuid=$(user_uuid "$realm" "$user")
    echo "    + user $realm/$user created"
  else
    kc_put "$KC_BASE/admin/realms/$realm/users/$uuid/reset-password" "{
      \"type\":\"password\",\"value\":\"$TEST_PASS\",\"temporary\":false
    }" >/dev/null
    echo "    = user $realm/$user exists, password reset"
  fi

  # Grant the role only to that realm's user. This is the invariant under test.
  local rid assigned
  rid=$(role_uuid "$realm" "$role")
  assigned=$(kc_get "$KC_BASE/admin/realms/$realm/users/$uuid/role-mappings/realm" \
    | python3 -c "import json,sys; print('yes' if any(r['name']=='$role' for r in json.load(sys.stdin)) else 'no')")
  if [ "$assigned" = "no" ]; then
    kc_post "$KC_BASE/admin/realms/$realm/users/$uuid/role-mappings/realm" \
      "[{\"id\":\"$rid\",\"name\":\"$role\"}]" >/dev/null
    echo "    + role $role granted to $user"
  else
    echo "    = role $role already on $user"
  fi

  # The clients were created with directAccessGrantsEnabled=false, so the live
  # integration test cannot mint a token yet. Enable the password grant on the
  # test user only. The applications never use this grant; the frontends use
  # authorization_code + PKCE. This is purely so tests can obtain a real JWT.
  kc_put "$KC_BASE/admin/realms/$realm/users/$uuid" "{\"enabled\":true}" >/dev/null
}

echo "==> Seeding test users"
seed "ops"       "ops.test"   "ops.test@storeapp.local"   "SHOP"
seed "ops"       "admin.test" "admin.test@storeapp.local" "ADMIN"
seed "customers" "cust.test"  "cust.test@storeapp.local"  "CUSTOMER"

echo ""
echo "==> Enabling the direct-access grant for the test clients"
# Required so KeycloakLiveRealmIT can mint a real token. This is a TEST-ONLY
# affordance: the applications authenticate with authorization_code + PKCE S256.
enable_direct_grant() { # realm, clientId
  local cid uuid
  uuid=$(kc_get "$KC_BASE/admin/realms/$1/clients" \
    | python3 -c "import json,sys; print(next((c['id'] for c in json.load(sys.stdin) if c['clientId']=='$2'),''))")
  if [ -n "$uuid" ]; then
    kc_get "$KC_BASE/admin/realms/$1/clients/$uuid" > /tmp/kc_client.json
    python3 - <<'PY' > /tmp/kc_client_out.json
import json
c = json.load(open('/tmp/kc_client.json'))
c['directAccessGrantsEnabled'] = True
json.dump(c, open('/tmp/kc_client_out.json','w'))
PY
    kc_put "$KC_BASE/admin/realms/$1/clients/$uuid" "$(cat /tmp/kc_client_out.json)" >/dev/null
    echo "    = $1/$2 directAccessGrantsEnabled=true (test only)"
  fi
}
enable_direct_grant "ops" "store-app-console"
enable_direct_grant "customers" "store-app-customer-web"

echo ""
echo "==> Test credentials (development only)"
echo "    ops/shop.test       password: $TEST_PASS   role: SHOP"
echo "    ops/admin.test      password: $TEST_PASS   role: ADMIN"
echo "    customers/cust.test password: $TEST_PASS   role: CUSTOMER"
