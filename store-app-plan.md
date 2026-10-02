# store-app — Multi-Tenant Shop Platform

**Build Plan v2** · 3 portals (Admin / Shop / Customer) · 2 frontends · 1 resource server · Keycloak · PostgreSQL

Scope: **build, run on localhost, test the flow.** No CI, no nginx, no deployment content.
Status: Draft for review · Last updated: 2026-10-01

---

## 0. What Changed From v1

| Area | v1 | v2 (this doc) | Why |
|---|---|---|---|
| Build tool | Gradle (Kotlin DSL) | **Maven** (`./mvnw`), Spring Boot **3.5.16** | Your start.spring.io project shipped 4.1.1; downgraded (section 3.1) |
| Infra | Docker Compose, nginx, systemd | **None.** Postgres + RabbitMQ already running locally | Your environment |
| Integration tests | Testcontainers | **Direct against local Postgres/RabbitMQ**, separate `*_test` database | No Docker needed |
| `shop_category` table | Per-shop 2-level taxonomy | **Removed.** Replaced by a hardcoded `ShopType` enum | You asked to simplify |
| Sub-category | `parent_id` self-reference, 2 levels | **Removed entirely** | You asked to remove |
| Global `category` table | Admin-managed, 2 levels | **Removed entirely** | Replaced by the enum system |
| Product category | Free-text / shop taxonomy FK | **Hardcoded nested enums**, validated per shop type | You asked for enums |
| Cloudinary | URL + public_id | **URL + public_id, organised `shop/{shopId}/...`** | Your convention |
| Deployment (§9) | Full ops chapter | **Removed.** Ops trimmed to a one-line "not in scope" note | You asked to drop it |
| CI, GitHub Actions | Present in Phase 0 | **Removed** | You asked to drop it |
| Observability, backups, blue-green | Present | **Removed** | You asked to drop it |
| Keycloak | Concrete config | **Live URLs hardcoded** — `https://auth-master.yenyasoft.com`, realms `ops` / `customers` (both verified reachable) | Real values supplied |

### Decisions locked in this round

- **D17** — Shop type is a **hardcoded enum** chosen once at signup. No per-shop custom taxonomy, no sub-categories.
- **D18** — Product category is a **nested enum inside `ShopType`**, so a pharmacy can never assign `KURTA`. The API validates the (shopType, productCategory) pair.
- **D19** — Cloudinary path is `shop/{shopId}/...`; DB stores **both** `url` and `public_id` (you approved keeping `public_id`).
- **D20** — Build and test on localhost only. Ops/deployment is out of scope for this plan.

---

## 1. Confirmed Decisions (full list)

| # | Decision | Choice |
|---|---|---|
| D1 | Storefront rendering | SSR inside the **Next.js** customer portal (`/s/[shopSlug]`) |
| D2 | Money movement | **Prepaid platform wallet.** Shop pre-funds; 5% debited at order **ACCEPT**. Customer pays the shop's own eSewa/COD/bank. |
| D3 | Keycloak tenancy | **2 realms.** Realm `ops`: `ADMIN`, `SHOP`. Realm `customers`: `CUSTOMER`. One owner per shop, no staff role. |
| D4 | Subscription gating | Feature flags in Postgres `entitlements`. Keycloak never holds billing state. |
| D5 | Admin API coverage | Curated screens **+** a generic OpenAPI-driven data explorer. |
| D6 | Spring Boot | **3.5.16**, Java 21, **Maven** (`./mvnw`). Downgraded from the Initializr default of 4.1.1 for springdoc stability — section 3.1. |
| D7 | Subscription billing | **Manual top-up + admin approval.** No recurring charging. |
| D8 | Theme engine | JSON config + **fixed versioned React section registry** + admin-published **template marketplace**. |
| D9 | Repos | **2 repos:** `web` (Turborepo) and `api` (Spring Boot/Maven). |
| D10 | Repo layout | `web/apps/customer` (Next.js), `web/apps/console` (Vite: shop + admin), `web/packages/*` shared. |
| D11 | Payment verification | Backend verifies eSewa **server-to-server**; COD and bank transfer via manual mark + receipt upload. Behind a `PaymentGateway` interface. |
| D12 | Phase 1 slice | **Vertical slice:** shop signup -> verification -> product -> order -> wallet debit -> notification. |
| D13 | Followers | **Count only.** No feed, no rewards. |
| D14 | Returns/refunds | **Deferred** to Phase 8. Cancellation + commission reversal ship early. |
| D15 | Paid custom CSS | **Deferred** to Phase 9, behind a strict sanitizer. |
| D16 | Infrastructure | Postgres 16.14, RabbitMQ 3.13.7, Redis 8.10.0 already running in Docker locally. No nginx, no systemd. |
| D21 | Keycloak | **Remote** instance at `https://auth-master.yenyasoft.com` (TLS-terminated). Realms `ops` and `customers`, both verified live. |
| D22 | Database | New `store_app` + `store_app_test` databases. The existing `storefront` database is **off limits**. |
| D23 | Credentials | Real credentials live in a git-ignored `application-local.yml`, imported via `spring.config.import`. Never in `application.yml`. |
| D24 | RabbitMQ user | Container's admin user `monu`, **not** `guest` — the `rabbitmq:3.13` image refuses `guest` for remote connections. |
| D25 | Realm clients | `store-app-console` (ops), `store-app-customer-web` (customers), plus bearer-only `store-app-api` / `store-app-api-customer`. Created by `scripts/keycloak-setup.sh`, idempotent. |
| D26 | Test users | `ops.test` (SHOP), `admin.test` (ADMIN), `cust.test` (CUSTOMER), password `iloveu`. Seeded by `scripts/keycloak-seed-test-users.sh`. Direct-access grant enabled on the two public clients **for tests only** — the apps use authorization_code + PKCE. |
| D27 | Shop types | **19** verticals, **133** category assignments, declared as Java enums. Verified by an exhaustive test, not a spot check. |
| D28 | Web workspace | Turborepo + pnpm, **10 packages**, 2 apps. Every cross-package import must be a declared dependency — enforced by an audit, see Appendix G. |
| D29 | Shop type change | **Self-serve, by the shop owner**, from general settings. Not admin-only. |
| D30 | Type-change safety | The picker offers **only reachable types** — those that every existing product still fits. Blocked types are returned with the exact categories to fix. Admin can override with an audit entry. |
| D17 | Shop category | **Hardcoded `ShopType` enum.** No table, no sub-category. |
| D18 | Product category | **Nested enums** per shop type, validated as a pair. |
| D19 | Media storage | Cloudinary folder `shop/{shopId}/...`; DB stores `url` **and** `public_id`. |
| D20 | Scope | Build, run on localhost, test the flow. Ops explicitly out of scope. |

### Implications worth calling out

- **D3 forces a multi-issuer resource server.** Two realms means two `JwtDecoder`s keyed on `iss` (section 5.2). This is real design work, not a config tweak.
- **D2 is the highest-risk part of the system.** The wallet ledger, commission math, and the "cannot accept without funds" guard are where money bugs live.
- **D17/D18 collapse two tables and an entire admin CRUD module** into a handful of Java files. This is a large net simplification: no category management UI, no drag-order, no slug uniqueness, no cross-shop category reporting. The trade-off is that a shop can never invent its own taxonomy — acceptable given the fixed shop types.
- **D1 means the theme engine renders in Next.js** but is *authored* in the Vite console. Both read the same `packages/theme-engine`, so the preview is faithful.

---

## 2. System Architecture

