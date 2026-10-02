# shop.app — Multi-Tenant Shop Platform

**Build Plan** · 3 portals (Admin / Shop / Customer) · 2 frontends · 1 resource server · Keycloak · PostgreSQL

Status: Draft for review · Last updated: 2026-10-01

---

## 1. Confirmed Decisions

These came out of the clarification round. They are the constraints the rest of the plan is built on.

| # | Decision | Choice |
|---|---|---|
| D1 | Storefront rendering | SSR inside the **Next.js** customer portal (`/s/[shopSlug]`) |
| D2 | Money movement | **Prepaid platform wallet.** Shop pre-funds; 5% debited at order **ACCEPT**. Customer pays the shop's own eSewa/COD/bank. Shops do not need their own eSewa *merchant* integration for commission — only for receiving customer payment. |
| D3 | Keycloak tenancy | **2 realms.** Realm A (`ops`): `ADMIN`, `SHOP`. Realm B (`customers`): `CUSTOMER`. No staff role — one owner per shop. |
| D4 | Subscription gating | Feature flags in Postgres `entitlements`. Keycloak never holds billing state. |
| D5 | Admin API coverage | Curated screens **+** a generic OpenAPI-driven data explorer. |
| D6 | Spring Boot | **3.5.x** (LTS-friendly, widest ecosystem), Java 21. |
| D7 | Subscription billing | **Manual top-up + admin approval.** No recurring charging. |
| D8 | Theme engine | JSON config + **fixed versioned React section registry** + admin-published **template marketplace**. |
| D9 | Repos | **2 repos:** `web` (Turborepo) and `api` (Spring Boot). |
| D10 | Repo layout | `web/apps/customer` (Next.js), `web/apps/console` (Vite + React: shop **and** admin), `web/packages/*` shared. |
| D11 | Payment verification | Backend verifies eSewa **server-to-server**; COD and bank transfer via manual mark + receipt upload. Wrapped in a `PaymentGateway` interface. |
| D12 | Phase 1 slice | **Vertical slice:** shop signup -> verification -> product -> order -> wallet debit -> notification. |
| D13 | Followers | **Count only**, in the shop portal. No feed, no rewards, no follow UI polish in early phases. |
| D14 | Returns/refunds | **Deferred** to a late phase. Cancellation + commission reversal ship early. |
| D15 | Paid custom CSS | **Deferred** until the CSS sanitizer is proven. |
| D16 | Deployment | Manual, by you. Nginx + Postgres + RabbitMQ + Redis + Spring jar + 2 static frontends. Frontend and backend deploy independently. |

### Implications worth calling out

- **D3 forces a multi-issuer resource server.** Spring needs two `JwtDecoder`s keyed by `iss`, because the realms are separate. This is a real design item in Phase 1, not an afterthought — see section 5.2.
- **D2 is the highest-risk piece of the whole system.** The wallet ledger, commission math, and the "cannot accept without funds" guard are where money bugs will live. It gets the most design attention (section 4.6) and the most test coverage.
- **D1 means the theme engine lives in Next.js**, but is *authored* in the Vite console. Both read the same `packages/theme-engine` and the same JSON schema, so the console preview is a faithful approximation, not a different renderer.
- **D9 + D16 means no shared CI is required.** Each repo builds and ships itself.

---

## 2. System Architecture

```
                        ┌──────────────────────┐
   Browser ────────────▶│  Nginx (you manage)  │
                        └──────┬───────┬───────┘
                               │       │
              ┌────────────────┘       └──────────────┐
              ▼                                      ▼
   ┌────────────────────┐              ┌──────────────────────────┐
   │ apps/customer      │              │ apps/console             │
   │ Next.js 15 (SSR)   │              │ Vite + React (SPA)       │
   │  /     gallery     │              │  /admin/*                │
   │  /s/[slug]  store  │              │  /shop/*                 │
   │  /account/*        │              │  /preview  (theme editor)│
   └─────────┬──────────┘              └────────────┬─────────────┘
             │                                    │
             │  OIDC (PKCE) →                     │  OIDC (PKCE) →
             ▼                                    ▼
   ┌──────────────────┐              ┌──────────────────────────┐
   │ Keycloak         │              │ Keycloak                 │
   │ realm: ops       │              │ realm: ops               │
   │  roles:          │              │  roles: ADMIN, SHOP      │
   │   ADMIN, SHOP    │              └──────────────────────────┘
   ├──────────────────┤
   │ realm: customers │  Both realms issue JWTs.
   │  role: CUSTOMER  │  Resource server validates BOTH issuers.
   └──────────────────┘
             │                                    │
             └──────────────┬─────────────────────┘
                            ▼
             ┌──────────────────────────────────┐
             │  Spring Boot 3.5 (api)          │
             │  Resource Server ONLY            │
             │  · no login endpoints            │
             │  · multi-issuer JWT validation   │
             │  · tenant filter (shop_id)       │
             │  · REST + OpenAPI (springdoc)    │
             └────┬──────────┬──────────┬───────┘
                  │          │          │
        ┌─────────▼──┐  ┌────▼─────┐  ┌─▼──────────┐
        │ PostgreSQL │  │  Redis   │  │ RabbitMQ   │
        │  source of │  │  cache   │  │ async bus  │
        │  truth     │  │  rate    │  └────┬────────┘
        └────────────┘  │  limits  │       │
                        └──────────┘  ┌────┴─────────────┐
                                        │  workers        │
                                        │  · email        │
                                        │  · FCM push     │
                                        │  · ledger       │
                                        └────┬──────┬─────┘
                                             │      │
                                     ┌───────▼──┐ ┌─▼────────┐
                                     │Cloudinary │ │ Firebase │
                                     │  media    │ │  FCM     │
                                     └───────────┘ └──────────┘
```

### 2.1 Two frontends, shared resources

| Package | Contents | Used by |
|---|---|---|
| `packages/ui` | shadcn/ui primitives + theme tokens (CSS vars), layout shells, `data-table`, form wrappers | both |
| `packages/theme-engine` | Section registry, renderer, zod theme schema, editor palette, storefront preview renderer | both |
| `packages/api-client` | Typed fetch wrapper, react-query hooks per domain, error normalizer, auth header injection | both |
| `packages/auth` | Keycloak adapter (PKCE), token lifecycle, `useAuthz` hook, role guards, realm-aware config | both |
| `packages/validation` | Shared zod schemas (register, product, address, checkout, shop settings) | both |
| `packages/state` | Zustand stores (auth cache, theme editor draft, cart, console filters) | both |
| `packages/config` | TS config, eslint, prettier, env var schema | both |
| `packages/icons` | react-icons curated re-exports | both |

Rule: `apps/*` may import from `packages/*`; `packages/*` may **never** import from `apps/*`. Enforced by an ESLint `no-restricted-imports` rule and a Turborepo task.

### 2.2 Why the storefront is SSR in Next.js

- Product pages must be indexable and share-previewable (your WhatsApp / social selling requirement).
- The theme engine needs to resolve a shop's theme + products on the server and stream HTML.
- The Vite console stays a pure SPA — it never serves public pages, so a broken console build can never take down a storefront.

---

## 3. Repository Layout

### Repo 1 — `web` (Turborepo, pnpm workspaces)

