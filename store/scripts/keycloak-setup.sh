#!/usr/bin/env bash
# =============================================================================
# Phase 0 Step 0 — Keycloak realm setup for store.app
#
# Idempotent: safe to re-run. Reads an admin token, then creates only what is
# missing. Run from anywhere:  ./scripts/keycloak-setup.sh
#
# Both realms were verified pristine on 2026-10-01 (Keycloak default clients
# only, no custom roles), so nothing existing is overwritten.
# =============================================================================
set -euo pipefail

KC_BASE="${KC_BASE:-https://auth-master.yenyasoft.com}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin@123!##}"

OPS_REALM="ops"
CUST_REALM="customers"

OPS_CLIENT="store-app-console"
CUST_CLIENT="store-app-customer-web"
API_CLIENT="store-app-api"
API_CLIENT_CUST="store-app-api-customer"

# Local frontends that must be allowed (R12 — remote TLS-terminated Keycloak
# enforces CORS strictly, so localhost is not implicitly permitted).
ALLOWED_ORIGINS='["http://localhost:3000","http://localhost:5173"]'

echo "==> Authenticating to $KC_BASE ..."
TOKEN=$(curl -s --max-time 30 \
  -d "client_id=admin-cli" \
  -d "username=$KC_ADMIN_USER" \
  --data-urlencode "password=$KC_ADMIN_PASS" \
  -d "grant_type=password" \
  "$KC_BASE/realms/master/protocol/openid-connect/token" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
echo "    ok"

kc_get() { curl -s --max-time 30 -H "Authorization: Bearer $TOKEN" "$1"; }
kc_post() { curl -s --max-time 30 -X POST -H "Authorization: Bearer $TOKEN" \
              -H "Content-Type: application/json" -d "$2" "$1"; }

create_role() { # realm, roleName
  local realm="$1" role="$2"
  if kc_get "$KC_BASE/admin/realms/$realm/roles/$role" | grep -q '"name"'; then
    echo "    = role $realm/$role already exists"
  else
    kc_post "$KC_BASE/admin/realms/$realm/roles" \
      "{\"name\":\"$role\",\"description\":\"store.app role\"}" >/dev/null
    echo "    + role $realm/$role created"
  fi
}

create_client() { # realm, clientId, payload
  local realm="$1" cid="$2" payload="$3"
  local uuid
  uuid=$(kc_get "$KC_BASE/admin/realms/$realm/clients" \
    | python3 -c "import json,sys; print(next((c['id'] for c in json.load(sys.stdin) if c['clientId']=='$cid'),''))")
  if [ -n "$uuid" ]; then
    echo "    = client $cid already exists (uuid $uuid)"
  else
    kc_post "$KC_BASE/admin/realms/$realm/clients" "$payload" >/dev/null
    echo "    + client $cid created"
  fi
}

echo ""
echo "==> Realm: $OPS_REALM"
create_role "$OPS_REALM" "ADMIN"
create_role "$OPS_REALM" "SHOP"

create_client "$OPS_REALM" "$OPS_CLIENT" "{
  \"clientId\": \"$OPS_CLIENT\",
  \"name\": \"store.app Console (shop + admin SPA)\",
  \"description\": \"Vite SPA for the shop and admin portals. Public client, PKCE S256.\",
  \"enabled\": true,
  \"publicClient\": true,
  \"standardFlowEnabled\": true,
  \"directAccessGrantsEnabled\": false,
  \"serviceAccountsEnabled\": false,
  \"protocol\": \"openid-connect\",
  \"redirectUris\": [\"http://localhost:5173/*\",\"http://localhost:5173\"],
  \"webOrigins\": $ALLOWED_ORIGINS,
  \"attributes\": {
    \"pkce.code.challenge.method\": \"S256\",
    \"post.logout.redirect.uris\": \"http://localhost:5173/*\"
  }
}"

create_client "$OPS_REALM" "$API_CLIENT" "{
  \"clientId\": \"$API_CLIENT\",
  \"name\": \"store.app API (ops realm)\",
  \"description\": \"Bearer-only. Confines tokens from realm $OPS_REALM to this resource server.\",
  \"enabled\": true,
  \"publicClient\": false,
  \"bearerOnly\": true,
  \"standardFlowEnabled\": false,
  \"directAccessGrantsEnabled\": false,
  \"serviceAccountsEnabled\": false,
  \"protocol\": \"openid-connect\"
}"

echo ""
echo "==> Realm: $CUST_REALM"
create_role "$CUST_REALM" "CUSTOMER"

create_client "$CUST_REALM" "$CUST_CLIENT" "{
  \"clientId\": \"$CUST_CLIENT\",
  \"name\": \"store.app Customer Web (Next.js)\",
  \"description\": \"Public client for the customer portal. PKCE S256.\",
  \"enabled\": true,
  \"publicClient\": true,
  \"standardFlowEnabled\": true,
  \"directAccessGrantsEnabled\": false,
  \"serviceAccountsEnabled\": false,
  \"protocol\": \"openid-connect\",
  \"redirectUris\": [\"http://localhost:3000/*\",\"http://localhost:3000\"],
  \"webOrigins\": $ALLOWED_ORIGINS,
  \"attributes\": {
    \"pkce.code.challenge.method\": \"S256\",
    \"post.logout.redirect.uris\": \"http://localhost:3000/*\"
  }
}"

create_client "$CUST_REALM" "$API_CLIENT_CUST" "{
  \"clientId\": \"$API_CLIENT_CUST\",
  \"name\": \"store.app API (customers realm)\",
  \"description\": \"Bearer-only. Confines tokens from realm $CUST_REALM to this resource server.\",
  \"enabled\": true,
  \"publicClient\": false,
  \"bearerOnly\": true,
  \"standardFlowEnabled\": false,
  \"directAccessGrantsEnabled\": false,
  \"serviceAccountsEnabled\": false,
  \"protocol\": \"openid-connect\"
}"

echo ""
echo "==> Registration + login policy"
# customers: open self-registration with email verification (plan D3)
kc_post "$KC_BASE/admin/realms/$CUST_REALM" \
  "{\"registrationAllowed\":true,\"verifyEmail\":true,\"registrationEmailAsUsername\":false}" >/dev/null
echo "    = customers: self-registration ON, email verification ON"

# ops: registration ON but the user is useless without a role, so this only
# creates accounts. Roles are granted by an admin in the Keycloak console
# (or the signup form is switched off once seeded). See plan appendix B.
kc_post "$KC_BASE/admin/realms/$OPS_REALM" \
  "{\"registrationAllowed\":true,\"verifyEmail\":true}" >/dev/null
echo "    = ops: self-registration ON, email verification ON"

echo ""
echo "==> Done. Verify:"
echo "    $KC_BASE/admin/master/console/#/$OPS_REALM/roles"
echo "    $KC_BASE/admin/master/console/#/$CUST_REALM/roles"