```
                        ┌──────────────────────┐
   Browser ────────────▶│  localhost:3000 (Next)│
                        │  localhost:5173 (Vite)│
                        │  auth-master.yenyasoft.com (Keycloak)
                        └──────┬───────┬───────┘
                               │       │
              ┌────────────────┘       └──────────────┐
              ▼                                      ▼
   ┌────────────────────┐              ┌──────────────────────────┐
   │ apps/customer      │              │ apps/console             │
   │ Next.js (SSR)      │              │ Vite + React (SPA)       │
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
   │  ADMIN, SHOP     │              │  ADMIN, SHOP             │
   ├──────────────────┤              └──────────────────────────┘
   │ realm: customers │  Both realms issue JWTs.
   │  CUSTOMER        │  Resource server validates BOTH issuers.
   └──────────────────┘
             │                                    │
             └──────────────┬─────────────────────┘
                            ▼
             ┌──────────────────────────────────┐
             │  Spring Boot 3.5 (api)          │
             │  localhost:8080                  │
             │  Resource Server ONLY            │
             │  · no login endpoints            │
             │  · multi-issuer JWT validation   │
             │  · tenant filter (shop_id)       │
             │  · category enum validation      │
             │  · REST + OpenAPI (springdoc)    │
             └────┬──────────┬──────────┬───────┘
                  │          │          │
        ┌─────────▼──┐  ┌────▼─────┐  ┌─▼──────────┐
        │ PostgreSQL │  │  Redis   │  │ RabbitMQ   │
        │ LOCAL      │  │  cache   │  │ LOCAL      │
        │ (running)  │  │  rate    │  │ (running)  │
        └────────────┘  │  limits  │  └────┬────────┘
                        └──────────┘  ┌────┴─────────────┐
                                        │  workers        │
                                        │  · email        │
                                        │  · FCM push     │
                                        │  · ledger       │
                                        └────┬──────┬─────┘
                                             │      │
                                     ┌───────▼──┐ ┌─▼────────┐
                                     │Cloudinary │ │ Firebase │
                                     │ shop/{id}/│ │  FCM     │
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
| `packages/enums` | **Generated from the backend enums** — `ShopType`, `ProductCategory`, order statuses | both |
| `packages/state` | Zustand stores (auth cache, theme editor draft, cart, console filters) | both |
| `packages/config` | TS config, eslint, prettier, env var schema | both |
| `packages/icons` | react-icons curated re-exports | both |

Rule: `apps/*` may import from `packages/*`; `packages/*` may **never** import from `apps/*`. Enforced by an ESLint `no-restricted-imports` rule.

> **New in v2:** `packages/enums` solves a real problem. The shop type and product category lists are hardcoded in Java, but the frontend dropdowns need the same list. Rather than hand-maintaining it in two places, the backend exposes `GET /api/v1/meta/shop-types` returning the full tree, and this package holds the typed response plus a small cache. Single source of truth stays Java.

### 2.2 Why the storefront is SSR in Next.js

- Product pages must be indexable and share-previewable (your WhatsApp / social selling requirement).
- The theme engine resolves a shop's theme + products on the server and streams HTML.
- The Vite console stays a pure SPA — a broken console build can never take down a storefront.

---

## 3. Repository Layout

Target: `/Users/monusiddiki/Desktop/store-app/` containing both repos.

```
store-app/
├─ api/                          # Spring Boot 3.5.x, Maven, Java 21
│  ├─ pom.xml
│  └─ src/
│     ├─ main/
│     │  ├─ java/com/storefront/store/
│     │  │  ├─ common/           # tenant filter, error model, pagination, money, ids
│     │  │  ├─ security/         # multi-issuer JWT, role mapping, method security
│     │  │  ├─ identity/         # user/shop profile mirror, keycloak linkage
│     │  │  ├─ shop/             # shop, settings, verification, subscription
│     │  │  ├─ catalog/          # ShopType, ProductCategory, product, variant, media
│     │  │  ├─ inventory/        # stock, movements, low-stock alerts
│     │  │  ├─ order/            # order, items, state machine, invoice
│     │  │  ├─ payment/          # gateway interface, esewa, cod, bank
│     │  │  ├─ wallet/           # ledger, commission, topup
│     │  │  ├─ theme/            # theme config, templates, entitlements
│     │  │  ├─ customer/         # audience, followers, addresses
│     │  │  ├─ notification/     # rabbit producers/consumers, fcm, email
│     │  │  ├─ admin/            # kpis, reports, audit, settings
│     │  │  └─ platform/         # commission config, feature flags, meta endpoints
│     │  └─ resources/
│     │     ├─ application.yml          # delete the default .properties
│     │     └─ db/migration/     # Flyway
│     └─ test/java/com/storefront/store/
└─ web/                          # Turborepo, pnpm workspaces
   ├─ apps/
   │  ├─ customer/               # Next.js, App Router, SSR storefront
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
   │        ├─ admin/
   │        ├─ shop/
   │        ├─ theme-editor/
   │        ├─ explorer/
   │        └─ main.tsx
   └─ packages/                  # the 10 shared packages above
```

### 3.1 The Maven project (existing, confirmed)

**Location:** `/Users/menusiddiki/Desktop/store-app/store/` — exists, generated from start.spring.io.
Keep it. Change the parent version and add the dependencies.

| Field | Current | Target |
|---|---|---|
| `groupId` | `com.storefront` | **keep** |
| `artifactId` | `store` | **keep** |
| `version` | `0.0.1-SNAPSHOT` | keep |
| `java.version` | `21` | **keep** (Boot 3.5 supports 21) |
| `spring-boot-starter-parent` | **4.1.1** | **3.5.16** |

Package root: `com.storefront.store` (`StoreApplication.java` already there).

> **Why downgrade 4.1.1 -> 3.5.16.** springdoc-openapi is the only OpenAPI 3.1 generator that
> serves the `/api/v1/openapi.json` contract, and the entire **Admin Data Explorer** (D5) is
> generated from that spec. springdoc **2.9.x** is the mature, widely-deployed line for Boot 3;
> **3.x** targets Boot 4 and is far newer. Building the data explorer on the newest combination
> buys nothing. Everything else in your stack — Postgres 16.14, RabbitMQ 3.13.7, Redis 8.10,
> Java 21, Keycloak OIDC — is byte-for-byte identical on both lines, so nothing else in this
> plan changes.

#### Replacement `pom.xml`

Keep the existing `<modelVersion>`, parent (with the new version), `groupId`, `artifactId`,
`version`, and `java.version`. Replace the `<dependencies>` block with:

```xml
<properties>
    <java.version>21</java.version>
    <springdoc.version>2.9.1</springdoc.version>
    <openpdf.version>2.0.3</openpdf.version>
</properties>

<dependencies>
    <!-- web / REST -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- OAuth2 resource server (validates BOTH Keycloak realms) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>

    <!-- @PreAuthorize method security for the role/tenant guards -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-cache</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <!-- required separately since Flyway 9.x -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>

    <!-- OpenAPI 3.1 -> /api/v1/openapi.json, feeds the Admin Data Explorer (D5) -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>${springdoc.version}</version>
    </dependency>

    <!-- PDF invoice generation (Phase 4) -->
    <dependency>
        <groupId>com.github.librepdf</groupId>
        <artifactId>openpdf</artifactId>
        <version>${openpdf.version}</version>
    </dependency>

    <!-- test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.amqp</groupId>
        <artifactId>spring-rabbit-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### Two environment notes

1. **`mvn` is not on your PATH.** Use the bundled wrapper `./mvnw` (Maven 3.9.16), which the
   project already ships in `.mvn/wrapper/`. All commands in this plan use `./mvnw`, not `mvn`.
   You can also `brew install maven` if you prefer a global binary.
2. **`src/main/resources/application.properties` exists** and is the Initializr default. This plan
   specifies `application.yml` throughout (section 5, Appendix A). **Delete the `.properties`
   file** — having both is a silent config-precedence trap, because Spring Boot loads
   `.properties` *and* `.yml`, and the property file can override your YAML without warning.

#### Verify the downgrade worked

```bash
cd ~/Desktop/store-app/store
./mvnw -q help:evaluate -Dexpression=spring-boot.version -DforceStdout
#   must print: 3.5.16

./mvnw clean install
#   must reach: BUILD SUCCESS
#   if springdoc fails to resolve, check the version is 2.x, never 3.x

./mvnw spring-boot:run
#   -> http://localhost:8080/actuator/health
```

### 3.2 Build and run commands

```bash
# API
cd api
cd store && ./mvnw clean install         # compile + run all tests
./mvnw spring-boot:run             # start on http://localhost:8080
./mvnw -Dtest=WalletLedgerTest test   # single test class

# web
cd web
pnpm install
pnpm --filter customer dev        # Next.js  on http://localhost:3000
pnpm --filter console dev         # Vite    on http://localhost:5173
pnpm build                        # both production builds
```

---

## 4. Data Model (PostgreSQL)

Conventions: `uuid` PKs, `timestamptz`, `snake_case`, `created_at`/`updated_at` on all mutable tables. All money is **`bigint` minor units (paisa)** — never floats, never `numeric` for storage.

### 4.1 The category simplification (D17 / D18)

**Deleted from v1:** `category` table (global taxonomy), `shop_category` table (per-shop taxonomy), and the `parent_id` self-reference that gave both of them a sub-category level.

**Replaced by:** two Java enums and two `varchar` columns. There is no category table at all.

`shop.shop_type` is a `varchar` holding one `ShopType` constant, chosen once at signup. It cannot be changed afterwards without admin action.

`product.product_category` is a `varchar` holding one `ProductCategory` constant. The pair `(shop_type, product_category)` is validated in three places:

1. **Backend, on write** — a `CategoryValidator` rejects a mismatch with `400 INVALID_CATEGORY`.
2. **Backend, on read** — the product DTO exposes categories only valid for the owning shop's type.
3. **Frontend, on selection** — the category dropdown is filtered by the shop's type, and the product form disables invalid options.

The DB stores `varchar` rather than a native Postgres `enum` type on purpose: adding a category then becomes an `ALTER TYPE` migration instead of a table rewrite, and a native enum makes "temporarily remove a category" painful.

**`ShopType` enum** (`catalog/ShopType.java`):

```java
public enum ShopType {
    PHARMACY, FASHION, GROCERY, TECH, PLANT_SHOP, BABY_SHOP, MOBILE_SHOP,
    COSMETICS, JEWELLERY, FURNITURE, HOME_DECOR, BOOKS, SPORTS, TOYS,
    PET_SUPPLIES, FLOWERS, ELECTRONICS, GROCERY_BEVERAGES, BEAUTY_SERVICES
}
```

**`ProductCategory`** is a **nested enum inside `ShopType`**, so a pharmacy's dropdown physically cannot offer `KURTA`:

```java
public enum ShopType {

    PHARMACY {
        @Override public Set<ProductCategory> categories() {
            return Set.of(MEDICINE, MEDICAL_DEVICE, SUPPLEMENT, PERSONAL_CARE, BABY_CARE);
        }
    },
    FASHION {
        @Override public Set<ProductCategory> categories() {
            return Set.of(MENS_CLOTHING, WOMENS_CLOTHING, KIDS_CLOTHING, FOOTWEAR,
                          ACCESSORIES, BAGS, JEWELRY, WATCHES);
        }
    },
    // ... 18 more

    /** The category enum for this shop type. */
    public abstract Set<ProductCategory> categories();

    public boolean allows(ProductCategory c) { return categories().contains(c); }

    public Set<ProductCategory> lookup() {
        return categories().stream()
            .map(c -> {
                try {
                    return Enum.valueOf(c.getDeclaringClass(), c.name());
                } catch (Exception ignored) { return null; }
            })
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}

public enum ProductCategory {
    // grouped by owner shop type, one flat list; membership is defined by ShopType.categories()
    MEDICINE, MEDICAL_DEVICE, SUPPLEMENT, PERSONAL_CARE, BABY_CARE,
    MENS_CLOTHING, WOMENS_CLOTHING, KIDS_CLOTHING, FOOTWEAR, ACCESSORIES,
    BAGS, JEWELRY, WATCHES, HANDBAG,
    RICE, LENTIL, SPICES, OIL, SNACKS, DAIRY, TEA, COFFEE,
    LAPTOP, DESKTOP, ACCESSORY, PERIPHERAL, CAMERA, AUDIO,
    INDOOR_PLANT, OUTDOOR_PLANT, SEED, SOIL, FERTILIZER, PLANTER,
    DIAPER, BABY_FOOD, BABY_CLOTHING, STROLLER, TOY_BABY,
    SMARTPHONE, FEATURE_PHONE, TABLET, SMARTWATCH, MOBILE_ACCESSORY, SIM_CARD,
    SKINCARE, HAIRCARE, MAKEUP, FRAGRANCE, NAIL_CARE,
    // ...
}
```

> **Simplification note.** A truly nested enum (`ShopType.PHARMACY.ProductCategory`) is possible in Java but hurts autocomplete and iteration. The pattern above — one flat `ProductCategory` enum plus a `categories()` contract on `ShopType` — gives you the same compile-time guarantee (the allowed set is defined in exactly one place, next to the shop type) while keeping a single importable type for the API layer and the frontend. If you later want strict physical nesting, it is a mechanical refactor of one file.

**Category list per shop type** (the starting set; extend as needed):

| ShopType | ProductCategory values |
|---|---|
| `PHARMACY` | MEDICINE, MEDICAL_DEVICE, SUPPLEMENT, PERSONAL_CARE, BABY_CARE |
| `FASHION` | MENS_CLOTHING, WOMENS_CLOTHING, KIDS_CLOTHING, FOOTWEAR, ACCESSORIES, BAGS, JEWELRY, WATCHES, HANDBAG |
| `GROCERY` | RICE, LENTIL, SPICES, OIL, SNACKS, DAIRY, TEA, COFFEE, SUGAR_SALT, MASALA |
| `TECH` | LAPTOP, DESKTOP, ACCESSORY, PERIPHERAL, CAMERA, AUDIO, STORAGE, NETWORKING |
| `PLANT_SHOP` | INDOOR_PLANT, OUTDOOR_PLANT, SEED, SOIL, FERTILIZER, PLANTER, POTTING_MIX |
| `BABY_SHOP` | DIAPER, BABY_FOOD, BABY_CLOTHING, STROLLER, BABY_TOY, BABY_CARE, BABY_FURNITURE |
| `MOBILE_SHOP` | SMARTPHONE, FEATURE_PHONE, TABLET, SMARTWATCH, MOBILE_ACCESSORY, SIM_CARD, EARPHONE |
| `COSMETICS` | SKINCARE, HAIRCARE, MAKEUP, FRAGRANCE, NAIL_CARE, SUNSCREEN |
| `JEWELLERY` | RING, NECKLACE, EARRING, BRACELET, BANGLES, WEDDING_SET, SILVER_JEWELRY, GOLD_JEWELRY |
| `FURNITURE` | SOFA, BED, TABLE, CHAIR, WARDROBE, MATTRESS, TV_UNIT, OFFICE_DESK |
| `HOME_DECOR` | WALL_DECOR, LIGHTING, RUG, CURTAIN, VASE, CUSHION, WALLPAPER, MIRROR |
| `BOOKS` | FICTION, NON_FICTION, ACADEMIC, CHILDREN_BOOK, RELIGIOUS, NOTEBOOK, STATIONERY |
| `SPORTS` | FOOTWEAR_SPORT, GYM_EQUIPMENT, SPORTSWEAR, BICYCLE, ACCESSORY_SPORT, TEAM_SPORT |
| `TOYS` | BOARD_GAME, SOFT_TOY, BUILDING_TOY, PUZZLE, REMOTE_CONTROL, EDUCATIONAL_TOY |
| `PET_SUPPLIES` | PET_FOOD, PET_TOY, PET_ACCESSORY, PET_CARE, AQUARIUM |
| `FLOWERS` | ROSE, ORCHID, TULIP, LILY, BOUQUET, POTTED_FLOWER, FLOWER_DECOR |
| `ELECTRONICS` | TELEVISION, AUDIO_SYSTEM, CAMERA_ELECTRONIC, GADGET, HOME_APPLIANCE, SMARTHOME |
| `GROCERY_BEVERAGES` | JUICE, SOFT_DRINK, WATER, ENERGY_DRINK, MILK, TEA_BEVERAGE, COFFEE_BEVERAGE |
| `BEAUTY_SERVICES` | HAIRCUT, SPA, MASSAGE, NAIL_SALON, SKIN_TREATMENT, BRIDAL_MAKEUP |

**Meta endpoint** so the frontend never hardcodes this list:

```
GET /api/v1/meta/shop-types
-> [{ "code": "PHARMACY",
      "label": "Pharmacy",
      "icon": "Pill",
      "categories": ["MEDICINE", "MEDICAL_DEVICE", ...] }]