```
web/
├─ apps/
│  ├─ customer/               # Next.js 15, App Router, SSR storefront
│  │  ├─ app/
│  │  │  ├─ (gallery)/        # /            shop gallery
│  │  │  ├─ s/[shopSlug]/     # storefront
│  │  │  │  ├─ page.tsx       # home (theme-rendered)
│  │  │  │  ├─ p/[handle]/    # single product
│  │  │  │  ├─ c/[handle]/    # category listing
│  │  │  │  ├─ cart/
│  │  │  │  ├─ checkout/
│  │  │  │  └─ policy/[slug]/
│  │  │  └─ account/          # orders, addresses, follows
│  │  ├─ lib/                 # server-only fetchers, SEO metadata
│  │  └─ components/
│  └─ console/                # Vite + React SPA
│     └─ src/
│        ├─ admin/            # admin screens
│        ├─ shop/             # shop dashboard
│        ├─ theme-editor/     # appearance builder + live preview
│        ├─ explorer/         # generic OpenAPI data explorer
│        └─ main.tsx          # route table by realm + role
└─ packages/                  # the 8 shared packages above
```

### Repo 2 — `api` (Spring Boot 3.5.x, Java 21)

```
api/
├─ src/main/java/com/shop/app/
│  ├─ common/          # tenant filter, error model, pagination, money, ids
│  ├─ security/        # multi-issuer JWT, role mapping, method security
│  ├─ identity/        # user/shop profile mirror, keycloak linkage
│  ├─ shop/            # shop, settings, verification, subscription
│  ├─ catalog/         # category, subcategory, product, variant, media
│  ├─ inventory/       # stock, movements, low-stock alerts
│  ├─ order/           # order, items, state machine, invoice
│  ├─ payment/         # gateway interface, esewa, cod, bank
│  ├─ wallet/          # ledger, commission, topup
│  ├─ theme/           # theme config, templates, entitlements
│  ├─ customer/        # audience, followers, addresses
│  ├─ notification/    # rabbit producers/consumers, fcm, email
│  ├─ admin/           # kpis, reports, audit, settings
│  └─ platform/        # commission config, feature flags
├─ src/main/resources/db/migration/   # Flyway
└─ build.gradle.kts
```

Monolith first, **modular** boundaries. Each top-level package is a vertical slice with its own controller/service/repository. Split into services later only if a measured bottleneck appears — not preemptively.

Build: **Gradle (Kotlin DSL)** with the Spring Boot plugin + dependency-management BOM, `flyway`, `springdoc-openapi`, `testcontainers` (Postgres + RabbitMQ), `jjwt`. Single service, single jar.

---

## 4. Data Model (PostgreSQL)

Conventions: `uuid` PKs, `timestamptz`, `snake_case`, soft delete only where audit matters (`deleted_at`), `created_at`/`updated_at` on all mutable tables. All money is **`bigint` minor units (paisa)** — never floats, never `numeric` for storage. `numeric` is used only for percentage/rate config.

### 4.1 Identity and tenancy

```sql
-- Mirror of Keycloak users. Keycloak is the authn source; this is the app profile.
app_user (
  id                uuid pk,
  realm             text not null check (realm in ('ops','customers')),
  keycloak_sub      text not null unique,     -- JWT 'sub'
  email             citext not null,
  full_name         text,
  phone             text,
  avatar_url        text,
  status            text not null default 'ACTIVE',  -- ACTIVE|SUSPENDED|DELETED
  email_verified_at timestamptz,
  last_login_at     timestamptz,
  created_at        timestamptz not null default now()
);
create index on app_user (email);
create index on app_user (status);

shop (
  id                 uuid pk,
  owner_user_id      uuid not null unique references app_user(id),  -- 1 owner per shop (D3)
  slug               citext not null unique,   -- shop.app/s/<slug>
  name               text not null,
  description        text,
  category_id        uuid references category(id),   -- global taxonomy
  logo_url           text, favicon_url text, cover_url text,
  status             text not null default 'DRAFT',
     -- DRAFT | PENDING_REVIEW | VERIFIED | REJECTED | SUSPENDED
  verification_note  text,
  verified_at        timestamptz,
  commission_plan    text not null default 'FREE',   -- FREE | SUBSCRIBED
  follower_count     int  not null default 0,        -- counter only (D13)
  published_at       timestamptz,
  created_at         timestamptz not null default now()
);
create index on shop (status);
create index on shop (category_id);

-- Admin's global category taxonomy, referenced by shops.
category (
  id         uuid pk,
  parent_id  uuid references category(id),   -- 2 levels: category -> subcategory
  name       text not null,
  slug       citext not null,
  sort_order int not null default 0,
  is_active  boolean not null default true
);
create unique index on category (parent_id, slug);
```

### 4.2 Catalog

```sql
shop_category (            -- shop's own taxonomy (distinct from global `category`)
  id          uuid pk,
  shop_id     uuid not null references shop(id) on delete cascade,
  parent_id   uuid references shop_category(id),   -- subcategory
  name        text not null,
  slug        citext not null,
  description text,
  image_url   text,
  sort_order  int not null default 0,
  is_active   boolean not null default true,
  unique (shop_id, parent_id, slug)
);

product (
  id                uuid pk,
  shop_id           uuid not null references shop(id) on delete cascade,
  category_id       uuid references shop_category(id),
  name              text not null,
  slug              citext not null,
  short_description text,
  description       text,                 -- sanitized rich text / markdown
  status            text not null default 'DRAFT',  -- DRAFT|ACTIVE|ARCHIVED
  base_price_minor  bigint not null default 0,
  compare_at_minor  bigint,               -- "was" price
  cost_price_minor  bigint,               -- shop-private margin data
  tax_category      text,                 -- resolves to a shop tax rule
  weight_grams      int,
  track_inventory   boolean not null default true,
  allow_backorder   boolean not null default false,
  is_featured       boolean not null default false,
  seo_title         text, seo_description text,
  rating_avg        numeric(3,2) not null default 0,
  rating_count      int not null default 0,
  view_count        int not null default 0,
  sold_count        int not null default 0,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz,
  unique (shop_id, slug)
);
create index on product (shop_id, status) where deleted_at is null;
create index on product (shop_id, category_id);

product_variant (
  id               uuid pk,
  product_id       uuid not null references product(id) on delete cascade,
  sku              text,
  name             text not null,            -- "Red / Large"
  option_values    jsonb not null default '{}',  -- {"color":"Red","size":"L"}
  price_minor      bigint not null,
  compare_at_minor bigint,
  barcode          text,
  image_url        text,
  sort_order       int not null default 0,
  is_active        boolean not null default true,
  unique (product_id, sku)
);

product_media (
  id                  uuid pk,
  product_id          uuid not null references product(id) on delete cascade,
  variant_id          uuid references product_variant(id) on delete cascade,
  cloudinary_public_id text not null,
  url                 text not null,
  kind                text not null default 'IMAGE',  -- IMAGE|VIDEO
  alt_text            text,
  sort_order          int not null default 0
);
```

### 4.3 Inventory

```sql
inventory (
  product_id          uuid pk references product(id) on delete cascade,
  variant_id          uuid pk references product_variant(id) on delete cascade,
  quantity_on_hand    int not null default 0,
  quantity_reserved   int not null default 0,
  low_stock_threshold int not null default 5,
  updated_at          timestamptz not null default now()
);
-- available = on_hand - reserved

inventory_movement (        -- append-only audit of every change
  id             uuid pk,
  product_id     uuid not null,
  variant_id     uuid not null,
  delta          int not null,
  reason         text not null,   -- SALE|RESTOCK|ADJUSTMENT|RETURN|CANCEL|IMPORT
  reference_type text, reference_id uuid,
  note           text,
  created_by     uuid references app_user(id),
  created_at     timestamptz not null default now()
);
create index on inventory_movement (product_id, variant_id, created_at desc);
```

### 4.4 Orders — the core aggregate

```sql
shop_order (
  id                uuid pk,
  order_number      text not null,             -- formatted per shop settings
  shop_id           uuid not null references shop(id),
  customer_id       uuid references app_user(id),   -- null for guest checkout
  guest_email       citext, guest_phone text,      -- denormalized contact
  status            text not null default 'PENDING',
     -- PENDING | ACCEPTED | PROCESSING | SHIPPED | DELIVERED
     -- | CANCELLED | RETURNED | REFUNDED
  payment_status    text not null default 'PENDING',
     -- PENDING | PAID | FAILED | REFUNDED | PARTIALLY_REFUNDED
  payment_method    text not null,            -- ESAWA|COD|BANK_TRANSFER
  subtotal_minor    bigint not null,
  discount_minor    bigint not null default 0,
  tax_minor         bigint not null default 0,
  shipping_minor    bigint not null default 0,
  total_minor       bigint not null,
  currency          char(3) not null default 'NPR',
  -- commission snapshot, frozen at accept time (never recomputed later)
  commission_rate_bp int,                     -- basis points: 500 = 5%
  commission_minor  bigint,
  commission_waived boolean not null default false,
  -- addresses snapshotted as JSONB: they must survive later address edits
  shipping_address  jsonb not null,
  billing_address   jsonb,
  customer_note     text,
  admin_note        text,
  placed_at         timestamptz not null default now(),
  accepted_at       timestamptz,
  shipped_at        timestamptz,
  delivered_at      timestamptz,
  cancelled_at      timestamptz,
  cancel_reason     text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  unique (shop_id, order_number)
);
create index on shop_order (shop_id, status, created_at desc);
create index on shop_order (customer_id, created_at desc);
create index on shop_order (placed_at);   -- revenue reports

order_item (
  id               uuid pk,
  order_id         uuid not null references shop_order(id) on delete cascade,
  product_id       uuid references product(id) on delete set null,
  variant_id       uuid references product_variant(id) on delete set null,
  -- snapshot everything; a deleted product must not corrupt historical invoices
  product_name     text not null,
  variant_name     text,
  sku              text,
  image_url        text,
  unit_price_minor bigint not null,
  quantity         int not null check (quantity > 0),
  discount_minor   bigint not null default 0,
  tax_minor        bigint not null default 0,
  line_total_minor bigint not null
);
create index on order_item (order_id);

order_status_history (        -- append-only; powers the timeline UI and disputes
  id          uuid pk,
  order_id    uuid not null references shop_order(id) on delete cascade,
  from_status text, to_status text not null,
  changed_by  uuid references app_user(id),
  note        text,
  created_at  timestamptz not null default now()
);
```

### 4.5 Payments (D11)

```sql
shop_payment_method (
  id                   uuid pk,
  shop_id              uuid not null references shop(id) on delete cascade,
  method               text not null,          -- ESAWA|COD|BANK_TRANSFER
  is_enabled           boolean not null default true,
  -- eSewa merchant config, per shop (encrypted at rest)
  esewa_merchant_id    text,
  esewa_secret_enc     bytea,
  bank_name            text, bank_account_name text,
  bank_account_number_enc bytea, bank_qr_url text,
  cod_fee_minor        bigint not null default 0,
  min_order_minor      bigint not null default 0,
  instructions         text,
  sort_order           int not null default 0,
  unique (shop_id, method)
);

payment_transaction (
  id              uuid pk,
  order_id        uuid not null references shop_order(id),
  shop_id         uuid not null references shop(id),
  method          text not null,
  amount_minor    bigint not null,
  gateway         text not null,        -- ESEWA|COD|BANK|MANUAL
  gateway_ref     text,                 -- eSewa transaction code / receipt id
  gateway_status  text,                 -- PENDING|COMPLETED|FAILED|REFUNDED
  request_payload jsonb,                -- raw gateway response, for disputes
  verified_at     timestamptz,
  verified_by     uuid references app_user(id),
  receipt_url     text,                 -- bank-transfer proof upload
  idempotency_key text not null unique,
  created_at      timestamptz not null default now()
);
create index on payment_transaction (order_id);
create index on payment_transaction (gateway_ref);
```

`PaymentGateway` interface: `createPaymentIntent(order)`, `verify(reference)`, `refund(txn, amount)`. Implementations: `EsewaGateway`, `CodGateway` (no-op intent, manual confirm), `BankTransferGateway` (no-op intent, receipt upload + manual confirm). Adding Khalti/Fonepay later = one new class, zero changes to order code.

The eSewa callback endpoint is **public and unauthenticated** — authenticated instead by the eSewa transaction code being verified server-to-server before any state changes.

### 4.6 Wallet, commission, top-up (D2) — highest-risk domain

**Invariant: the ledger is the truth. There is no mutable `balance` column.** A `shop_wallet_balance` table is maintained in the *same transaction* as every ledger insert purely as a read cache, and is reconciled by a scheduled job that recomputes `SUM(amount_minor)` and raises an alert on any drift.

```sql
shop_wallet_balance (            -- READ CACHE ONLY, always re-derivable
  shop_id       uuid pk references shop(id) on delete cascade,
  balance_minor bigint not null default 0,   -- may go negative; allowed, and alerted
  updated_at    timestamptz not null default now()
);

wallet_ledger_entry (            -- IMMUTABLE, append-only. No updates. Ever.
  id             uuid pk,
  shop_id        uuid not null references shop(id),
  entry_type     text not null,
     -- TOPUP_APPROVED | COMMISSION_DEBIT | COMMISSION_REVERSAL
     -- | SUBSCRIPTION_CHARGE | ADJUSTMENT_CREDIT | ADJUSTMENT_DEBIT
     -- | REFUND_DEBIT | PAYOUT
  direction      text not null check (direction in ('CREDIT','DEBIT')),
  amount_minor   bigint not null check (amount_minor > 0),  -- sign comes from direction
  balance_after_minor bigint not null,     -- running balance snapshot for auditability
  order_id       uuid references shop_order(id),
  subscription_id uuid references shop_subscription(id),
  payment_transaction_id uuid references payment_transaction(id),
  reversal_of    uuid references wallet_ledger_entry(id),  -- links a reversal to its debit
  idempotency_key text not null unique,     -- makes retries safe
  description    text not null,
  actor_user_id  uuid references app_user(id),
  metadata       jsonb,
  created_at     timestamptz not null default now()
);
create index on wallet_ledger_entry (shop_id, created_at desc);
create index on wallet_ledger_entry (order_id);
create index on wallet_ledger_entry (reversal_of);

wallet_topup_request (
  id              uuid pk,
  shop_id         uuid not null references shop(id),
  amount_minor    bigint not null check (amount_minor > 0),
  method          text not null,        -- ESAWA|BANK_TRANSFER
  reference       text,                 -- txn code or transfer ref
  proof_url       text,
  note            text,
  status          text not null default 'PENDING',  -- PENDING|APPROVED|REJECTED
  reviewed_by     uuid references app_user(id),
  reviewed_at     timestamptz,
  review_note     text,
  ledger_entry_id uuid references wallet_ledger_entry(id),
  created_at      timestamptz not null default now()
);
```