GET /api/v1/meta/shop-types/{type}
-> { "code": "FASHION", "label": "Fashion", "categories": [...] }
```

Both cached in Redis for 1 hour; the frontend's `packages/enums` wraps them in react-query.

### 4.2 Identity and tenancy

```sql
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
  slug               citext not null unique,   -- store.app/s/<slug>
  name               text not null,
  description        text,
  -- D17: hardcoded enum, NOT a foreign key. Chosen once at signup.
  shop_type          text not null,           -- ShopType code, e.g. 'PHARMACY'
  logo_url           text, favicon_url text, cover_url text,
  status             text not null default 'DRAFT',
     -- DRAFT | PENDING_REVIEW | VERIFIED | REJECTED | SUSPENDED
  verification_note  text,
  verified_at        timestamptz,
  commission_plan    text not null default 'FREE',   -- FREE | SUBSCRIBED
  follower_count     int  not null default 0,        -- counter only (D13)
  published_at       timestamptz,
  created_at         timestamptz not null default now(),
  -- shop_type must be a valid ShopType code; enforced by CategoryValidator, not a DB constraint
  check (shop_type = upper(shop_type))
);
create index on shop (status);
create index on shop (shop_type);
```

**Removed in v1:** the `category_id uuid references category(id)` column and the whole `category` table. A shop's vertical is now `shop_type`; a product's category is now `product_category`.

### 4.3 Catalog

```sql
product (
  id                uuid pk,
  shop_id           uuid not null references shop(id) on delete cascade,
  -- D18: hardcoded enum, validated against shop.shop_type. No sub-category.
  product_category  text not null,           -- ProductCategory code, e.g. 'MEDICINE'
  name              text not null,
  slug              citext not null,
  short_description text,
  description       text,                    -- sanitized rich text / markdown
  status            text not null default 'DRAFT',  -- DRAFT|ACTIVE|ARCHIVED
  base_price_minor  bigint not null default 0,
  compare_at_minor  bigint,                  -- "was" price
  cost_price_minor  bigint,                  -- shop-private margin data
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
create index on product (shop_id, product_category);
create index on product (product_category);   -- gallery "browse by category"

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
```

**Removed in v1:** `shop_category`, `product.category_id`, `product.tax_category`, and every `parent_id` self-reference.

**Still present and worth keeping:** `product.tax_category` was removed because a shop's `tax_rule` list (section 4.9) is small and applies shop-wide. Tax is resolved per order from the shop's active rule rather than per product. If you later need per-product tax rates, add a `tax_rule_id` FK to `product` then — it is a small additive change.

### 4.4 Media (D19) — Cloudinary, `shop/{shopId}/...`

```sql
-- One table for every Cloudinary asset in the system: product images, shop logo,
-- favicon, cover, banners, theme images, payment receipt QR codes.
media (
  id             uuid pk,
  shop_id        uuid not null references shop(id) on delete cascade,
  -- usage context, so the UI knows where an asset belongs
  usage          text not null,   -- PRODUCT_IMAGE | VARIANT_IMAGE | SHOP_LOGO
                                  -- | SHOP_FAVICON | SHOP_COVER | BANNER
                                  -- | THEME_IMAGE | PAYMENT_QR
  -- D19: folder convention shop/{shopId}/products/{productId}/...
  cloudinary_folder text not null,       -- 'shop/8f3a.../products/2b71...'
  -- D19: BOTH columns. public_id is required to delete or transform; url is what
  -- the frontend renders. public_id is never exposed in public DTOs.
  public_id      text not null,
  url            text not null,
  secure_url     text not null,
  width          int, height         int,
  format         text,                -- jpg|png|webp|avif
  bytes          int,
  alt_text       text,
  sort_order     int not null default 0,
  created_by     uuid references app_user(id),
  created_at     timestamptz not null default now()
);
create index on media (shop_id, usage);
create index on media (product_id) where product_id is not null;  -- see note
```

**Cloudinary folder convention** (D19), all uploads go through a single `CloudinaryService`:

```
shop/{shopId}/                          -- shop logo, favicon, cover
shop/{shopId}/products/{productId}/     -- product images
shop/{shopId}/products/{productId}/variants/{variantId}/
shop/{shopId}/theme/                    -- hero, banners, section backgrounds
shop/{shopId}/payments/                 -- bank transfer QR code
```

`product_media` from v1 is **replaced by the single `media` table** with a nullable `product_id` and `variant_id`. One table is simpler than three, and it makes the "shop's whole media library" screen (a real feature you listed under Media) a single query.

| Column | Why it exists |
|---|---|
| `url` / `secure_url` | What the browser renders. This is the only column the storefront ever sees. |
| `public_id` | Required by `cloudinary.uploader.destroy(publicId)`. **Without it you can never delete an image**, and orphaned uploads accumulate in your Cloudinary account forever. Not exposed in any public DTO. |
| `cloudinary_folder` | Lets you delete a whole shop's media on suspension, and audit which shop owns what. |
| `width`/`height`/`bytes` | Powers the upload-time validation (reject a 20 MB 6000px hero before it costs storage) and the layout-shift-safe `next/image` sizes. |

Upload flow: frontend requests a **signed upload signature** from the backend, uploads directly to Cloudinary from the browser, then POSTs the resulting `public_id` + `url` to create the `media` row. Images never transit your server. Signature endpoint:

```
POST /api/v1/shop/media/upload-signature
-> { "signature": "...", "timestamp": 1234567, "folder": "shop/8f3a.../products/2b71...", "cloudName": "..." }
```

### 4.5 Inventory

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

### 4.6 Orders — the core aggregate

```sql
shop_order (
  id                uuid pk,
  order_number      text not null,
  shop_id           uuid not null references shop(id),
  -- frozen at order time: the shop's type at purchase, for reporting
  shop_type_snapshot text not null,
  customer_id       uuid references app_user(id),   -- null for guest checkout
  guest_email       citext, guest_phone text,
  status            text not null default 'PENDING',
     -- PENDING | ACCEPTED | PROCESSING | SHIPPED | DELIVERED
     -- | CANCELLED | RETURNED | REFUNDED
  payment_status    text not null default 'PENDING',
     -- PENDING | PAID | FAILED | REFUNDED | PARTIALLY_REFUNDED
  payment_method    text not null,            -- ESAWA|COD|BANK_TRANSFER
  -- frozen at order time: what the customer actually bought
  item_category_snapshot jsonb not null,      -- [{"productId":"..","category":"MEDICINE"}]
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
  shipping_address  jsonb not null,           -- snapshotted: must survive later edits
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
create index on shop_order (placed_at);

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
  product_category text not null,           -- frozen enum value
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

> **New in v2:** `shop_type_snapshot` and `item_category_snapshot`. With categories now hardcoded enums rather than tables, historical reporting ("how many KURTA orders in March") would silently change meaning if a shop's type were ever reclassified. Snapshotting the enum value onto the order costs two columns and makes reports permanently correct.

### 4.7 Payments (D11)

```sql
shop_payment_method (
  id                   uuid pk,
  shop_id              uuid not null references shop(id) on delete cascade,
  method               text not null,          -- ESAWA|COD|BANK_TRANSFER
  is_enabled           boolean not null default true,
  esewa_merchant_id    text,
  esewa_secret_enc     bytea,                  -- encrypted at rest
  bank_name            text, bank_account_name text,
  bank_account_number_enc bytea, bank_qr_url text,  -- bank_qr_url -> media table
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
  receipt_media_id uuid references media(id),   -- bank-transfer proof upload
  idempotency_key text not null unique,
  created_at      timestamptz not null default now()
);
create index on payment_transaction (order_id);
create index on payment_transaction (gateway_ref);
```

`PaymentGateway` interface: `createPaymentIntent(order)`, `verify(reference)`, `refund(txn, amount)`. Implementations: `EsewaGateway`, `CodGateway` (no-op intent, manual confirm), `BankTransferGateway` (no-op intent, receipt upload + manual confirm). Adding Khalti or Fonepay later = one new class, zero changes to order code.

The eSewa callback endpoint is **public and unauthenticated** — authenticated instead by the transaction code being verified server-to-server before any state change.

### 4.8 Wallet, commission, top-up (D2) — highest-risk domain

**Invariant: the ledger is the truth. There is no mutable `balance` column.** `shop_wallet_balance` is maintained in the *same transaction* as every ledger insert purely as a read cache, and a scheduled job recomputes `SUM(amount_minor)` nightly and alerts on any drift.

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
  amount_minor   bigint not null check (amount_minor > 0),  -- sign from direction
  balance_after_minor bigint not null,     -- running balance, for auditability
  order_id       uuid references shop_order(id),
  subscription_id uuid references shop_subscription(id),
  payment_transaction_id uuid references payment_transaction(id),
  reversal_of    uuid references wallet_ledger_entry(id),  -- links reversal to debit
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
  reference       text,
  proof_media_id  uuid references media(id),
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

### 4.9 Subscriptions and entitlements (D4, D7)

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
  limit_value bigint,             -- capped features (max_products = 500)
  expires_at  timestamptz,
  updated_at  timestamptz not null default now(),
  primary key (shop_id, feature_key)
);

plan_entitlement (
  plan_id     uuid not null references subscription_plan(id) on delete cascade,
  feature_key text not null,
  enabled     boolean not null default false,
  limit_value bigint,
  primary key (plan_id, feature_key)
);
```

Renewal flow (D7): shop requests a plan -> pays the **platform's** eSewa/bank -> submits a top-up request tagged with the plan -> **admin approves** -> one transaction creates a `TOPUP_APPROVED` credit, activates `shop_subscription`, writes a `SUBSCRIPTION_CHARGE` debit -> `shop.commission_plan` flips to `SUBSCRIBED`. A scheduled job expires subscriptions past `expires_at` and flips shops back to `FREE` (and back to 5% commission).

### 4.10 Theme engine (D8)

```sql
theme_template (              -- admin-published marketplace
  id                uuid pk,
  code              text not null unique,
  name              text not null,
  description       text,
  preview_media_id  uuid references media(id),
  shop_type         text,                   -- nullable: which shop types it suits (enum)
  config            jsonb not null,         -- validated by the shared zod schema
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
  config         jsonb not null,
  schema_version int not null default 1,
  is_published   boolean not null default false,
  updated_at     timestamptz not null default now()
);
create unique index on shop_theme (shop_id) where is_published;  -- one live theme

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

**New in v2:** `theme_template.shop_type`. Since shop types are now a closed enum, an admin publishing a pharmacy-themed template can tag it `PHARMACY` and the shop's "browse templates" view filters by the shop's own type. This was impossible when categories were admin-editable rows.

**Theme JSON shape** (shared zod schema, versioned — the version lives on both template and shop theme so a renderer change can migrate old configs):

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
      "props": { "heading": "Trusted Pharmacy", "subheading": "...",
                 "imageUrl": "...", "cta": { "label": "Shop now", "href": "/c/all" } } },
    { "id": "cat-1", "type": "category-grid",
      "props": { "columns": 4, "source": "shop_categories", "limit": 8 } },
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