**Commission flow (the exact rules you specified):**

```
Order PLACED     -> no ledger movement. Wallet NOT checked.
Order ACCEPTED   -> FREE plan:       debit 5% of (subtotal - discount)
                    SUBSCRIBED plan: no debit; commission_waived = true, rate = 0
                    Guard: if balance < commission -> ACCEPT is rejected (409)
                    idempotency_key = "commission:" + order_id   => exactly-once
Order CANCELLED  -> if commission was debited, insert COMMISSION_REVERSAL
                    (CREDIT) linked via reversal_of.
                    Order PLACED -> CANCELLED with no debit => nothing to reverse.
Order REFUNDED   -> same reversal logic. (Phase 8)
```

Rate is stored as **basis points** (`commission_rate_bp`) and **snapshotted onto the order at accept time**. Changing the platform rate later must never retroactively alter a historical order.

### 4.7 Subscriptions and entitlements (D4, D7)

```sql
subscription_plan (
  id             uuid pk,
  code           text not null unique,    -- FREE, PRO_MONTHLY, PRO_ANNUAL
  name           text not null,
  billing_period text not null,           -- NONE|MONTHLY|ANNUAL
  price_minor    bigint not null,
  duration_days  int,                     -- null for FREE
  is_active      boolean not null default true,
  sort_order     int not null default 0
);

shop_subscription (
  id               uuid pk,
  shop_id          uuid not null references shop(id),
  plan_id          uuid not null references subscription_plan(id),
  status           text not null default 'PENDING',
     -- PENDING_PAYMENT | ACTIVE | EXPIRED | CANCELLED
  started_at       timestamptz,
  expires_at       timestamptz,
  auto_renew       boolean not null default false,   -- always false in v1 (D7)
  topup_request_id uuid references wallet_topup_request(id),
  cancelled_at     timestamptz,
  created_at       timestamptz not null default now()
);
create index on shop_subscription (shop_id, status, expires_at desc);

-- The single source of truth for "what can this shop do" (D4)
shop_entitlement (
  shop_id     uuid not null references shop(id) on delete cascade,
  feature_key text not null,      -- use_paid_templates, custom_css, remove_commission,
                                   -- max_products, custom_domain, priority_support, ...
  enabled     boolean not null default false,
  limit_value bigint,             -- for capped features (max_products = 500)
  expires_at  timestamptz,        -- null = permanent; else falls back to plan default
  updated_at  timestamptz not null default now(),
  primary key (shop_id, feature_key)
);

-- Derived from the active plan; the table above is a materialized override.
plan_entitlement (
  plan_id     uuid not null references subscription_plan(id) on delete cascade,
  feature_key text not null,
  enabled     boolean not null default false,
  limit_value bigint,
  primary key (plan_id, feature_key)
);
```

Renewal flow (D7): shop requests a plan -> pays the **platform's** eSewa/bank -> submits a top-up request tagged with the plan -> **admin approves** -> one transaction creates a `TOPUP_APPROVED` credit, activates `shop_subscription`, and writes a `SUBSCRIPTION_CHARGE` debit -> the shop's `commission_plan` flips to `SUBSCRIBED`. A nightly job expires subscriptions past `expires_at` and flips shops back to `FREE` (and back to 5% commission).

### 4.8 Theme engine (D8)

```sql
theme_template (              -- admin-published marketplace (D8)
  id                uuid pk,
  code              text not null unique,
  name              text not null,
  description       text,
  preview_image_url text,
  category_id       uuid references category(id),  -- templates vary by shop category
  config            jsonb not null,   -- full theme JSON, validated by the shared zod schema
  schema_version    int not null default 1,
  price_tier        text not null default 'FREE',  -- FREE|SUBSCRIBED
  is_active         boolean not null default true,
  sort_order        int not null default 0,
  published_at      timestamptz,
  created_at        timestamptz not null default now()
);

shop_theme (
  id             uuid pk,
  shop_id        uuid not null references shop(id) on delete cascade,
  template_id    uuid references theme_template(id),
  name           text not null default 'Default',
  config         jsonb not null,          -- the live, customized theme
  schema_version int not null default 1,
  is_published   boolean not null default false,
  updated_at     timestamptz not null default now()
);
create unique index on shop_theme (shop_id) where is_published;  -- one live theme

-- Named, reusable sub-configs so "customize the hero" doesn't rewrite the whole doc
theme_asset (
  id         uuid pk,
  shop_id    uuid not null references shop(id) on delete cascade,
  kind       text not null,   -- HERO|HEADER_PAGE|FOOTER|BANNER|MEDIA_PAGE
                              -- |CATALOG_LAYOUT|PRODUCT_LAYOUT|POLICY_PAGE
  handle     text not null,   -- stable key referenced from theme config
  name       text not null,
  config     jsonb not null,
  created_at timestamptz not null default now(),
  unique (shop_id, kind, handle)
);
```

Splitting `theme_asset` out of the main config is what makes the Appearance module tractable — each tab (Hero, Pages, Media, Product catalog, Single product, Banners, Footer) edits one `theme_asset` row, and the storefront renderer composes the published `shop_theme.config` with the referenced assets.

**Theme JSON shape** (shared zod schema, versioned — the version is stored on both template and shop theme so a renderer change can migrate old configs):

```jsonc
{
  "schemaVersion": 1,
  "tokens": {                       // maps 1:1 onto shadcn CSS variables
    "primary": "#7c3aed", "background": "#ffffff",
    "fontHeading": "Inter", "fontBody": "Inter",
    "radius": "0.75rem", "mode": "light"
  },
  "sections": [
    { "id": "announcement", "type": "announcement-bar",
      "props": { "text": "Free delivery over Rs. 2000", "dismissible": true } },
    { "id": "hero-1", "type": "hero", "variant": "split-image",
      "props": { "heading": "Handmade Nepalese Soap", "subheading": "...",
                 "imageUrl": "...", "cta": { "label": "Shop now", "href": "/c/all" } } },
    { "id": "cat-1", "type": "category-grid",
      "props": { "columns": 4, "source": "top_categories", "limit": 8 } },
    { "id": "promo-1", "type": "promo-banner",
      "props": { "imageUrl": "...", "href": "/p/sale", "overlay": 0.4 } },
    { "id": "products-1", "type": "product-carousel",
      "props": { "source": "featured", "limit": 12, "layoutId": "card-soft" } },
    { "id": "test-1", "type": "testimonials", "props": { "source": "reviews", "limit": 6 } },
    { "id": "faq-1", "type": "faq",
      "props": { "items": [{ "q": "Do you deliver outside Kathmandu?", "a": "Yes..." }] } }
  ],
  "nav":    { "menu": [{ "label": "Shop", "href": "/c/all" }] },
  "footer": { "assetHandle": "footer-main", "columns": 3, "showPaymentBadges": true }
}
```