The `category-grid` section's `source: "shop_categories"` now resolves from the shop's `shop_type` category set, not a category table. This is where D17 pays off: the storefront's category navigation is derived from the enum, zero queries.

The renderer holds a **closed registry** — a `Record<SectionType, SectionComponent>` map. An unknown `type` renders a visible "unknown section" placeholder rather than crashing the page, and a missing registry entry is a build-time error, not a runtime surprise.

Planned section types (~25): `announcement-bar`, `hero`, `category-grid`, `product-carousel`, `product-grid`, `collection-list`, `promo-banner`, `image-with-text`, `rich-text`, `video`, `gallery`, `testimonials`, `faq`, `newsletter`, `countdown`, `trust-badges`, `feature-list`, `cta-banner`, `map`, `social-feed`, `divider`, `html` (sanitized), `spacer`, `unknown-section` (fallback).

### 4.11 Shop settings, audience, misc

```sql
shop_setting (            -- 1:1 with shop, JSONB for the long tail
  shop_id          uuid pk references shop(id) on delete cascade,
  general          jsonb not null default '{}',  -- tagline, support email/phone, hours
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
  cities          text[],                 -- empty = default zone
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
  is_active  boolean not null default true
);

discount (
  id                uuid pk,
  shop_id           uuid not null references shop(id) on delete cascade,
  code              citext,
  name              text not null,
  type              text not null,          -- PERCENT|FIXED|FREE_SHIPPING
  value             bigint not null,
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
  status            text not null default 'PENDING',
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

### 4.12 Table count

v1 had 25 tables. v2 has **18** — `category`, `shop_category`, and `product_media` are gone, replaced by `media`. Two admin CRUD modules (global categories, shop categories) disappear entirely and are replaced by editing Java enums.

### 4.13 Postgres notes

- `citext` extension for case-insensitive email/slug.
- Partial indexes for the hot admin/shop list queries.
- **Row-level security is deliberately off.** Tenant isolation is enforced in one place — a `TenantContext` filter — because debugging RLS under connection pooling is a tax you don't need yet.
- Flyway migrations are append-only, never edited after merge.
- **Local dev databases** (section 8.2): `store_app` for development, `store_app_test` for integration tests, same local server.

---

## 5. Backend Design

### 5.1 Modules

Modular monolith. Bounded contexts, each owning its tables exclusively — no cross-module table writes, ever. Cross-module communication is either a method call in one transaction (same module) or a RabbitMQ event (different module).

`identity` · `shop` · `catalog` · `inventory` · `order` · `payment` · `wallet` · `theme` · `customer` · `notification` · `admin` · `platform`

### 5.2 Multi-realm security (D3) — the trickiest infra piece

Because admin/shop and customer live in **different realms**, the resource server must accept two issuers and map them onto three app roles.

```java
@Configuration
@EnableMethodSecurity
class SecurityConfig {

  @Bean
  JwtDecoder jwtDecoder(KeycloakProperties props) {
    var ops       = NimbusJwtDecoder.withIssuerLocation(props.opsIssuer()).build();
    var customers = NimbusJwtDecoder.withIssuerLocation(props.customerIssuer()).build();
    var validator = DelegatingOAuth2TokenValidator<>(List.of(
        new JwtIssuerValidator(props.opsIssuer()),
        new JwtIssuerValidator(props.customerIssuer()),
        new JwtTimestampValidator()));
    ops.setJwtValidator(validator);
    customers.setJwtValidator(validator);
    return new MultiIssuerJwtDecoder(Map.of(
        props.opsIssuer(),      ops,
        props.customerIssuer(), customers));
  }
}
```

- `JwtAuthenticationConverter` maps `realm_access.roles` to `ROLE_ADMIN` / `ROLE_SHOP` from realm `ops`, and `ROLE_CUSTOMER` from realm `customers`. A role name appearing in the wrong realm is **rejected**, not silently accepted.
- **No realm issues a role belonging to the other realm.** Enforced by a startup self-test that logs in as a test user in each realm and asserts the mapped authorities.
- Shop ownership is **not** a Keycloak concern. `shop.owner_user_id` in Postgres is the authority. A `SHOP` token with no matching shop row gets 403, not 500.
- The resource server has **zero** auth endpoints — no `/login`, no `/register`, no token issuance. Registration happens through Keycloak's own registration flow, then a first-login sync creates the Postgres mirror row.
- Method security: `@PreAuthorize("hasRole('ADMIN')")`, `@PreAuthorize("@tenantGuard.isOwner(#shopId)")`.
- A `TenantContext` `ThreadLocal` filter resolves `shop_id` from the JWT subject via a short-TTL Redis cache, cleared in a `finally`. Every repository query goes through a `TenantAwareSpecification`, so a missing filter fails loudly in dev.

### 5.3 Category validation (D18)

One validator, called from the product service, the shop service, and the meta controller. This is the only place the (shopType, category) rule exists on the backend.

```java
@Component
public class CategoryValidator {

  /** Rejects a category that does not belong to the shop's type. */
  public void validate(ShopType shopType, ProductCategory category) {
    if (shopType == null || category == null) {
      throw new BadRequestException("INVALID_CATEGORY", "shop type and category are required");
    }
    if (!shopType.allows(category)) {
      throw new BadRequestException("INVALID_CATEGORY",
          "Category %s is not valid for shop type %s".formatted(category, shopType));
    }
  }

  /** Categories available to a shop of the given type. */
  public Set<ProductCategory> allowedFor(ShopType shopType) { ... }
}
```

Used by:
- `ProductService.create/update` — validate before persist.
- `MetaController` — `GET /api/v1/meta/shop-types`.
- `ShopService.update` — changing `shop_type` revalidates every existing product, and rejects the change if any product would become invalid. This is the one nasty edge case: a shop that switches `FASHION` -> `PHARMACY` while selling KURTA must be blocked, not silently orphaned.

### 5.4 Order state machine

```
                 ┌──────────────► CANCELLED ◄────────────┐
                 │  (no debit)   (reverses commission)   │
PENDING ──accept──► ACCEPTED ──► PROCESSING ──► SHIPPED ──► DELIVERED
   │                    │            │             │
   └──cancel────────────┴────────────┴─────────────┘
                          (Phase 8 adds RETURNED / REFUNDED)
```

- Transitions are the **only** way status changes. No `setStatus()` from a controller.
- Each transition validates the current status, writes `order_status_history`, adjusts inventory, fires a domain event.
- Illegal transition returns `409 CONFLICT` with the legal target states in the body.
- `ACCEPTED` additionally runs the **wallet guard** in the same transaction as the status write.

### 5.5 Money

- Storage: `bigint` paisa everywhere. A `Money` value object wraps `long amount` + `Currency`; no arithmetic on raw longs in service code.
- Commission: `rate_bp` basis points (`5% = 500`). Computed as `BigDecimal` internally, rounded `HALF_UP` to paisa, stored as `long`.
- Commissionable base: `(subtotal − discount)`. Shipping and tax are **excluded** — confirm before Phase 1 ships, it changes every number.
- Every money-writing endpoint accepts an `Idempotency-Key` header persisted in a unique index. Retries are free; double-charges are impossible.

### 5.6 Async (RabbitMQ)

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

Consumers are `@RabbitListener` with manual ack, prefetch 20, a container factory with DLQ + exponential backoff.

### 5.7 API conventions

- `/api/v1/**`, OpenAPI 3.1 via springdoc at `/api/v1/openapi.json`.
- Cursor pagination (`?cursor=&limit=`) for orders/products/ledger — offset pagination dies on large ledgers.
- Errors: RFC 7807 `ProblemDetail`, every error carrying a `traceId`.
- `Idempotency-Key` on all POSTs that move money or create orders.
- Optimistic locking (`@Version`) on `shop_order` and `product`.
- Redis: token->shop cache, rate limits, hot storefront reads, cursor encoding, meta endpoint cache.

### 5.8 API surface outline

**Public (no auth):** `GET /shops` (gallery, filters by `shopType` + `category`) · `GET /shops/{slug}` · `GET /shops/{slug}/products?category=` · `GET /p/{shopSlug}/{handle}` · `POST /payments/esewa/callback` · `GET /meta/shop-types[/{type}]`

**Customer:** `POST /orders/quote` · `POST /orders` · `GET /orders/me` · `POST /orders/{id}/cancel` · addresses CRUD · follow/unfollow · review submit.

**Shop:** `GET/PATCH /shop/profile` (including `shop_type` change, which revalidates products) · `POST /shop/submit-verification` · product/variant/inventory CRUD · `GET /shop/orders?status=` · `POST /shop/orders/{id}/transition` · `GET /shop/audience` · `GET /shop/wallet/ledger` · `POST /shop/wallet/topup` · `POST /shop/media/upload-signature` · `GET/POST/DELETE /shop/media/{id}` · `GET/PUT /shop/theme` · theme asset CRUD · `GET/PUT /shop/settings/*` · shipping zones · tax rules · discounts · payment methods · `GET /shop/invoices/{orderId}`

**Admin:** `GET /admin/kpis` · shops list + `verify|suspend|reject` · users list + suspend · plans + entitlements CRUD · wallet top-up `approve|reject` · `POST /admin/wallet/adjust` · theme template CRUD · featured shops · `GET /admin/reports/*` · `GET /admin/audit` · `GET/PUT /admin/settings`

---

## 6. Frontend Design

### 6.1 `apps/customer` — Next.js, App Router

| Route | Rendering | Purpose |
|---|---|---|
| `/` | SSR + ISR | Shop gallery, filter by shop type + category, search, featured |
| `/s/[shopSlug]` | SSR, dynamic | Storefront home from theme JSON |
| `/s/[shopSlug]/c/[productCategory]` | SSR + ISR | Category listing, using the enum handle as the slug |
| `/s/[shopSlug]/p/[handle]` | SSR | Product detail, variants, add to cart |
| `/s/[shopSlug]/cart` | client | Cart |
| `/s/[shopSlug]/checkout` | client | Address, shipping zone, payment method, quote |
| `/s/[shopSlug]/order/[id]` | SSR | Order confirmation + status timeline |
| `/s/[shopSlug]/policy/[slug]` | SSR | Policy pages from theme assets |
| `/account/*` | client + SSR shell | Orders, addresses, follows |

> **Route change in v2:** the category route is `/s/[shopSlug]/c/[productCategory]` where the handle is an enum value (e.g. `/c/MEDICINE`, slugged to `/c/medicine`). It is not a DB row, so it is validated against the shop type's category set at request time — an invalid or out-of-type category returns 404, not an empty page.

- Theme rendering is a **pure server component** — fetch theme config + section data, map to the registry, stream HTML. This is what makes the storefront indexable.
- `generateMetadata` per storefront/product for OG tags, so WhatsApp and Facebook previews render correctly.
- Cart: Zustand, persisted to `localStorage`, re-validated against the backend at checkout — prices are never trusted from the client.
- Auth: **not** `next-auth`. A thin Keycloak PKCE adapter in `packages/auth`, tokens in httpOnly cookies set by a route handler so SSR can read them.
- React Query for all mutations and client reads; server components use direct fetch with revalidation tags.

### 6.2 `apps/console` — Vite + React SPA

One bundle, role-split at the router. Realm `ops` users land on `/admin` or `/shop`; realm `customers` users are redirected out.

**Shop dashboard:** overview KPIs · orders board (status columns, bulk transitions) · products (list + editor, category dropdown filtered by shop type) · inventory · audience · wallet (ledger table + top-up form) · payments · discounts · shipping · tax · settings (multi-tab) · appearance/theme editor · media library · invoices · preview.

**Admin:** KPI dashboard · shops (verify/suspend) · users · plans and entitlements · wallet approvals · revenue and ledger · theme template publishing · featured shops · reports · audit log · platform settings · **data explorer**.

> **Removed from admin in v2:** global categories and featured-shop category management. Both are now enum edits in Java, done with a rebuild, not a UI.

**Theme editor** (the highest-value UI in the product): left = section list (drag to reorder, add/remove), center = live preview rendering the *same* section registry against the draft config, right = property panel for the selected section. Autosave to a draft, explicit publish, undo/redo stack in Zustand.

**Data explorer:** consumes `/api/v1/openapi.json`, builds a filterable/sortable/paginated table per `GET` endpoint, with a confirmation-gated mutation panel. Admin-only; every action written to `audit_log`.

### 6.3 Shared UI

shadcn/ui components vendored into `packages/ui` (not an npm dep — you own the source). Theme tokens exposed as CSS custom properties so the shadcn theme and the storefront theme JSON resolve through the same variable names. Tailwind v4 with a shared preset.

---

## 7. Phased Delivery

Each phase ends with something demonstrable in a browser on localhost. No phase starts before the previous one passes.

---

### Phase 0 — Foundations (~1 week)

**Goal:** skeleton that builds, runs on localhost, and authenticates against your real Keycloak.

**Step 0 — inspect before you create (do this first).**
Admin API access is needed to list clients and roles. Obtain an admin token and read the current
state of both realms so nothing existing is overwritten:

```bash
# admin token (use a dedicated admin account, not your personal one)
TOKEN=$(curl -s -d "client_id=admin-cli" \
  -d "username=$KC_ADMIN_USER" -d "password=$KC_ADMIN_PASS" -d "grant_type=password" \
  https://auth-master.yenyasoft.com/realms/master/protocol/openid-connect/token \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")

# what already exists?
curl -s -H "Authorization: Bearer $TOKEN" \
  https://auth-master.yenyasoft.com/admin/realms/ops/clients | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN" \
  https://auth-master.yenyasoft.com/admin/realms/ops/roles | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN" \
  https://auth-master.yenyasoft.com/admin/realms/customers/clients | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN" \
  https://auth-master.yenyasoft.com/admin/realms/customers/roles | python3 -m json.tool
```

Write down what exists, then create only what is missing per Appendix B. **Do not recreate a
client that already exists** — reuse its clientId instead of the proposed name.

**Step 1 — API skeleton (modify the existing project, do not regenerate).**
`~/Desktop/store-app/store/` already exists. Do **not** generate a new project.

1. Edit `pom.xml`: parent `4.1.1` -> **`3.5.16`**, add the dependencies in section 3.1.
2. **Delete** `src/main/resources/application.properties` (the Initializr default) — this plan
   uses `application.yml`, and a stray `.properties` file silently wins over your YAML.
3. Create the two databases:
   ```bash
   createdb -h localhost -U postgres store_app
   createdb -h localhost -U postgres store_app_test
   ```
4. Create `src/main/resources/application.yml` from Appendix A.
5. First Flyway migration `V1__baseline.sql`:
   ```sql
   CREATE EXTENSION IF NOT EXISTS citext;
   ```
6. Verify the downgrade actually took:
   ```bash
   cd ~/Desktop/store-app/store
   ./mvnw -q help:evaluate -Dexpression=spring-boot.version -DforceStdout   # 3.5.16
   ./mvnw clean install                                                   # BUILD SUCCESS
   ./mvnw spring-boot:run                                                 # :8080
   curl http://localhost:8080/actuator/health
   ```
   If springdoc fails to resolve, the version is wrong — it must be **2.x**, never 3.x.

   **Confirm the JDBC URL points at `store_app`, not `storefront`** (R13). The `storefront`
   database holds unrelated project data and must not be touched.

**Step 2 — multi-issuer JWT against the live realms.**
Wire `MultiIssuerJwtDecoder` with the two verified issuers. Test by minting a token from each
realm via `Resource Owner Password` (test users only) and asserting:
- an `ops` token grants `ROLE_SHOP`, a `customers` token grants `ROLE_CUSTOMER`
- a `customers`-realm token claiming an `ADMIN` role is **rejected**
- the startup self-test logs both realms' real role lists and asserts no cross-realm leakage

**Step 3 — web skeleton.**
Turborepo + pnpm workspaces, TS strict, Tailwind v4, ESLint boundary rule, `packages/ui` with
shadcn primitives, `packages/config`. `packages/auth` pointed at
`https://auth-master.yenyasoft.com` with PKCE `S256` (confirmed supported).

**Step 4 — Keycloak web origins.**
Add `http://localhost:3000` and `http://localhost:5173` to both public clients' **Web origins**
field. The instance is remote and TLS-terminated, so CORS is enforced strictly — this step is
the difference between login working and an opaque browser CORS error. Verify with a real login
from `http://localhost:5173` before moving on.

**Step 5 — meta endpoint.**
`GET /api/v1/meta/shop-types` returning the full `ShopType` + `ProductCategory` tree, plus a
unit test asserting every category belongs to exactly one shop type. This validates the enum
design before anything depends on it.

**Exit:** `./mvnw spring-boot:run` serves the meta endpoint and a protected endpoint on :8080;
`pnpm --filter customer dev` serves a page on :3000; `pnpm --filter console dev` serves a page
on :5173; a user can log in through the real Keycloak from :5173 and their token authenticates
against :8080 with the correct role; the `storefront` database is provably untouched.

### Phase 1 — Vertical Slice: Shop -> Product -> Order (~3 weeks)

**Goal:** one complete money path, end to end, no shortcuts. This de-risks the entire project.

1. **Identity sync** — first-login profile sync from both realms into `app_user`; the `SHOP` role provisions a `shop` row in `DRAFT`.
2. **Shop signup with type selection** — basic details, **shop type picker driven by the meta endpoint**, slug generation with collision retry. Type is locked after creation (section 5.3).
3. **Admin verification** — shop submits documents, admin approves/rejects with a note. The gate that keeps fake shops out.
4. **Product, minimal** — one product, category chosen from the type's allowed set, up to N variants, Cloudinary upload to `shop/{shopId}/products/{productId}/`, price in minor units, activate.
5. **Storefront, minimal** — one hardcoded default theme, SSR, list + detail, add to cart.
6. **Checkout** — guest email, address, shipping zone rate, tax rule, discount code, server-side quote, order creation with the category snapshot written.
7. **Payment** — `PaymentGateway` interface; `CodGateway` + `BankTransferGateway` live; `EsewaGateway` stubbed behind the same interface.
8. **Wallet + commission** — top-up request, admin approval, ledger entries, **5% debit on accept**, **reversal on cancel**, insufficient-balance guard.
9. **Order lifecycle** — PENDING -> ACCEPTED -> PROCESSING -> SHIPPED -> DELIVERED, plus CANCELLED, with history and inventory movement.
10. **Notifications** — outbox + RabbitMQ + email worker. Push deferred to Phase 5.
11. **Follower count** — the counter and the increment path (D13, no UI beyond a number).

**Exit:** a verified pharmacy shop publishes a product with category `MEDICINE`, a customer orders and pays by COD, the shop's wallet drops by exactly 5%, cancellation puts it back, the ledger reconciles against the balance cache, and attempting `KURTA` on that shop is rejected with `400 INVALID_CATEGORY`.

**This is where the plan's risk lives.** If the wallet math or the state machine is wrong here, everything downstream inherits it.

---

### Phase 2 — Catalog and Inventory Depth (~2 weeks)

Product editor (images via the media table, variants matrix, bulk edit) · product listing filtered and grouped by `ProductCategory` · inventory with movements, low-stock alerts, CSV import/export · product search with Postgres FTS + trigram typo tolerance · SEO fields · product duplication · archive/restore · drafts.

> **Removed from v1:** drag-order category management and sub-category creation. The category grid comes from the enum.

**Exit:** a shop manages a 500-SKU catalog across its type's categories, including variants and stock adjustments, with every quantity change traceable.

---

### Phase 3 — Theme Engine v1 (~3 weeks)

Section registry (~15 core sections first) · zod theme schema, versioned · `theme_template` marketplace with `shop_type` tagging and admin CRUD · shop theme editor with live preview · token system mapping to shadcn CSS vars · hero/header-page/banner/footer assets · default templates per shop type.

**Exit:** an admin publishes a pharmacy template, a pharmacy shop installs it, customizes sections and tokens, publishes, and the SSR storefront renders it — visually identical to the editor preview.

---

### Phase 4 — Shop Operations (~2 weeks)

Orders board with filters, bulk status transitions, printable order view · **invoice generator** (PDF, configurable template) · discount system (percent/fixed/free-shipping, limits, expiry) · audience list with spend stats, CSV export, private notes · **media library** screen (all Cloudinary assets for the shop, grouped by usage) · full shop settings: general, address, social, checkout, notifications, order-number format, invoice template, email templates · payment method config incl. eSewa merchant ID/secret · low-stock notification.

**Exit:** a shop runs its entire day-to-day operation without admin intervention.

---

### Phase 5 — Customer Portal Completion (~2 weeks)

**eSewa gateway live** with server-to-server verification and callback (D11) · customer account: order history, tracking timeline, reorder · addresses CRUD · follow/unfollow shops · product reviews and ratings · search · wishlist · **gallery polish: browse by shop type and by product category** (the D17 payoff — a customer can find all pharmacies, or all medicine) · sorting, empty and error states · Firebase push for order status · Nepali localization scaffolding.

**Exit:** a customer can discover a shop by type, follow it, order via eSewa, track the order by push notification, and reorder in one click.

---

### Phase 6 — Subscriptions and Revenue (~2 weeks)

Plans CRUD · entitlements engine + `TenantGuard` enforcement on every gated backend operation · subscription request -> top-up -> admin approval -> activation -> expiry job · commission waiver for subscribed shops · admin revenue dashboard (MRR, commission, top-ups, ARPU) · shop subscription screen with upgrade/downgrade/cancel · feature-limit enforcement with clear upgrade prompts in the UI.

**Exit:** a shop upgrades, its commission drops to 0% and paid templates unlock, the ledger records the charge, and expiry restores the free tier correctly.

---

### Phase 7 — Admin Platform (~2 weeks)

KPI dashboard (GMV, orders, ARPU, commission, active shops, churn, **revenue split by shop type**) · shop management: verify, suspend, feature, search, **filter by type** · user management with cross-realm awareness · **generic data explorer** over the OpenAPI spec · reports (sales, products, shops, types, taxes, payouts) with CSV export · audit log viewer with diff view · platform settings incl. commission rate · support tooling (notification history, email log) · consolidated notifications across admin and shops.

**Exit:** every endpoint in the API is reachable from the admin UI, and every privileged action lands in the audit log.

---

### Phase 8 — Returns, Refunds and Advanced Selling (~2 weeks)

Return and refund workflow with commission reversal (D14) · partial refunds · social selling: shareable product links, **WhatsApp order button**, copy-to-clipboard shop link (your multi-channel requirement) · pickup-location orders · abandoned-cart reminders · scheduled/promotional campaigns · tax reports.

**Exit:** a full return-and-refund cycle reconciles the wallet correctly and the shop can share a product to WhatsApp with a working deep link.

---

### Phase 9 — Polish and Hardening (ongoing)

Paid custom CSS with a strict sanitizer (D15) · follower feed and rewards (D13) · custom domains · multi-warehouse stock · product comparison · recommendations · Nepali localization completion.

---

## 8. Testing Strategy

### 8.1 Layers

| Layer | Tool | Focus |
|---|---|---|
| Unit | JUnit 5 + AssertJ | **Category enum integrity** (every category belongs to exactly one type; no orphans; no duplicates), money math, commission calculation, discount rules, state-machine transitions, theme schema validation. **100% coverage on `wallet`, `order`, and the enum classes** — no exceptions. |
| Integration | Spring Boot Test against **your local Postgres** | Repository behaviour, tenant isolation, multi-realm JWT validation, outbox relay, ledger reconciliation, category validation end to end |
| RabbitMQ | Local instance + `spring-rabbit-test` | Consumer retry, DLQ, idempotent consumption |
| Contract | springdoc schema -> generated client types | `packages/api-client` types regenerated locally; drift is visible in the diff |
| E2E | Playwright | Shop signup -> pick type -> publish -> order -> pay -> accept -> ship, run against localhost |
| Architecture | ArchUnit | Resource server exposes **no** auth endpoints; `packages/*` never imports `apps/*`; no entity leaks past its module boundary; **no JPA entity outside its owning module's package** |

### 8.2 Test database

Since Postgres is already running, integration tests use a **separate database on the same server**:

```yaml
# src/test/resources/application-test.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/store_app_test
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate      # Flyway owns the schema, always
  flyway:
    enabled: true
    clean-disabled: false     # only allow clean in the test profile
  rabbitmq:
    host: localhost
    port: 5672
```

A `@Sql`-free approach: let Flyway migrate `store_app_test` on boot, wrap each test class in `@Transactional` for rollback. One deliberate exception: tests for the wallet ledger and the outbox relay must commit, because those paths use `REQUIRES_NEW` and async publication — those truncate the affected tables in `@AfterEach` instead.

> **Why not Testcontainers here:** you already run Postgres and RabbitMQ locally, so a container would be a redundant second instance. The trade-off is that integration tests hit your real dev server — hence a separate `store_app_test` database and never `store_app`.

### 8.3 Manual test flow

Because the plan is scoped to "build, run on localhost, test the flow", each phase ships with a manual test script in `docs/manual-tests/phase-N.md` — exact click paths and expected values, including the wallet balance before and after each action. Phase 1's script is the important one; it is the money path.

---

## 9. Scope Boundaries

**In scope:** everything in section 7, running on localhost.

**Explicitly out of scope for this plan** (noted so it is not lost, to be handled when you deploy):

- Deployment: nginx, systemd, environment profiles, secrets management, blue-green
- CI/CD and GitHub Actions
- Managed Postgres, backups, restore drills
- Observability: OpenTelemetry, Grafana, structured logging, error tracking
- Kubernetes, containerization

These were removed at your request. The only forward-looking note: keep config in `application.yml` with an env-var override (`${DB_URL:jdbc:postgresql://localhost:5432/store_app}`) so a future deploy is a config change, not a code change.

---

## 10. Risks and Mitigations

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | **Commission math is wrong or double-debits** | High — real money, trust, legal exposure | Ledger-only balance, unique `idempotency_key`, rate snapshotted at accept, 100% unit coverage on wallet, scheduled reconciliation alerting on drift |
| R2 | **Two-realm JWT handling is subtly wrong** | High — auth holes or total lockout | Explicit `MultiIssuerJwtDecoder`, per-realm role mapping tested, cross-realm role rejected, startup self-test, security integration tests |
| R3 | **Theme JSON is a foot-gun** — a shop publishes a broken storefront | Medium | zod validation on write *and* render, `schemaVersion` + migrations, unknown section -> visible placeholder, always-one fallback template |
| R4 | **Tenant isolation leaks shop A's data to shop B** | Critical | Single `TenantContext` filter, `TenantAwareSpecification` on every query, an integration test that runs every shop-scoped repository as two different tenants |
| R5 | **Events lost between DB commit and RabbitMQ** | Medium — missing orders/notifications | Outbox pattern from Phase 1, not retrofitted |
| R6 | **Phase 3 theme engine is underestimated** | Medium — schedule slip | Registry is closed and finite; ship 15 sections, not 25; no custom code execution ever |
| R7 | **eSewa sandbox/production friction** | Medium | `PaymentGateway` interface; COD and bank transfer work independently, so the platform is never blocked on eSewa |
| R8 | **Shops under-fund the wallet and cannot accept orders** | Medium — support load | Pre-accept warning, low-balance alerts, admin top-up queue, an admin "accept anyway" override that writes an audit entry |
| R9 | **Hardcoded categories become a limitation** — a shop wants a category you did not anticipate | Medium — needs a code change + redeploy | The enum is the deliberate simplification you asked for. Mitigate by shipping a generous starting set (19 types, ~110 categories) and treating additions as small additive commits. A per-shop custom category table can be added later without touching this design, since `product_category` is a `varchar`. |
| R10 | **A shop changes `shop_type` and orphans existing products** | Medium | The type change endpoint revalidates every product and rejects the change if any would become invalid (section 5.3) |
| R11 | **Custom CSS XSS / layout destruction** (D15) | Medium | Deferred to Phase 9 behind a strict sanitizer and a preview gate |
| R12 | **Remote Keycloak CORS blocks localhost login** — the instance is TLS-terminated and does not know about `localhost:5173` | Medium — blocks all login, with a confusing browser error | Phase 0 **step 4** adds both localhost origins to both public clients and verifies a real login before any other work. Fail fast, not at Phase 1. |
| R13 | **`storefront` database gets overwritten** by a careless `drop` or a wrong JDBC URL | High — destroys unrelated project data | New `store_app` database only. `storefront` named in Appendix D as untouchable. Verify the JDBC URL in Phase 0 exit criteria. |

---

## 11. Open Questions

These do not block Phase 0 or Phase 1, but each needs an answer before the phase that depends on it.

1. **Commissionable base** — the plan assumes `(subtotal − discount)`, excluding shipping and tax. Confirm.
2. **Guest checkout** — allowed by default, or account required? Affects the `customer` upsert path.
3. **eSewa credentials** — do you already have a merchant account, and is there a sandbox? Blocks Phase 5.
4. **Plan pricing** — actual NPR prices and durations for the Pro tiers.
5. **Commission rate** — fixed 5%, or configurable per shop type from Phase 1? (Per-type is now easy since types are enums.)
6. **Shop review** — one-shot approval, or can a shop re-submit after rejection?
7. **Refunds** — who pays the refund, the shop or the platform, and does commission reverse on a *partial* refund? Blocks Phase 8.
9. **Is `shop_type` changeable at all?** The plan blocks it once products exist. If you want a self-serve flow, that is a different (harder) design.
10. **Category set completeness** — the starting list in section 4.1 is my proposal. Review it and add anything your real shops need before Phase 2.

---

## Appendix A — Environment Variables

All local defaults, overridable by env var (see the note in section 9).

**API (`api/src/main/resources/application.yml`)** — real values, verified against your running environment:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/store_app}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

store:
  keycloak:
    # VERIFIED: both realms return a valid OpenID configuration document
    base-url: https://auth-master.yenyasoft.com
    ops-issuer: https://auth-master.yenyasoft.com/realms/ops
    customer-issuer: https://auth-master.yenyasoft.com/realms/customers
    ops-client-id: ${KEYCLOAK_OPS_CLIENT_ID:store-app-console}
    customer-client-id: ${KEYCLOAK_CUSTOMER_CLIENT_ID:store-app-customer-web}
  commission:
    rate-bp: ${PLATFORM_COMMISSION_RATE_BP:500}   # 500 = 5%
  cloudinary:
    cloud-name: ${CLOUDINARY_CLOUD_NAME:}
    api-key: ${CLOUDINARY_API_KEY:}
    api-secret: ${CLOUDINARY_API_SECRET:}
  esewa:
    merchant-id: ${ESEWA_MERCHANT_ID:}
    secret: ${ESEWA_SECRET:}
  firebase:
    project-id: ${FIREBASE_PROJECT_ID:}
    credentials: ${FIREBASE_CREDENTIALS:}
  mail:
    host: ${SMTP_HOST:localhost}
    port: ${SMTP_PORT:1025}
```

Derived issuer URLs (copy these directly; both confirmed working):

```
ops:       https://auth-master.yenyasoft.com/realms/ops
customers: https://auth-master.yenyasoft.com/realms/customers
```

**`web/apps/customer/.env.local`:**
```
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_KEYCLOAK_URL=https://auth-master.yenyasoft.com
NEXT_PUBLIC_OPS_REALM=ops
NEXT_PUBLIC_CUSTOMER_REALM=customers
NEXT_PUBLIC_FIREBASE_API_KEY=<FILL IN>
```

**`web/apps/console/.env`:**
```
VITE_API_URL=http://localhost:8080
VITE_KEYCLOAK_URL=https://auth-master.yenyasoft.com
VITE_OPS_REALM=ops
VITE_FIREBASE_API_KEY=<FILL IN>
```

## Appendix B — Keycloak Setup Checklist

**Instance verified live.** `https://auth-master.yenyasoft.com` is reachable, and both realms
return a valid OpenID configuration document. Confirmed capabilities on both realms:

| Capability | Value | Consequence |
|---|---|---|
| Code challenge methods | `S256`, `plain` | **Use `S256`.** PKCE works as planned. |
| Grant types | authorization_code, refresh_token, password, client_credentials, device_code, CIBA | authorization_code + refresh_token available. **Do not use the `password` grant** in the app. |
| Signing algs | RS256, ES256, EdDSA, HS256, PS256, ... | Spring will pick `RS256` automatically. |
| Scopes | openid, profile, email, phone, address, roles, web-origins | `roles` scope is available but unused — authorization lives in Postgres (D4). |
| Token auth | client_secret_basic, client_secret_post, private_key_jwt | Standard confidential-client flow for the API. |

Clients and roles themselves are **not yet verified** — that needs admin API access. Phase 0
step 1 inspects the realms before creating anything, so nothing existing gets overwritten.

**Targets:**

- Realm `ops`: roles `ADMIN`, `SHOP`; client `store-app-console` (public, PKCE S256); client `api` (bearer-only).
- Realm `customers`: role `CUSTOMER`; client `store-app-customer-web` (public, PKCE S256); client `api-customer` (bearer-only).
- Separate bearer-only clients let you revoke tokens per realm independently.
- Registration: self-signup enabled in `ops` **only for the SHOP** role; `customers` realm open registration with email verification.
- Realm `ops` must **not** contain a `CUSTOMER` role, and vice versa — verified by the Phase 0 self-test.
- Web origins for both public clients: `http://localhost:3000` and `http://localhost:5173`.
- Token lifetimes: access 5 min, refresh 30 days. No cross-realm refresh issues, since they are separate issuers.

## Appendix C — Local Run Checklist

```bash
# 1. infrastructure (already running, verify only)
docker ps
docker exec master-database psql -U postgres -c "select version();"
docker exec rabbitmq-server rabbitmqctl status | head -3
docker exec redis_server redis-cli ping            # -> PONG

# 2. databases -- create BOTH, never touch 'storefront'
createdb -h localhost -U postgres store_app
createdb -h localhost -U postgres store_app_test

# 3. keycloak (remote, already live)
curl -s https://auth-master.yenyasoft.com/realms/ops/.well-known/openid-configuration | python3 -m json.tool | head
curl -s https://auth-master.yenyasoft.com/realms/customers/.well-known/openid-configuration | python3 -m json.tool | head
#    admin console: https://auth-master.yenyasoft.com/admin/master/console/

# 4. api
cd api
cd store && ./mvnw clean install
./mvnw spring-boot:run            # -> http://localhost:8080
#   verify: curl http://localhost:8080/actuator/health
#   verify: curl http://localhost:8080/api/v1/meta/shop-types

# 5. web
cd web
pnpm install
pnpm --filter customer dev   # -> http://localhost:3000
pnpm --filter console dev    # -> http://localhost:5173

# 6. rabbitmq management
open http://localhost:15672   # guest/guest
```

## Appendix D — Verified Environment (checked 2026-10-01)

Everything below was confirmed by inspecting your running machine, not assumed.

### Docker containers

| Container | Image | Status | Ports | Version |
|---|---|---|---|---|
| `master-database` | `postgres:16` | Up | 5432 | **PostgreSQL 16.14** (Debian) |
| `rabbitmq-server` | `rabbitmq:3.13-management` | Up | 5672, 15672 | **RabbitMQ 3.13.7**, Erlang/OTP 26, management plugin on 15672 |
| `redis_server` | `redis` | Up | 6379 | **Redis 8.10.0**, responding to `PING` with `PONG` |

### Keycloak

| Item | Value |
|---|---|
| Base URL | `https://auth-master.yenyasoft.com` |
| Admin console | `https://auth-master.yenyasoft.com/admin/master/console/` |
| `ops` realm | `https://auth-master.yenyasoft.com/realms/ops` — **verified live** |
| `customers` realm | `https://auth-master.yenyasoft.com/realms/customers` — **verified live** |

Both realms returned a valid `/.well-known/openid-configuration` document. Capability table in
Appendix B. This is a **remote, TLS-terminated, production-style host**, not localhost — two
consequences the plan now accounts for:

1. **CORS is strict.** The Keycloak web origin must explicitly allow `http://localhost:3000`
   and `http://localhost:5173`. Without this, the browser blocks the token request and login
   fails with an opaque CORS error rather than a clear message.
2. **Token lifetimes are whatever the realm has set** (commonly 5 min access, 30 days refresh).
   The refresh timer in `packages/auth` must handle a silent failure and redirect to login
   rather than looping.

### Existing databases on `master-database`

| Database | Status |
|---|---|
| `storefront` | **17 tables, live data** (6 users, 5 orders, 3 products, 5 categories, 3 media assets) |
| `cvgen`, `doko`, `hiti_tech`, `meropasal`, `msoftcrm`, `npm`, `portfolio`, `sem7` | other projects |

> **Decision: `storefront` is left completely untouched.** This project uses a **new** `store_app`
> database. Do not rename, migrate, drop, or `TRUNCATE` anything in `storefront`.
>
> For the record, the existing `storefront` schema does **not** match this plan and could not be
> reused without rework: `bigint` identity PKs instead of `uuid`, a `deleted` boolean instead of
> soft-delete timestamps, `timestamp without time zone` instead of `timestamptz`, no wallet or
> ledger tables, no commission tracking, and a `category` table that D17/D18 replace with enums.
> It also has a `CHECK` constraint hardcoding all 75 Nepal districts on `shop.district` — a
> useful idea worth borrowing for `shipping_zone`, but not a schema to build on.

### Databases to create

```bash
createdb -h localhost -U postgres store_app        # development
createdb -h localhost -U postgres store_app_test   # integration tests (section 8.2)
```

### Port map for local development

| Port | Service |
|---|---|
| 3000 | Next.js customer portal |
| 5173 | Vite console (shop + admin) |
| 8080 | Spring Boot API |
| 5432 | PostgreSQL |
| 5672 | RabbitMQ AMQP |
| 15672 | RabbitMQ management UI (http://localhost:15672) |
| 6379 | Redis |

### Not yet verified

- **Keycloak clients and realm roles.** Inspecting them requires an admin API token. Phase 0
  step 1 does this before creating anything, so existing config is never overwritten.
- **Cloudinary, eSewa, Firebase, SMTP** credentials. Still `<FILL IN>`; each blocks only the
  phase that uses it (Cloudinary in Phase 1, eSewa in Phase 5).

---

## Appendix E — Phase 0 Step 1: COMPLETED

Executed 2026-10-01. All exit criteria met.

### Verified result

| Check | Result |
|---|---|
| `spring-boot-starter-parent` | **3.5.16** (downgraded from 4.1.1) |
| Spring Framework | 6.2.19 |
| Tomcat | 10.1.55 |
| springdoc-openapi | **2.9.1** (2.x line — required) |
| Java | 21.0.5 Corretto LTS |
| `./mvnw clean install` | **BUILD SUCCESS**, 2/2 tests green |
| Boot time | 5.2 s |
| Fat JAR | 77 MB |
| `/actuator/health` | **UP** — db, rabbit, redis, diskSpace, ping, ssl all UP |
| Connected DB | `jdbc:postgresql://localhost:5432/store_app` |
| Flyway | v1 baseline applied to both `store_app` and `store_app_test` |
| `storefront` | **untouched** — 17 tables, 6 users, 5 orders |

### Files changed

| File | Action |
|---|---|
| `pom.xml` | parent 4.1.1 -> 3.5.16; 17 dependencies added |
| `src/main/resources/application.properties` | **DELETED** (would have silently overridden the YAML) |
| `src/main/resources/application.yml` | created — full config incl. verified Keycloak issuers |
| `src/main/resources/application-local.yml` | created — git-ignored real credentials |
| `src/main/resources/db/migration/V1__baseline.sql` | created — `CREATE EXTENSION citext` |
| `src/test/resources/application-test.yml` | created — points at `store_app_test` |
| `src/test/java/com/storefront/store/StoreApplicationTests.java` | rewritten — 2 tests incl. a `storefront` guard |
| `.gitignore` | added `application-local.yml`, `.env`, `.env.local` |

### Four bugs found and fixed during this step

1. **`guest` refused by RabbitMQ** (`ACCESS_REFUSED ... mechanism PLAIN`). The `rabbitmq:3.13`
   image restricts the built-in `guest` to loopback. The container's actual admin user is
   **`monu`**. Health went DOWN until this was corrected. Recorded as D24.
2. **`application-local.yml` was silently ignored.** Profile-specific files require an active
   profile; naming it `-local` is not enough. Fixed with `spring.config.import` in
   `application.yml` (D23).
3. **Test profile bypassed the credentials.** `application-test.yml` did not inherit
   `application.yml`'s import, so the datasource had no password. Fixed by repeating the
   import in the test file.
4. **Declared-empty placeholder beat the imported value.** Setting
   `password: ${DB_PASSWORD:}` in `application-test.yml` resolved to an empty string, and
   because a key in the importing file **overrides** the imported file, it clobbered the real
   password -> `SCRAM-based authentication, but no password was provided`. Fix: **omit the key
   entirely** so the imported value applies. This is a subtle Spring config trap worth
   remembering for every future profile file.

### Note on the openapi.json 401

`/api/v1/openapi.json` currently returns **401** with
`WWW-Authenticate: Basic realm="Realm"`. This is **expected and correct** for Step 1:
`spring-boot-starter-oauth2-resource-server` auto-secures every endpoint with HTTP Basic until
a `SecurityConfig` bean exists. Step 2 replaces it with the multi-issuer JWT config and
explicitly permits the docs paths, so the contract becomes readable.

### Remaining for Phase 0

- **Step 0** — inspect Keycloak realms with admin credentials, create any missing clients/roles
- **Step 2** — `MultiIssuerJwtDecoder` for both realms + startup realm self-test
- **Step 3** — Turborepo `web` workspace
- **Step 4** — add `http://localhost:3000` and `:5173` as Keycloak web origins (R12)
- **Step 5** — `GET /api/v1/meta/shop-types` + enum integrity test

---

## Appendix F — Phase 0 Steps 0 & 2: COMPLETED

Executed 2026-10-01.

### Keycloak baseline (both realms were pristine)

Before writing anything, both realms contained only Keycloak's default clients
(`account`, `account-console`, `admin-cli`, `broker`, `realm-management`,
`security-admin-console`) and **no custom roles**. Nothing existing was overwritten.

### Created

| Realm | Resource | Detail |
|---|---|---|
| `ops` | role `ADMIN` | granted to `admin.test` |
| `ops` | role `SHOP` | granted to `ops.test` |
| `ops` | client `store-app-console` | public, PKCE **S256**, origins `:5173` + `:3000` |
| `ops` | client `store-app-api` | bearer-only |
| `customers` | role `CUSTOMER` | granted to `cust.test` |
| `customers` | client `store-app-customer-web` | public, PKCE **S256**, origins `:3000` + `:5173` |
| `customers` | client `store-app-api-customer` | bearer-only |

Self-registration enabled on both realms with email verification.
`sslRequired: external`, access token 300 s, sso idle 1800 s (both realms unchanged).

### Scripts (idempotent, re-runnable)

| Script | Purpose |
|---|---|
| `scripts/keycloak-setup.sh` | Creates roles + clients. Reads before writing, so a re-run never clobbers. |
| `scripts/keycloak-seed-test-users.sh` | Seeds the 3 test users, grants realm roles, enables the direct-access grant for tests. |

### Security code

| Class | Role |
|---|---|
| `KeycloakProperties` | Typed config for both issuers + client ids. |
| `MultiIssuerJwtDecoder` | Routes each token to a decoder by `iss`. Untrusted issuer is rejected **without** being tried against any decoder. |
| `KeycloakJwtAuthenticationConverter` | Maps realm roles → authorities through a hard-coded per-realm table. A role a realm may not grant is logged and **dropped**. |
| `SecurityConfig` | Stateless resource server. No login/register/refresh endpoint. Permits actuator, openapi, and read-only public paths; everything else requires a valid token. CORS limited to the two localhost origins. |

### Verified end to end against the live instance

Real tokens minted from the real realms, presented to a running app:

| Token | Issuer | Realm roles in JWT | Authorities mapped by the app |
|---|---|---|---|
| `ops.test` | `.../realms/ops` | SHOP | `ROLE_SHOP, ROLE_AUTHENTICATED, REALM_OPS` |
| `admin.test` | `.../realms/ops` | ADMIN | `ROLE_ADMIN, ROLE_AUTHENTICATED, REALM_OPS` |
| `cust.test` | `.../realms/customers` | CUSTOMER | `ROLE_CUSTOMER, ROLE_AUTHENTICATED, REALM_CUSTOMERS` |

`default-roles-*`, `offline_access` and `uma_authorization` were present in every token and
correctly discarded. The customer token received **no** `ROLE_ADMIN` or `ROLE_SHOP`.

### Tests: 17/17 green

| Suite | Count | Needs network |
|---|---|---|
| `StoreApplicationTests` (context + `storefront` guard) | 2 | no |
| `MultiIssuerJwtDecoderTest$IssuerRouting` | 7 | 2 of them, gated |
| `MultiIssuerJwtDecoderTest$RoleMapping` | 8 | no |
| `KeycloakLiveRealmIT` | 4 | yes, gated |

Run everything including the live checks:

```bash
KC_INTEGRATION_TEST=true \
KC_OPS_USER=ops.test KC_OPS_PASS=iloveu KC_OPS_ROLE=SHOP \
KC_CUST_USER=cust.test KC_CUST_PASS=iloveu \
./mvnw test
```

Without `KC_INTEGRATION_TEST` the network tests skip and the build still passes (15 run, 2 skip).

### A real security bug the live test caught

The first implementation gave **both** decoders the same validator:

```java
new DelegatingOAuth2TokenValidator<>(
    new JwtIssuerValidator(opsIssuer),        // fails for a customers token
    new JwtIssuerValidator(customerIssuer),
    new JwtTimestampValidator())
```

`DelegatingOAuth2TokenValidator` returns the **first** error, so a `customers` token presented
to the `ops` decoder was rejected — safe, but only by accident. The real hazard is the
converse: had the validator been written to accept either issuer, **each decoder would have
accepted both realms**, which is precisely the cross-realm leak the design exists to prevent.

Fixed by binding exactly one `JwtIssuerValidator` per decoder, via `perRealmDecoders()`.

**The unit tests did not catch this.** They substitute stub decoders and never exercise the
real validator chain, so the wiring looked correct while being wrong. Only minting a real token
from the real realm exposed it. The fix is now pinned by two regression tests that reach past
the router to the individual decoders.

Three API mistakes were also fixed during this step, all caught by compiling rather than by
reasoning: `jwkSetUri()` is not a public builder method in Spring Security 6 (the JWK Set URI
is derived from the issuer); `CorsConfiguration` setters take `List`, not varargs; and
`SignedJWT` has no `getSerialize()`.

### Runtime verification

| Check | Result |
|---|---|
| Boot | 5.8 s, Spring Boot 3.5.16 / Spring 6.2.19 |
| `/actuator/health` | **UP** — db, rabbit, redis, diskSpace, ping, ssl |
| `/api/v1/openapi.json` | **200** (was 401 in Step 1) — the D5 contract is public |
| `/api/v1/shops` (protected) | **401** without a token, **404→authenticated** with one |
| Garbage bearer token | **401** |
| `storefront` database | **untouched** — 17 tables, 6 users, 5 orders |

A temporary `_authprobe` controller was used to print the mapped authorities from a live
request, then **deleted**. It must not reappear; the `/api/v1/meta/shop-types` endpoint in
Step 5 is the permanent replacement.

### Remaining for Phase 0

- **Step 3** — Turborepo `web` workspace (Next.js + Vite + 10 shared packages)
- **Step 5** — `GET /api/v1/meta/shop-types`, `ShopType` / `ProductCategory` enums + integrity test

Step 4 (Keycloak web origins) is **already satisfied** — `scripts/keycloak-setup.sh` set both
`http://localhost:3000` and `http://localhost:5173` on both public clients, closing risk R12.

---

## Appendix G — Phase 0 Steps 5 & 3: COMPLETED

Executed 2026-10-02. **Phase 0 is now complete.**

### Step 5 — category enums and the meta endpoint (D17 / D18)

| Item | Result |
|---|---|
| `ShopType` | 19 verticals, each declaring the `ProductCategory` values it permits |
| `ProductCategory` | 120 values across 19 groups, 133 type/category assignments |
| `CategoryValidator` | single enforcement point for the (shopType, category) rule |
| `GET /api/v1/meta/shop-types` | public, returns the full tree with `code`, `label`, `slug` |
| `GET /api/v1/meta/shop-types/{code}` | one type; unknown code returns **404 `UNKNOWN_SHOP_TYPE`** |
| Errors | RFC 7807 `ProblemDetail` with a machine-readable `code` |

`BABY_CARE` is the **only** category valid in more than one vertical (pharmacy and baby shop).
An earlier draft of this plan also listed `PERSONAL_CARE`; that was wrong — it is pharmacy-only.
A test now pins the bridging set with an exact-equality assertion. See Appendix H.

**2,708 tests** in `CategoryEnumIntegrityTest`, including the exhaustive cross-product of every
non-permitted (category, shopType) pair — 2,375 cases. A spot check would let a newly added
category ship untested.

### Step 3 — the web workspace

```
web/
├─ apps/
│  ├─ customer/    Next.js 15 App Router, SSR storefront (D1)
│  └─ console/     Vite + React SPA, shop + admin (D10)
└─ packages/       ui, theme-engine, api-client, auth, validation,
                   state, enums, icons, config  (9)
```

Both apps build, both typecheck, and both run against the live API:

| Service | Port | Status |
|---|---|---|
| API | 8080 | `/actuator/health` **UP** |
| Customer portal | 3000 | 200, **19 shop types in the raw server-rendered HTML** |
| Console | 5173 | 200 |

CORS preflight verified: `Access-Control-Allow-Origin: http://localhost:5173` with
`authorization` in the allowed headers. **R12 closed.**

The storefront route for a shop that does not exist renders a graceful "not available" page
rather than a 500, and the theme JSON already flows end to end — section placeholders render
from real config, awaiting the Phase 3 components.

### The boundary rule and why it is enforced twice

`packages/*` must never import `apps/*`. This is expressed in
`packages/config/eslint/base.js` via `no-restricted-imports`, and it matters: a reverse import
creates a cycle that Vite and Next resolve differently, so it fails in one app and not the
other. The rule is switched **off** for `apps/**`, which legitimately imports both.

### Eight mistakes found by building rather than assuming

Every one of these was caught by a real compile or run, not by review:

1. **`@store-app/config` was never a declared dependency.** pnpm only links what is declared, so
   `extends: "@store-app/config/tsconfig.base.json"` silently failed and every package fell back
   to `tsc` defaults — which surfaced as 200+ bogus "Private identifiers are only available when
   targeting ECMAScript 2015" errors from inside `node_modules`.
2. **`react-icons` names were wrong.** `LuCouch` and `LuHome` do not exist; they are `LuSofa` and
   `LuStore`. Verified against the installed package rather than guessed.
3. **`<redirect>` is not a JSX element** in react-router v7 — it is `<Navigate to=… replace />`.
4. **`Button` had no `asChild`.** Buttons that navigate were rendering `<a>` inside `<button>`,
   which is invalid HTML and breaks middle-click. Added via Radix `Slot`, the shadcn pattern.
5. **Only 2 of 10 packages had a `tsconfig.json`.** The rest failed `turbo typecheck` only when
   the whole workspace ran, not when the apps built.
6. **`@types/node` was needed per-package.** Because a package's source is compiled in the
   context of whichever app imports it, the error appeared in `state` even though
   `theme-engine` compiled cleanly. Moved to the shared base config and the workspace root.
7. **Two cross-package imports were undeclared** (`state -> theme-engine`, `validation -> enums`).
   Found by a scripted audit of every `@store-app/*` import against every `package.json`, not by
   chasing one error at a time.
8. **`z.record` was called with the message in the wrong position**, so the message was read as
   a schema. Fixed by putting the validation on the value schema, where it actually belongs.

### A lesson worth keeping

Mistake 7 is the one to remember. I had been fixing errors one at a time and each fix revealed
the next. A twenty-line script that cross-references every workspace import against every
declared dependency found both remaining problems at once, and confirmed the other eight packages
were clean. When a monorepo produces a chain of "cannot find module" errors, audit the
dependency graph rather than following the chain.

### Phase 0 completion checklist

| Step | Status |
|---|---|
| 0 — inspect and create Keycloak realms | **done** (Appendix F) |
| 1 — Maven project, config, databases, Flyway | **done** (Appendix E) |
| 2 — multi-issuer JWT, proven live | **done** (Appendix F) |
| 3 — Turborepo workspace | **done** (this appendix) |
| 4 — Keycloak web origins (R12) | **done**, closed by `keycloak-setup.sh` |
| 5 — category enums and meta endpoint | **done** (this appendix) |

**Totals: 2,725 backend tests green, 10/10 web packages typecheck, both apps build, three
services running on localhost, `storefront` untouched.**

### Next: Phase 1 — the vertical slice

Order matters here. The money path is the highest-risk part of the whole system (R1), so Phase 1
is: identity sync -> shop signup with type -> admin verification -> one product with variants ->
minimal SSR storefront -> checkout -> COD and bank transfer -> **wallet debit and commission
reversal** -> order lifecycle -> notifications via the outbox.

The commission and the insufficient-balance guard get the most attention and the most test
coverage. If the wallet math is wrong there, everything downstream inherits the bug.

---

## Appendix H — Self-serve shop type change (D29 / D30)

Executed 2026-10-02. A shop owner can now change their own shop type from general settings.

### The design problem

A shop type is not a free-text label. It constrains every product the shop sells, and the
storefront's category navigation is **derived from the shop type** rather than from a category
table. So changing type with products already in place risks stranding them: a `MEDICINE` product
in a shop that is now `FASHION` would list under a category the vertical does not offer, and
would silently disappear from the storefront's category grid.

### The rule

> A type change never strands a product. Either every category the shop already uses stays valid
> under the new type, or the change is refused with the exact categories to fix.

Rather than blocking every change and pushing the owner to a support ticket, the API answers
**which types can I move to right now?** and the picker shows only those. Blocked types are
still returned, each with the categories responsible, so the owner gets a to-do list instead of
a missing option.

### Endpoint

```
GET /api/v1/shop/type-migration/reachable?currentType=GROCERY&currentCategories=RICE&currentCategories=LENTIL
```

| Response field | Purpose |
|---|---|
| `currentType` | What the shop is now |
| `currentCategories` | The categories its products use, as labels |
| `reachable` | Type codes the shop may switch to |
| `blocked[]` | `code`, `label`, `blockingCategories[]`, `blockingCount` for each unavailable type |

Requires a shop token — this is tenant data, unlike the public `/meta` endpoints. A refused
change returns **409 `SHOP_TYPE_CHANGE_CONFLICT`** with `blockingCategories`, not 400: the
request is well-formed but conflicts with current data.

### Verified behaviour

| Shop | Products | Reachable |
|---|---|---|
| GROCERY | RICE, LENTIL, SPICES | *(none)* |
| PHARMACY | MEDICINE | *(none)* |
| PHARMACY | BABY_CARE | `BABY_SHOP` |
| PHARMACY | *(none)* | all 18 others |

The grocery result is the honest one: a shop selling only staples cannot become
`GROCERY_BEVERAGES`, because it does not sell tea, coffee or milk. The rule is working, not
being lenient.

### `BABY_CARE` is the only bridge — and my earlier claim was wrong

The plan previously stated that `BABY_CARE` **and** `PERSONAL_CARE` were shared between pharmacy
and baby shop. Only `BABY_CARE` is. `PERSONAL_CARE` is pharmacy-only, despite both being care
verticals.

This was masked by a weak assertion: the overlap test used `isSubsetOf`, so a shared set that
*shrank* still passed. It is now an exact-equality check, plus a second test asserting the
owners of both categories. This is the general lesson — a subset assertion documents a ceiling
but not a floor, and drift downwards is the direction that actually matters.

### Code

| Class | Role |
|---|---|
| `CategoryValidator.reachableTypes` | the reachability rule |
| `CategoryValidator.canChangeTo` | the guard used by the change endpoint |
| `CategoryValidator.ShopTypeChangeConflictException` | carries the blocking categories to the 409 |
| `ShopTypeMigrationController` | the endpoint |
| `ApiExceptionHandler` | maps the conflict to RFC 7807 with `blockingCategories` |

### Tests: 2,414 for the change rules alone

`ShopTypeChangeTest` (2,412) includes the exhaustive cross-product of every non-permitted
(category, target) pair, plus a structural check that **no type reported reachable would strand a
product** — the property that makes the word "reachable" trustworthy rather than merely plausible.
`ShopTypeMigrationControllerIT` (8) covers the HTTP surface, including that the endpoint is not
public.

**Total backend suite: 5,138 tests green.**

### Debugging note worth recording

One test file produced a javac error on a line and column that did not exist in the file, and
`javac`, `awk`, `grep` and `python` each reported a different line number for the same constant.
The construct compiled correctly in isolation, so the code was never wrong. Rather than keep
bisecting a phantom, I rewrote the file from scratch with consistent 4-space indentation (the
repo mixes tabs and spaces, and javac's column reporting for tab-indented files is misleading).
If a compiler error points at a location that does not contain what it claims, check the
indentation before suspecting the code.