The renderer holds a **closed registry** — a `Record<SectionType, SectionComponent>` map. An unknown `type` renders a visible "unknown section" placeholder rather than crashing the page, and a missing registry entry is a build-time error, not a runtime surprise.

Planned section types (~25): `announcement-bar`, `hero`, `category-grid`, `product-carousel`, `product-grid`, `collection-list`, `promo-banner`, `image-with-text`, `rich-text`, `video`, `gallery`, `testimonials`, `faq`, `newsletter`, `countdown`, `trust-badges`, `feature-list`, `cta-banner`, `map`, `social-feed`, `divider`, `html` (sanitized), `spacer`, `unknown-section` (fallback).

### 4.9 Shop settings, audience, misc

```sql
shop_setting (            -- 1:1 with shop, JSONB for the long tail
  shop_id          uuid pk references shop(id) on delete cascade,
  general          jsonb not null default '{}',  -- tagline, support email/phone, business hours
  address          jsonb not null default '{}',  -- registered + return address
  social           jsonb not null default '{}',  -- facebook, instagram, tiktok, youtube, whatsapp
  checkout         jsonb not null default '{}',  -- guest checkout, note required, min order
  notifications    jsonb not null default '{}',  -- channel toggles per event
  order_number     jsonb not null default '{"format":"ORD-{YYYY}-{####}","next":1}',
  invoice_template jsonb not null default '{}',
  email_templates  jsonb not null default '{}',
  appearance_extra jsonb not null default '{}',  -- custom CSS (D15: locked until sanitizer ships)
  updated_at       timestamptz not null default now()
);

shipping_zone (
  id              uuid pk,
  shop_id         uuid not null references shop(id) on delete cascade,
  name            text not null,          -- "Inside Ring Road", "Outside Valley"
  countries       text[] not null default '{NP}',
  cities          text[],                 -- empty = whole zone
  rate_minor      bigint not null default 0,
  free_over_minor bigint,
  min_days int not null default 1, max_days int not null default 7,
  is_active       boolean not null default true
);

tax_rule (
  id         uuid pk,
  shop_id    uuid not null references shop(id) on delete cascade,
  name       text not null,
  rate_bp    int not null,              -- 1300 = 13%
  inclusive  boolean not null default false,
  applies_to text not null default 'ALL',  -- ALL|PRODUCT|shipping
  is_active  boolean not null default true
);

discount (
  id                uuid pk,
  shop_id           uuid not null references shop(id) on delete cascade,
  code              citext,
  name              text not null,
  type              text not null,          -- PERCENT|FIXED|FREE_SHIPPING
  value             bigint not null,        -- percent in bp, or minor units
  min_order_minor   bigint,
  max_discount_minor bigint,
  usage_limit       int, usage_used int not null default 0,
  per_customer_limit int,
  starts_at         timestamptz, ends_at timestamptz,
  is_active         boolean not null default true
);
create unique index on discount (shop_id, code) where code is not null;

customer (                 -- "Audience": a customer *as seen by one shop*
  id               uuid pk,
  shop_id          uuid not null references shop(id) on delete cascade,
  user_id          uuid references app_user(id),   -- null for guest orders
  email            citext not null,
  full_name        text, phone text,
  total_orders     int not null default 0,
  total_spent_minor bigint not null default 0,
  last_order_at    timestamptz,
  notes            text,                     -- shop-private tags
  created_at       timestamptz not null default now(),
  unique (shop_id, email)
);
create index on customer (shop_id, total_spent_minor desc);

shop_follower (            -- count denormalized on shop.follower_count (D13)
  shop_id    uuid not null references shop(id) on delete cascade,
  user_id    uuid not null references app_user(id),
  created_at timestamptz not null default now(),
  primary key (shop_id, user_id)
);

notification (
  id                uuid pk,
  audience          text not null,      -- SHOP|CUSTOMER|ADMIN
  recipient_user_id uuid references app_user(id),
  recipient_shop_id uuid references shop(id),
  channel           text not null,      -- EMAIL|PUSH|IN_APP
  type              text not null,      -- ORDER_PLACED, ORDER_ACCEPTED, LOW_STOCK, ...
  title             text not null, body text not null,
  data              jsonb,
  status            text not null default 'PENDING',  -- PENDING|SENT|FAILED
  attempts          int not null default 0,
  last_error        text,
  sent_at           timestamptz,
  read_at           timestamptz,
  created_at        timestamptz not null default now()
);
create index on notification (recipient_user_id, created_at desc);
create index on notification (status) where status = 'PENDING';

audit_log (
  id            bigserial pk,
  actor_user_id uuid references app_user(id),
  actor_role    text,
  action        text not null,   -- SHOP_VERIFIED, WALLET_TOPUP_APPROVED, ORDER_CANCELLED
  entity_type   text not null, entity_id uuid,
  shop_id       uuid,
  before        jsonb, after jsonb,
  ip            inet, user_agent text,
  created_at    timestamptz not null default now()
);
create index on audit_log (shop_id, created_at desc);
create index on audit_log (entity_type, entity_id, created_at desc);

platform_setting (
  key        text pk,
  value      jsonb not null,
  updated_by uuid references app_user(id),
  updated_at timestamptz not null default now()
);
-- keys: commission_rate_bp, min_topup_minor, platform_currency, gallery_featured_limit,
--        registration_open, default_theme_template_id

outbox_event (              -- transactional outbox; guarantees no lost events (R5)
  id             uuid pk,
  aggregate_type text not null, aggregate_id uuid not null,
  event_type     text not null,
  payload        jsonb not null,
  published_at   timestamptz,
  attempts       int not null default 0,
  created_at     timestamptz not null default now()
);
create index on outbox_event (created_at) where published_at is null;
```

### 4.10 Postgres extras

- `citext` extension for case-insensitive email/slug.
- Partial indexes for the hot admin/shop list queries.
- **Row-level security is deliberately off.** Tenant isolation is enforced in one place — a `TenantContext` filter — because debugging RLS under connection pooling is a tax you don't need yet. Revisit if the DB is ever exposed directly.
- Flyway migrations are append-only, never edited after merge.

---

## 5. Backend Design

### 5.1 Modules

Modular monolith. Bounded contexts, each owning its tables exclusively — no cross-module table writes, ever. Cross-module communication is either a method call within one transaction (same module) or a RabbitMQ event (different module).

`identity` · `shop` · `catalog` · `inventory` · `order` · `payment` · `wallet` · `theme` · `customer` · `notification` · `admin`

### 5.2 Multi-realm security (D3) — the trickiest infra piece

Because admin/shop and customer live in **different realms**, the resource server must accept two issuers and map them onto three app roles.

```java
@Configuration
class SecurityConfig {

  // Two decoders, chosen by the 'iss' claim.
  @Bean JwtDecoder jwtDecoder(KeycloakProperties props) {
    var ops       = NimbusJwtDecoder.withIssuerLocation(props.opsIssuer()).build();
    var customers = NimbusJwtDecoder.withIssuerLocation(props.customerIssuer()).build();
    var validator = DelegatingOAuth2TokenValidator<>(List.of(
        new JwtIssuerValidator(props.opsIssuer()),
        new JwtIssuerValidator(props.customerIssuer()),
        new JwtTimestampValidator()));
    ops.setJwtValidator(validator);
    customers.setJwtValidator(validator);
    return new MultiIssuerJwtDecoder(Map.of(
        props.opsIssuer(),       ops,
        props.customerIssuer(),  customers));
  }
}
```

- `JwtAuthenticationConverter` maps `realm_access.roles` to `ROLE_ADMIN` / `ROLE_SHOP` from realm `ops`, and `ROLE_CUSTOMER` from realm `customers`. A role name present in the wrong realm is **rejected**, not silently accepted.
- **No realm issues a role belonging to the other realm.** Enforced by a startup self-test that logs in as a test user in each realm and asserts the mapped authorities.
- Shop ownership is **not** a Keycloak concern. `shop.owner_user_id` in Postgres is the authority. A `SHOP` token with no matching shop row gets 403, not 500.
- The resource server has **zero** auth endpoints — no `/login`, no `/register`, no token issuance. Registration happens via Keycloak's own registration flow, then a first-login sync creates the Postgres mirror row.
- Method security: `@PreAuthorize("hasRole('ADMIN')")`, `@PreAuthorize("@tenantGuard.isOwner(#shopId)")`.
- A `TenantContext` `ThreadLocal` filter resolves `shop_id` from the JWT subject via a short-TTL Redis cache, cleared in a `finally`. Every repository query goes through a `TenantAwareSpecification`, so a missing filter fails loudly in dev.

### 5.3 Order state machine

```
                 ┌──────────────► CANCELLED ◄────────────┐
                 │  (no debit)   (reverses commission)   │
PENDING ──accept──► ACCEPTED ──► PROCESSING ──► SHIPPED ──► DELIVERED
   │                    │            │             │
   └──cancel────────────┴────────────┴─────────────┘
                          (Phase 8 adds RETURNED / REFUNDED)
```

- Transitions are the **only** way status changes. No `setStatus()` from a controller.
- Each transition validates the current status, writes `order_status_history`, adjusts inventory, and fires a domain event.
- Illegal transition returns `409 CONFLICT` with the legal target states in the body.
- `ACCEPTED` additionally runs the **wallet guard** in the same transaction as the status write.

### 5.4 Money

- Storage: `bigint` paisa everywhere. A `Money` value object wraps `long amount` + `Currency`; no arithmetic on raw longs in service code.
- Commission: `rate_bp` basis points (`5% = 500`). Computed as `BigDecimal` internally, rounded `HALF_UP` to paisa, then stored as `long`.
- Commissionable base: `(subtotal − discount)`. Shipping and tax are **excluded** — confirm before Phase 1 ships, it changes every number.
- Every money-writing endpoint accepts an `Idempotency-Key` header persisted in a unique index. Retries are free; double-charges are impossible.

### 5.5 Async (RabbitMQ)

Queues, all with dead-letter queues and bounded retries:

| Queue | Producer | Consumer | Work |
|---|---|---|---|
| `email.out` | any domain | `EmailWorker` | render template -> SMTP |
| `push.out` | any domain | `PushWorker` | FCM fan-out |
| `notification.persist` | any domain | `NotificationWorker` | write `notification` row |
| `order.events` | order | analytics / read models | KPI counters, `sold_count` |
| `wallet.events` | wallet | ledger reconciliation | drift detection alert |
| `search.index` | catalog/inventory | `SearchWorker` | Postgres FTS + trigram |

Publishing uses the **outbox pattern**: the state change and the event insert commit together, and a relay publishes to RabbitMQ afterwards. This is the difference between "notifications sometimes don't send" and "never lose an order event" — worth the extra table from day one.

Consumers are `@RabbitListener` with manual ack, prefetch 20, and a container factory with DLQ + exponential backoff.

### 5.6 API conventions

- `/api/v1/**`, OpenAPI 3.1 via springdoc at `/api/v1/openapi.json`.
- Cursor pagination (`?cursor=&limit=`) for orders/products/ledger — offset pagination dies on large ledgers.
- Errors: RFC 7807 `ProblemDetail`, every error carrying a `traceId`.
- `Idempotency-Key` on all POSTs that move money or create orders.
- Optimistic locking (`@Version`) on `shop_order` and `product`.
- Redis: token->shop cache, rate limits (60 req/min per shop on writes), hot storefront reads, cursor encoding.

### 5.7 API surface outline

**Public (no auth):** `GET /shops` (gallery, filters) · `GET /shops/{slug}` · `GET /shops/{slug}/products` · `GET /shops/{slug}/categories` · `GET /p/{shopSlug}/{handle}` · `POST /payments/esewa/callback` · `GET /themes/templates/{code}`

**Customer:** `POST /orders/quote` · `POST /orders` · `GET /orders/me` · `POST /orders/{id}/cancel` · addresses CRUD · follow/unfollow · review submit. (The cart is client-side; the server re-validates every price at quote time.)

**Shop:** `GET/PATCH /shop/profile` · `POST /shop/submit-verification` · categories/products/variants/inventory CRUD · `GET /shop/orders?status=` · `POST /shop/orders/{id}/transition` · `GET /shop/audience` · `GET /shop/wallet/ledger` · `POST /shop/wallet/topup` · `GET/PUT /shop/theme` · `GET/POST/PATCH/DELETE /shop/theme/assets` · `GET/PUT /shop/settings/*` · shipping zones · tax rules · discounts · payment methods · `GET /shop/invoices/{orderId}`

**Admin:** `GET /admin/kpis` · shops list + `POST /admin/shops/{id}/verify|suspend|reject` · users list + suspend · `GET/POST/PUT /admin/plans` · `GET/PUT /admin/plans/{id}/entitlements` · `POST /admin/wallet/topups/{id}/approve|reject` · `POST /admin/wallet/adjust` · `GET/POST/PUT /admin/themes/templates` · global categories CRUD · featured shops · `GET /admin/reports/*` · `GET /admin/audit` · `GET/PUT /admin/settings`

---

## 6. Frontend Design

### 6.1 `apps/customer` — Next.js 15, App Router

| Route | Rendering | Purpose |
|---|---|---|
| `/` | SSR + ISR | Shop gallery, category filter, search, featured |
| `/s/[shopSlug]` | SSR, dynamic | Storefront home from theme JSON |
| `/s/[shopSlug]/c/[handle]` | SSR + ISR | Category listing |
| `/s/[shopSlug]/p/[handle]` | SSR | Product detail, variants, add to cart |
| `/s/[shopSlug]/cart` | client | Cart |
| `/s/[shopSlug]/checkout` | client | Address, shipping zone, payment method, quote |
| `/s/[shopSlug]/order/[id]` | SSR | Order confirmation + status timeline |
| `/s/[shopSlug]/policy/[slug]` | SSR | Policy pages from theme assets |
| `/account/*` | client + SSR shell | Orders, addresses, follows |

- Theme rendering is a **pure server component** — fetch theme config + section data, map to the registry, stream HTML. This is what makes the storefront indexable.
- `generateMetadata` per storefront/product for OG tags, so WhatsApp and Facebook previews render correctly (your multi-channel selling requirement).
- Cart: Zustand, persisted to `localStorage`, re-validated against the backend at checkout — prices are never trusted from the client.
- Auth: **not** `next-auth`. A thin Keycloak PKCE adapter in `packages/auth`, with tokens in httpOnly cookies set by a route handler so SSR can read them.
- React Query for all mutations and client reads; server components use direct fetch with revalidation tags.

### 6.2 `apps/console` — Vite + React SPA

One bundle, role-split at the router. Realm `ops` users land on `/admin` or `/shop`; realm `customers` users are redirected out.

**Shop dashboard:** overview KPIs · orders board (status columns, bulk transitions) · products (list + editor) · categories · inventory · audience · wallet (ledger table + top-up form) · payments · discounts · shipping · tax · settings (multi-tab) · appearance/theme editor · invoices · preview.

**Admin:** KPI dashboard · shops (verify/suspend) · users · plans and entitlements · wallet approvals · revenue and ledger · theme template publishing · global categories · featured shops · reports · audit log · platform settings · **data explorer**.

**Theme editor** (the highest-value UI in the product): left = section list (drag to reorder, add/remove), center = live preview rendering the *same* section registry against the draft config, right = property panel for the selected section. Autosave to a draft, explicit publish, undo/redo stack in Zustand.

**Data explorer:** consumes `/api/v1/openapi.json`, builds a filterable/sortable/paginated table per `GET` endpoint, with a confirmation-gated mutation panel. Admin-only; every action written to `audit_log`.

### 6.3 Shared UI

shadcn/ui components vendored into `packages/ui` (not an npm dep — you own the source). Theme tokens exposed as CSS custom properties so the shadcn theme and the storefront theme JSON resolve through the same variable names. Tailwind v4 with a shared preset.

---

## 7. Phased Delivery

Each phase ends with something demonstrable and a written exit checklist. No phase starts before the previous one passes.

---

### Phase 0 — Foundations (~1 week)

**Goal:** empty skeleton that builds, runs, and deploys end to end.

- `web`: Turborepo + pnpm workspaces, TS strict, Tailwind v4, ESLint boundary rule, `packages/ui` with shadcn primitives, `packages/config`.
- `api`: Spring Boot 3.5.x + Java 21, Gradle, Postgres via Testcontainers, Flyway, springdoc, `/actuator/health`.
- Keycloak: 2 realms, 3 clients (`customer-web`, `console`, `api`), PKCE, redirect URIs, realm roles.
- Nginx: vhost config, `/api` -> jar, `/` -> Next.js, `/console` -> Vite static with SPA fallback.
- CI: GitHub Actions per repo — `pnpm build` + `tsc`; `./gradlew build` + tests.

**Exit:** `http://localhost` serves a Next.js page, `/console` serves a Vite page, `/api/v1/ping` returns 200 through Nginx, and a token from each realm is accepted by the resource server.

---

### Phase 1 — Vertical Slice: Shop -> Product -> Order (~3 weeks)

**Goal:** one complete money path, end to end, with no shortcuts. This de-risks the entire project.

1. **Identity sync** — first-login profile sync from both realms into `app_user`; the `SHOP` role provisions a `shop` row in `DRAFT`.
2. **Shop signup** — basic details, slug generation with collision retry, category selection.
3. **Admin verification** — shop submits documents, admin approves/rejects with a note. The gate that keeps fake shops out.
4. **Product, minimal** — one product, up to N variants, Cloudinary upload, price in minor units, activate.
5. **Storefront, minimal** — one hardcoded default theme, SSR, list + detail, add to cart.
6. **Checkout** — guest email, address, shipping zone rate, tax rule, discount code, server-side quote, order creation.
7. **Payment** — `PaymentGateway` interface; `CodGateway` + `BankTransferGateway` live; `EsewaGateway` stubbed behind the same interface.
8. **Wallet + commission** — top-up request, admin approval, ledger entries, **5% debit on accept**, **reversal on cancel**, insufficient-balance guard.
9. **Order lifecycle** — PENDING -> ACCEPTED -> PROCESSING -> SHIPPED -> DELIVERED, plus CANCELLED, with history and inventory movement.
10. **Notifications** — outbox + RabbitMQ + email worker. Push deferred to Phase 5.
11. **Follower count** — the counter and the increment path (D13, no UI beyond a number).

**Exit:** a verified shop publishes a product, a customer orders and pays by COD, the shop's wallet drops by exactly 5%, cancellation puts it back, the ledger reconciles against the balance cache, and the flow is covered by integration tests against Testcontainers.

**This is where the plan's risk lives.** If the wallet math or the state machine is wrong here, everything downstream inherits it.

---

### Phase 2 — Catalog and Inventory Depth (~2 weeks)

Categories and subcategories (2 levels, drag-order) · product editor (images, variants matrix, bulk edit) · inventory with movements, low-stock alerts, CSV import/export · product search with Postgres FTS + trigram typo tolerance · SEO fields · product duplication · archive/restore · drafts.

**Exit:** a shop manages a 500-SKU catalog including variants and stock adjustments, with every quantity change traceable.

---

### Phase 3 — Theme Engine v1 (~3 weeks)

Section registry (~15 core sections first) · zod theme schema, versioned · `theme_template` marketplace table and admin CRUD · shop theme editor with live preview · token system mapping to shadcn CSS vars · hero/header-page/banner/footer assets · default templates per shop category.

**Exit:** an admin publishes a template, a shop installs it, customizes sections and tokens in the editor, publishes, and the SSR storefront renders it — visually identical to the editor preview.

---

### Phase 4 — Shop Operations (~2 weeks)

Orders board with filters, bulk status transitions, printable order view · **invoice generator** (PDF, configurable template) · discount system (percent/fixed/free-shipping, limits, expiry) · audience list with spend stats, CSV export, private notes · full shop settings: general, address, social, checkout, notifications, order-number format, invoice template, email templates · payment method config incl. eSewa merchant ID/secret · low-stock notification.

**Exit:** a shop runs its entire day-to-day operation without admin intervention.

---

### Phase 5 — Customer Portal Completion (~2 weeks)

**eSewa gateway live** with server-to-server verification and callback (D11) · customer account: order history, tracking timeline, reorder · addresses CRUD · follow/unfollow shops · product reviews and ratings · search · wishlist · gallery polish: featured shops, category browse, sorting, empty and error states · Firebase push for order status · Nepali localization scaffolding.

**Exit:** a customer can discover a shop, follow it, order via eSewa, track the order by push notification, and reorder in one click.

---

### Phase 6 — Subscriptions and Revenue (~2 weeks)

Plans CRUD · entitlements engine + `TenantGuard` enforcement on every gated backend operation · subscription request -> top-up -> admin approval -> activation -> expiry job · commission waiver for subscribed shops · admin revenue dashboard (MRR, commission, top-ups, ARPU) · shop subscription screen with upgrade/downgrade/cancel · feature-limit enforcement with clear upgrade prompts in the UI.

**Exit:** a shop upgrades, its commission drops to 0% and paid templates unlock, the ledger records the charge, and expiry restores the free tier correctly.

---

### Phase 7 — Admin Platform (~2 weeks)

KPI dashboard (GMV, orders, ARPU, commission, active shops, churn) · shop management: verify, suspend, feature, search · user management with cross-realm awareness · **generic data explorer** over the OpenAPI spec · reports (sales, products, shops, taxes, payouts) with CSV export · audit log viewer with diff view · platform settings incl. commission rate · support tooling (notification history, email log) · consolidated notifications across admin and shops.

**Exit:** every endpoint in the API is reachable from the admin UI, and every privileged action lands in the audit log.

---

### Phase 8 — Returns, Refunds and Advanced Selling (~2 weeks)

Return and refund workflow with commission reversal (D14) · partial refunds · social selling: shareable product links, **WhatsApp order button**, copy-to-clipboard shop link (your multi-channel requirement) · pickup-location orders · abandoned-cart reminders · scheduled/promotional campaigns · tax reports.

**Exit:** a full return-and-refund cycle reconciles the wallet correctly and the shop can share a product to WhatsApp with a working deep link.

---

### Phase 9 — Polish, Scale and Hardening (ongoing)

Paid custom CSS with a strict sanitizer (D15) · follower feed and rewards (D13) · custom domains · multi-warehouse stock · product comparison · recommendations · advanced analytics · performance pass (image optimization, caching, query tuning) · observability (OpenTelemetry, structured logs, error tracking) · security review · backup and restore drills.

---

## 8. Testing Strategy

| Layer | Tool | Focus |
|---|---|---|
| Unit | JUnit 5 + AssertJ | Money math, commission calculation, discount rules, state-machine transitions, theme schema validation. **100% coverage on the `wallet` and `order` packages** — no exceptions. |
| Integration | Spring Boot Test + Testcontainers (Postgres, RabbitMQ) | Repository behaviour, tenant isolation, multi-realm JWT validation, outbox relay, ledger reconciliation |
| Contract | springdoc schema -> generated client types | `packages/api-client` types regenerated in CI; drift fails the build |
| E2E | Playwright | Shop signup -> publish -> order -> pay -> accept -> ship: one golden path per phase, in CI |
| Architecture | ArchUnit | Resource server exposes **no** auth endpoints; `packages/*` never imports `apps/*`; no entity leaks past its module boundary |

Load-tested before Phase 5: storefront with 1,000 concurrent readers, order placement at 20/sec.

---

## 9. Deployment (manual, by you)

Per D16. Two independent deploy paths, no CI/CD pipeline required.

**Backend:** `./gradlew bootJar` -> upload -> `systemd` unit with an env file -> `systemctl restart shop-api`. Flyway migrates on boot; take a `pg_dump` first. Zero-downtime via a two-instance blue/green swap behind Nginx if you want it.

**Frontend:** `pnpm build` in `apps/customer` -> `next start` (or standalone output) behind Nginx. `apps/console` -> static `dist/` served directly with an SPA `try_files` fallback. **They never deploy together.**

**Supporting services** (you manage): PostgreSQL, RabbitMQ, Redis, Keycloak. Backups: nightly `pg_dump`, off-site copy, restore tested quarterly.

**Environments:** local Docker Compose (Postgres, RabbitMQ, Redis, Keycloak) mirroring production, so nothing surprises you.

---

## 10. Risks and Mitigations

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | **Commission math is wrong or double-debits** | High — real money, trust, legal exposure | Ledger-only balance, unique `idempotency_key`, rate snapshotted at accept, 100% unit coverage on wallet, nightly reconciliation job alerting on drift |
| R2 | **Two-realm JWT handling is subtly wrong** | High — auth holes or total lockout | Explicit `MultiIssuerJwtDecoder`, per-realm role mapping tested, cross-realm role rejected, startup self-test, security integration tests |
| R3 | **Theme JSON is a foot-gun** — a shop publishes a broken storefront | Medium | zod validation on write *and* render, `schemaVersion` + migrations, unknown section -> visible placeholder, always-one fallback template |
| R4 | **Tenant isolation leaks shop A's data to shop B** | Critical | Single `TenantContext` filter, `TenantAwareSpecification` on every query, an integration test that runs every shop-scoped repository as two different tenants |
| R5 | **Events lost between DB commit and RabbitMQ** | Medium — missing orders/notifications | Outbox pattern from Phase 1, not retrofitted |
| R6 | **Phase 3 theme engine is underestimated** | Medium — schedule slip | Registry is closed and finite; ship 15 sections, not 25; no custom code execution ever |
| R7 | **eSewa sandbox/production friction** | Medium | `PaymentGateway` interface; COD and bank transfer work independently, so the platform is never blocked on eSewa |
| R8 | **Shops under-fund the wallet and cannot accept orders** | Medium — support load | Pre-accept warning, low-balance threshold alerts, admin top-up queue, an admin "accept anyway" override that writes an audit entry |
| R9 | **Monolith hits a real ceiling** | Low–Medium | Modular boundaries from day one make extraction mechanical; do not preemptively split |
| R10 | **Custom CSS XSS / layout destruction** (D15) | Medium | Deferred to Phase 9 behind a strict sanitizer and a preview gate |

---

## 11. Open Questions

These do not block Phase 0 or Phase 1, but each needs an answer before the phase that depends on it.

1. **Commissionable base** — the plan assumes `(subtotal − discount)`, excluding shipping and tax. Confirm.
2. **Guest checkout** — allowed by default, or account required? Affects the `customer` upsert path.
3. **eSewa credentials** — do you already have a merchant account, and is there a sandbox? Blocks Phase 5.
4. **Plan pricing** — actual NPR prices and durations for the Pro tiers.
5. **Commission rate** — fixed 5%, or configurable per shop/category from Phase 1?
6. **Shop review** — one-shot approval, or can a shop re-submit after rejection?
7. **Refunds** — who pays the refund, the shop or the platform, and does commission reverse on a *partial* refund? Blocks Phase 8.
8. **Product moderation** — do products need admin approval before going live, or does shop verification suffice?
9. **Nepali localization** — Nepali-only confirmed. Should the storefront's default language become per-shop configurable?
10. **Moderation and reporting** — customer-reported content and shop takedown workflow. Not currently scoped.

---

## Appendix A — Environment Variables

**API:** `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_URL`, `RABBITMQ_URL`, `KEYCLOAK_OPS_ISSUER`, `KEYCLOAK_CUSTOMER_ISSUER`, `KEYCLOAK_JWK_SET_URI`, `CLOUDINARY_*`, `FIREBASE_*`, `SMTP_*`, `ESEWA_*`, `PLATFORM_COMMISSION_RATE_BP`, `CORS_ORIGINS`

**customer:** `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_KEYCLOAK_URL`, `NEXT_PUBLIC_OPS_REALM`, `NEXT_PUBLIC_CUSTOMER_REALM`, `NEXT_PUBLIC_FIREBASE_*`

**console:** `VITE_API_URL`, `VITE_KEYCLOAK_URL`, `VITE_OPS_REALM`, `VITE_FIREBASE_*`

## Appendix B — Keycloak Setup Checklist

- Realm `ops`: roles `ADMIN`, `SHOP`; client `console` (public, PKCE S256); client `api` (bearer-only).
- Realm `customers`: role `CUSTOMER`; client `customer-web` (public, PKCE S256); client `api-customer` (bearer-only).
- Separate bearer-only clients let you revoke tokens per realm independently.
- Registration: self-signup enabled in `ops` **only for the SHOP** role; `customers` realm open registration with email verification.
- Realm `ops` must **not** contain a `CUSTOMER` role, and vice versa — verified by the Phase 0 self-test.
- Token lifetimes: access 5 min, refresh 30 days. No cross-realm refresh issues, since they are separate issuers.
