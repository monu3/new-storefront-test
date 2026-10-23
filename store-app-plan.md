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
| D14 | Returns/refunds | **Resolved, Phase 8.** The **shop's wallet pays** the refund and the platform **reverses commission** on the returned portion. Cancellation + commission reversal shipped early. |
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
| D31 | Commission base | `(subtotal - discount)`, **excluding shipping and tax**. Implemented in `CommissionCalculator`; plan open question #1 is now answered in code and awaits only the user's confirmation. |
| D32 | Balance arithmetic | All money is `bigint` paisa. `Money` is a record; `BigDecimal` appears only inside percentage maths and is immediately converted back. `distribute()` guarantees parts sum exactly to the total. |
| D33 | Lombok | **1.18.48**, `provided` scope, excluded from the fat jar. Used for accessors, builders, `@Slf4j`, `@RequiredArgsConstructor` — **not** for rules. |
| D34 | Commission base | **Confirmed by the user:** `(subtotal - discount)`, excluding shipping and tax. Plan open question #1 is closed. |
| D35 | Shop derivation | Shop-scoped endpoints resolve the shop from the token, never from a request parameter. A `shopId` in the body would make authorisation a per-endpoint decision. |
| D36 | Admin guarding | `@PreAuthorize` on the admin controller **class**, not per method, so a new endpoint is admin-only by default. |
| D37 | Integration tests | `*IT.java` runs in the normal `mvn test`. Surefire's default skips them, and a green build that ran almost nothing is worse than a failing one. |
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

### Phase 1 — Vertical Slice: Shop -> Product -> Order (~3 weeks) — ✅ COMPLETE

**Goal:** one complete money path, end to end, no shortcuts. This de-risks the entire project.

> **Status: complete.** All 11 items delivered. 5,524 tests green; the exit criterion below is
> verified live. See Appendix L (items 1–4) and Appendix M (items 5–11, decisions D43–D55).

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

### Phase 2 — Catalog and Inventory Depth (~2 weeks) — ✅ COMPLETE

Product editor (images via the media table, variants matrix, bulk edit) · product listing filtered and grouped by `ProductCategory` · inventory with movements, low-stock alerts, CSV import/export · product search with Postgres FTS + trigram typo tolerance · SEO fields · product duplication · archive/restore · drafts.

> **Removed from v1:** drag-order category management and sub-category creation. The category grid comes from the enum.

**Exit:** a shop manages a 500-SKU catalog across its type's categories, including variants and stock adjustments, with every quantity change traceable.

> **Status: complete.** Verified live against a 1,010-product catalogue; 5,604 tests green. See
> Appendix N (decisions D56–D66 and the eleven bugs the phase found).

---

### Phase 3 — Theme Engine v1 (~3 weeks) — ✅ COMPLETE

Section registry (~15 core sections first) · zod theme schema, versioned · `theme_template` marketplace with `shop_type` tagging and admin CRUD · shop theme editor with live preview · token system mapping to shadcn CSS vars · hero/header-page/banner/footer assets · default templates per shop type.

**Exit:** an admin publishes a pharmacy template, a pharmacy shop installs it, customizes sections and tokens, publishes, and the SSR storefront renders it — visually identical to the editor preview.

> **Status: complete.** 5,679 backend tests green; all 10 frontend packages typecheck and both apps
> build. Exit criterion verified live end to end. See Appendix O (decisions D67–D74, and the
> validator-drift bug that rendered a blank storefront).

---

### Phase 4 — Shop Operations (~2 weeks) — ✅ COMPLETE

Orders board with filters, bulk status transitions, printable order view · **invoice generator** (PDF, configurable template) · discount system (percent/fixed/free-shipping, limits, expiry) · audience list with spend stats, CSV export, private notes · **media library** screen (all Cloudinary assets for the shop, grouped by usage) · full shop settings: general, address, social, checkout, notifications, order-number format, invoice template, email templates · payment method config incl. eSewa merchant ID/secret · low-stock notification.

**Exit:** a shop runs its entire day-to-day operation without admin intervention.

> **Status: complete.** 5,770 backend tests green, 0 skipped; all 10 frontend packages typecheck and
> both apps build; `check-ui.mjs` reports ALL PAGES OK and the five screens were driven in a real
> browser. The exit criterion is met — a shop can open and close itself, number its own orders,
> discount, invoice, configure eSewa and bank transfer, see its audience and its low stock, and
> manage its media without an admin.
>
> Most of the Phase 1 schema already existed; what was missing was the Java. Six bugs were found
> and fixed along the way, two of them severe: `REQUIRES_NEW` bulk transitions that could not see
> the caller's writes, and a settings save that made a shop unable to take orders. See Appendix W.

---

### Phase 5 — Customer Portal Completion (~2 weeks) — COMPLETE

**eSewa gateway live** with server-to-server verification and callback (D11) · customer account: order history, tracking timeline, reorder · addresses CRUD · follow/unfollow shops · product reviews and ratings · search · wishlist · **gallery polish: browse by shop type and by product category** (the D17 payoff — a customer can find all pharmacies, or all medicine) · sorting, empty and error states · Firebase push for order status · Nepali localization scaffolding.

**Exit:** a customer can discover a shop by type, follow it, order via eSewa, track the order by push notification, and reorder in one click.

---

### Phase 6 — Subscriptions and Revenue (~2 weeks) — COMPLETE

Plans CRUD · entitlements engine + `TenantGuard` enforcement on every gated backend operation · subscription request -> top-up -> admin approval -> activation -> expiry job · commission waiver for subscribed shops · admin revenue dashboard (MRR, commission, top-ups, ARPU) · shop subscription screen with upgrade/downgrade/cancel · feature-limit enforcement with clear upgrade prompts in the UI.

**Exit:** a shop upgrades, its commission drops to 0% and paid templates unlock, the ledger records the charge, and expiry restores the free tier correctly.

> **Status: complete.** Every clause of the exit criterion is implemented and asserted — the
> commission waiver, the paid-template unlock, the ledger charge and the expiry sweep are four named
> tests in `SubscriptionLifecycleIT`.
>
> Five screens, on the existing shell and components: `/shop/billing`, `/admin/wallet`,
> `/admin/revenue`, `/admin/plans`, and the upgrade prompt on the product form. Verified by driving
> the whole D7 flow through a real browser — request, quote, pay, approve, and the numbers move.
>
> ```
> backend  5,905 tests green · frontend 10/10 typecheck, console builds
> check-phase6          5 pages, 0 console errors, 0 4xx/5xx
> check-phase6-journey  JOURNEY OK — MRR Rs. 0.00 → 2,000.00, commission 5% → none
> ```
>
> See Appendix AC (back end: the design decisions and the ten bugs the tests found) and Appendix AD
> (front end: four screens, and the fact that D7's flow was never finishable through the UI until the
> admin approval screen existed).

---

### Phase 7 — Admin Platform (~2 weeks) — COMPLETE

KPI dashboard (GMV, orders, ARPU, commission, active shops, churn, **revenue split by shop type**) · shop management: verify, suspend, feature, search, **filter by type** · user management with cross-realm awareness · **generic data explorer** over the OpenAPI spec · reports (sales, products, shops, types, taxes, payouts) with CSV export · audit log viewer with diff view · platform settings incl. commission rate · support tooling (notification history, email log) · consolidated notifications across admin and shops.

**Exit:** every endpoint in the API is reachable from the admin UI, and every privileged action lands in the audit log.

> **Status: complete.** The foundation (audit trail, platform settings) plus every admin
> feature: KPI dashboard, shop management with featuring and filtering, cross-realm user
> management, reports with CSV export, the data explorer, the audit log's admin screen with its
> diff view, support tooling, and the ten admin screens that make each one reachable.
>
> Every privileged action in the new surface lands in the audit log, and the explorer refuses to
> run anything but an admin `GET` — so "every endpoint is reachable from the admin UI" is true
> without the UI also being a second, quieter way to act.
>
> The ordering is deliberate and not incidental: auditing retrofit after the features exist is how you
> get an audit log that is complete for the features somebody remembered. See Appendix AE.

---

### Phase 8 — Returns, Refunds and Advanced Selling (~2 weeks) — IN PROGRESS (returns, social selling, pickup, abandoned carts and campaigns complete; tax reports partial)

Return and refund workflow with commission reversal (D14) · partial refunds · social selling: shareable product links, **WhatsApp order button**, copy-to-clipboard shop link (your multi-channel requirement) · pickup-location orders · abandoned-cart reminders · scheduled/promotional campaigns · tax reports.

**Exit:** a full return-and-refund cycle reconciles the wallet correctly and the shop can share a product to WhatsApp with a working deep link.

---

### Phase 8.5 — Registration, Onboarding and the Landing Page — COMPLETE

Until 18 Oct 2026 the platform had **two test accounts and no way to get a third**. A shop owner
existed only if an administrator made one by hand in the Keycloak console, and a customer likewise.
`POST /api/v1/shop/signup` had been written and nothing called it.

**Three decisions, taken 18 Oct 2026:**

1. **`ROLE_SHOP` is granted by a Keycloak default role on the existing `store-app-console` client**,
   not by a third client and not by the app calling the Admin API. Self-registration through that
   client therefore grants the role automatically.
2. **A dedicated landing page** at the storefront root, with the gallery moving to `/shops`.
3. **Two-step shop onboarding** — account first, then shop — landing in the dashboard rather than in
   a wizard.

#### The two things that are not alternatives

| | Means | Where it lives |
|---|---|---|
| `ROLE_SHOP` | this person may open the shop console | Keycloak, account-level, automatic |
| Shop verification | this shop may actually trade | **already built** — `PENDING_REVIEW` → `VERIFIED` |

An admin approving a shop in this console gives them a shop; it does not give them a Keycloak role.
Conflating the two is what made this look like it needed the Admin API.

#### Why the default-role route and not the Admin API

The Admin API would work with no Keycloak configuration at all, and it was rejected because it puts
Keycloak admin credentials in this application's config and turns role assignment into code that
can fail at signup — where the failure mode is a person who registered and cannot get in, with
nothing in the logs saying why. A Keycloak default role fails loudly and early instead: if it is not
set, the new owner gets a console that refuses every screen, and the fix is one setting.

The trade-off is accepted: **anyone who self-registers through the console client gets a shop
console.** That is not as open as it sounds, because their shop is `DRAFT` and cannot trade until an
administrator verifies it — which is the same gate every other shop goes through.

#### Work

- `V18` — `shop.profile_completed_at`, so "has this owner finished signing up" is a fact rather than
  a guess from the shop being called "My shop"
- console `/shop/start` — name, vertical, description; calls the existing `POST /api/v1/shop/signup`
- console `/shop` — an onboarding guard that sends an unfinished owner to `/shop/start`
- registration links on both login pages, pointing at Keycloak's registration endpoint
- storefront landing page at `/`, gallery to `/shops`

#### Keycloak configuration (done once, by hand)

On the **`ops`** realm: Clients → `store-app-console` → *Client Roles* → create `SHOP` if it is not
already there → *Default Client Roles* → add `SHOP`. On the **`customers`** realm: Clients →
`store-app-customer-web` → *Client Roles* → `CUSTOMER` → *Default Client Roles* → add `CUSTOMER`.

Without this, a person who registers can sign in and is refused every screen, because the token
carries no role the backend recognises.

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
7. ~~**Refunds** — who pays the refund, the shop or the platform, and does commission reverse on a *partial* refund?~~ **Answered 18 Oct 2026.** The shop's wallet pays, and commission reverses **proportionally** to the returned portion — so a 40%-value return credits back 40% of the commission charged. Keeps commission aligned with what actually sold; the alternative (reverse nothing until the whole order comes back) means the platform earns commission on items it just gave money back. See D14 and Appendix AG.
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

---

## Appendix I — Phase 1 in progress: the money foundation

**Status: 4 of 11 Phase 1 items done. The money path is proven; the flows around it are not
built yet.**

### Complete

| # | Item | State |
|---|---|---|
| — | **Schema** | V1–V4, **27 tables** in `store_app` |
| — | **`Money`** value object | 55 unit tests |
| — | **`CommissionCalculator`** | the D2 rules, tested |
| — | **Wallet ledger** | 24 integration tests against real Postgres |
| — | `ShopOrder` entity + `ShopTypeChange` | from the previous session |

**Backend suite: 5,193 tests green.** App boots in 8.6 s; health UP on all six indicators.

### The three wallet properties, proven

`WalletServiceIT` asserts, against a real database rather than mocks:

1. **A debit is impossible without funds.** `InsufficientWalletBalanceException`, and the
   balance does not move.
2. **The same key can never move money twice.** Accepted twice, charged once.
3. **The cache and the ledger never disagree.** Reconciliation detects injected drift and
   restores the ledger's value, because the ledger is authoritative.

Plus: a full fund → accept → cancel cycle returns the wallet to exactly where it started, and a
**raw insert bypassing the service is still rejected** by the unique constraint — the guarantee
lives in the schema, not only in the calling code.

### Nine bugs found by running it, not reading it

Every one of these was invisible to review and only appeared at runtime:

1. **`@UuidGenerator` alongside a constructor-assigned id.** Hibernate treated the row as a
   detached instance and issued UPDATEs, producing `ObjectOptimisticLockingFailureException`
   everywhere. Application-assigned ids need `@Id` alone.
2. **`@CreationTimestamp` on a NOT NULL column with no default.** The annotation populates the
   field *after* the INSERT is built, so it fails with "No value specified for parameter 12".
   Replaced with field initialisers, which exist before Hibernate builds the statement.
3. **`BaseEntity` on the append-only ledger.** Its `@PreUpdate` would rewrite `updated_at` on a
   row that must never change. `WalletLedgerEntry` no longer extends it, and declares
   `createdAt` directly — an append-only guarantee should be structural, not disciplinary.
4. **`citext` needs `columnDefinition`, not a `UserType`.** A hand-written `CitextType` worked in
   isolation but silently bound NULL through Hibernate, and `@Type` alone does not register the
   type. `columnDefinition = "citext"` is the whole solution; the custom type was deleted.
5. **`jsonb` needs `@JdbcTypeCode(SqlTypes.JSON)`.** `columnDefinition` alone leaves the value
   bound as VARCHAR, which Postgres rejects.
6. **`char(3)` vs `varchar(3)`** for `currency`. Hibernate maps String to varchar; `char` also
   pads with spaces, so `'NPR '` would not equal `'NPR'`. Migration V4 relaxes it.
7. **A test fixture that cached its owner across `@Transactional` tests.** Every test rolls
   back, so the cached entity's row no longer existed while its id still looked valid — the FK
   failed on a completely correct-looking id. Not cached now.
8. **Random UUIDs where real rows are required.** `wallet_ledger_entry.order_id` and
   `shop_order` both have genuine foreign keys. The schema was right; the tests were wrong.
9. **A derived query that Spring Data could not parse.** `findByStatus...(List, Pageable)` reads
   the List as a scalar equality. Rewritten as explicit JPQL.

### Two deliberate choices worth noting

**The balance is moved with an atomic `UPDATE balance = balance + delta`**, not a
read-modify-write on the entity. A `@Version`ed entity and a pessimistic lock interacted badly
under the flush ordering, and an atomic UPDATE cannot be lost to a stale in-memory copy. A
pessimistic row lock is still held for the affordability check, because that read must not be
stale — two concurrent accepts of *different* orders are not covered by any per-order
idempotency key.

**`commission_idempotency_key()` is derived from the order id** (`"commission:" + id`). That one
string is what makes double-charging impossible, and it means a retried accept cannot even
attempt a second debit.

### Not yet built

The remaining 7 Phase 1 items, in the order they depend on each other:

- identity sync (first-login profile creation, shop provisioning on the SHOP role)
- shop signup with type selection, and the admin verification queue
- products and variants, media upload to Cloudinary
- minimal SSR storefront, checkout and quote
- `PaymentGateway` with COD and bank transfer (eSewa stubbed behind the interface)
- order state machine, inventory reservation, order numbering
- outbox relay, RabbitMQ workers, email

### Open question #1 is now answered in code

`CommissionCalculator` implements `(subtotal - discount)`, excluding shipping and tax, as the
plan assumed. If that is not the intended base, the change is one method and its tests — but it
should be confirmed **before** the first real order, not after.

---

## Appendix J — Lombok adoption (D33)

Added at the user's request, version **1.18.48** (current; 1.18.30+ is the floor for Java 21).

### Configuration

| Setting | Value | Why |
|---|---|---|
| `<scope>` | `provided` | Compile-time only; must not reach runtime. |
| `spring-boot-maven-plugin` `<excludes>` | lombok | Verified: **0 lombok entries** in the fat jar. |
| `maven-compiler-plugin` `annotationProcessorPaths` | lombok 1.18.48 | With Java 21 the processor path is not inferred reliably, and a silent failure looks like "the annotation does nothing". |
| `-parameters` | on | Keeps constructor parameter names in the bytecode for Spring's argument resolution. |

### Where it is used, and where it deliberately is not

| Annotation | Applied to | Effect |
|---|---|---|
| `@Getter` / `@Setter` | `Shop`, `AppUser`, `ShopOrder`, `WalletLedgerEntry` | 31, 21, 35, 15 accessors generated |
| `@NoArgsConstructor(PROTECTED)` | all four entities | the JPA constructor, no longer hand-written |
| `@Builder` | `WalletLedgerEntry` | replaced a ~70-line hand-written builder class |
| `@Slf4j` | `WalletService` | removed the `LoggerFactory.getLogger` boilerplate |
| `@RequiredArgsConstructor` | `WalletService` | constructor injection without the boilerplate |

**107 accessors** now come from about ten lines of annotations.

### The rule this establishes

> Lombok removes boilerplate. It never removes a **rule**.

Three consequences, all enforced in code:

1. **`WalletLedgerEntry`'s constructor is hand-written** with its validation inside, and
   `@Builder` is pointed at that constructor. A fluent builder still cannot bypass the checks —
   a zero amount or a blank description fails at `build()`, exactly as before.

2. **`ShopOrder` uses `@Setter(AccessLevel.PACKAGE)` with `@Setter(AccessLevel.NONE)` on the
   state-bearing fields.** `status`, `paymentStatus`, the commission columns and the lifecycle
   timestamps have no public setter at all, so the state machine is the only thing that can move
   an order. A blanket `@Setter` would have quietly opened a hole the class exists to close.

3. **`ShopWalletBalance` has no setters whatsoever** — not even package-private. The balance moves
   only through the atomic repository `UPDATE`, so it is structurally impossible to change a
   balance without a matching ledger entry.

`Shop`'s lifecycle methods (`verify`, `reject`, `suspend`, `submitForReview`) are likewise
hand-written: they carry rules, and a generated setter would let any caller skip them.

### Verification

| Check | Result |
|---|---|
| `./mvnw clean install` | **BUILD SUCCESS**, 5,193 tests |
| Lombok in the fat jar | **0 entries** |
| App boot | 9.1 s, health **UP** on all six indicators |
| `ShopOrder` public setters | 4 — `adminNote`, `customerId`, `guestEmail`, `guestPhone` only |

### Open question #1 is closed

The user confirmed: **commission is 5% of `(subtotal - discount)`, excluding shipping and tax.**
That is what `CommissionCalculator` already implements and what its tests assert, so no change was
needed. D34 records it.

`CommissionCalculatorTest` covers it directly — `discountReducesTheBase` asserts the percentage
applies *after* the discount (Rs 1,000 − Rs 200 → 5% of Rs 800 = Rs 40, not Rs 30), and
`excludesShippingAndTax` asserts Rs 50 of delivery and Rs 130 of VAT do not enter the base.

---

## Appendix K — Phase 1: identity, shops and the verification gate

**Status: 7 of 11 Phase 1 items done.**

### Complete since Appendix I

| # | Item | State |
|---|---|---|
| 2 | **Shop signup with type** | `ShopService.signUp` |
| 3 | **Admin verification** | `VerificationService` + `AdminShopController` |
| — | Identity sync | `IdentitySyncService`, `CurrentUser` |
| — | Tenant guard | `TenantGuard` |
| — | Public gallery | `PublicShopController` |
| — | Shop dashboard API | `ShopController` |
| — | RFC 7807 error mapping | `ApiExceptionHandler` extended |

**Backend suite: 5,284 tests green, 0 skipped. App boots in 9.1 s.**

### Two architectural choices worth stating

**Shop-scoped endpoints derive the shop from the token** (D35). No endpoint accepts a
`shopId` parameter. Accepting one would make authorisation a per-endpoint decision, and the one
endpoint that forgets its `@PreAuthorize` becomes a cross-tenant read. Deriving it means there
is nothing to get wrong.

**Admin guarding is class-level** (D36), so a new admin endpoint is admin-only by default. Making
it public takes a deliberate edit rather than a forgotten annotation. The same reasoning is why
the Phase 7 data explorer must treat mutating endpoints as dangerous.

### The verification gate, verified live

The whole flow, against the running service and real Keycloak tokens:

```
1. shop signs up      -> slug=krishna-pharmacy type=PHARMACY status=DRAFT
2. public gallery     -> 0 shops visible          (correctly hidden)
3. submit for review  -> 200
4. admin queue        -> [('Krishna Pharmacy', 'PENDING_REVIEW')]
5. admin approves     -> status=VERIFIED verifiedAt=True
6. public gallery     -> Krishna Pharmacy appears
7. storefront         -> categories: MEDICINE, MEDICAL_DEVICE, SUPPLEMENT,
                            PERSONAL_CARE, BABY_CARE
                          offers KIDS_CLOTHING? False
8. type filter        -> PHARMACY: 1 shop   FASHION: 0 shops
```

Step 2 is the one that matters: **an unverified shop is invisible before an admin looks at it.**
That is the plan's answer to "limit fake shops", and it is asserted rather than assumed.

### A security gap the tests caught

`IdentitySyncService` originally derived the realm from the **last path segment** of the issuer.
That accepts `https://rogue.example.com/realms/ops` as the ops realm — the realm name is not a
secret, it appears in every legitimate token.

Fixed to compare the **whole issuer URL** against the configured values, with the two issuers
derived from a single configured base so they cannot drift apart. Pinned by
`rogueHostWithValidRealmNameRejected`, which tries the trick against both realms.

This was defence in depth rather than the primary control — `MultiIssuerJwtDecoder` had already
rejected the token — but the decoder is configured in one place and the sync service in another,
and a future change to one should not silently weaken the other.

### The most important bug found in this whole phase

**All seven `*IT.java` integration suites were silently skipped.** Surefire's default include is
`*Test.java` only, so `mvn test` reported a green build while running almost nothing — including
the wallet's 24 tests and the live Keycloak tests.

```
before:  Tests run: 5193   <- ITs never executed
after:   Tests run: 5284   <- 91 more tests, actually running
```

**Every "tests green" claim before this point was overstated**, including in Appendix I. The unit
tests genuinely passed, and the integration tests I ran individually did pass — but the aggregate
number was not what it appeared to be. Surefire is now configured to include both patterns, and
the live suites run in the normal build.

The lesson generalises: a green build that skipped its own tests is a lie with a passing status
code. Worth checking that a test suite is *executing* before trusting its count — the number is
the same whether it runs or not.

### A test that was wrong, not the code

`noShopYetIs404` expected 404 for a SHOP token with no shop. It got 200 — because
`IdentitySyncService` auto-provisions a DRAFT shop on first sight. The test's premise was wrong;
the behaviour is the intended one (an owner never sees an empty state). Rewritten as
`firstRequestProvisionsShop`, plus a separate test for the real cross-realm guard: a customers
token is **403** on `hasRole('SHOP')` endpoints, rather than 404-ing because no shop happened to
exist.

### Tests now exercise the real role mapping

`ShopControllerIT` uses the `authorities(Converter)` post-processor overload, which runs the
application's actual `KeycloakJwtAuthenticationConverter` over the token. If a realm role
stopped mapping to `ROLE_SHOP`, these tests would fail — a hand-asserted authority list would
sail straight past it.

### Error handling

`ApiExceptionHandler` now maps:

| Exception | Status | Code |
|---|---|---|
| `InvalidCategoryException` | 400 | `INVALID_CATEGORY` |
| `ShopTypeChangeConflictException` | 409 | `SHOP_TYPE_CHANGE_CONFLICT` |
| `UnknownShopTypeException` | 404 | `UNKNOWN_SHOP_TYPE` |
| `ShopNotFoundException` | 404 | `SHOP_NOT_FOUND` |
| `IllegalStateException` | 409 | `ILLEGAL_STATE_TRANSITION` |
| `IllegalArgumentException` | 400 | `INVALID_REQUEST` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` + `fieldErrors` |

### Remaining Phase 1 items

*(All delivered. See appendices L and M — Phase 1 is complete.)*

---

## Appendix L — Phase 1 item 4: products, variants and Cloudinary media

Products, variants, the media library and signed direct-to-Cloudinary uploads are built and
verified against the live account. Full suite: **5,379 tests, 0 failures, 0 skipped**, with
both the live Keycloak and live Cloudinary integrations enabled.

### D38 — a product always has at least one variant

A product is what a customer browses; a variant is what they buy and what carries the price,
the SKU and the stock. The single-SKU case is represented as a product with exactly one
variant rather than special-cased, because **a product with no variant has no price and cannot
be ordered**. `Product.publish()` and `ProductService.publish()` both refuse a product with no
active variant, so an unpriced row never reaches the storefront.

This also settles where the price lives. `product_variant.price_minor` is the source of truth;
`product.base_price_minor` is a **mirror of the cheapest active variant**, kept so a product
list can sort and show "from Rs. 40" without a join per row. `ProductService` recomputes it
after every variant price change, deactivation and activation.

### D39 — option axes are an ordered array in jsonb, not an object

`product_variant.option_values` holds `[{"axis":"size","value":"L"},{"axis":"colour","value":"red"}]`
rather than `{"size":"L","colour":"red"}`.

**This was found by a test, not designed up front.** jsonb does not preserve object key order —
it normalises keys by length then bytewise — so a variant labelled "Large / Red" read back as
"red / L", and two reads of the same row could disagree. A jsonb *array* preserves element
order. The order originates in the request body, which Jackson deserialises into a
`LinkedHashMap`, so the order the shop chose in the form reaches the stored label.

The ordering is not decoration: `variant.optionsLabel()` is what the customer reads on the
product page, and `VariantView` returns both the ordered list and the rendered label so the
client does not rebuild it and get it subtly wrong.

### D40 — media uploads go browser to Cloudinary, never through the API

Three steps: sign → upload → record.

1. `POST /api/v1/shop/media/signature` returns the signature, the folder and the size limits.
2. The browser posts the file **straight to Cloudinary**. Our API never sees the bytes.
3. `POST /api/v1/shop/media/record` reports the `public_id` back and the row is created.

A 5 MB image transiting this server is 5 MB of our bandwidth and connection time for something
Cloudinary will store anyway. The limits travel with the signature so the browser can refuse an
oversized file *before* spending the user's bandwidth on an upload that would be refused.

### D41 — a claimed upload is verified against Cloudinary, not trusted

Nothing stops a client from reporting a `public_id` that does not exist, or one that lives in
another shop's directory. `MediaService.record` therefore **re-derives the folder from the
caller's shop and compares it with the folder Cloudinary reports** for the asset. A mismatch is
refused. Without this check a shop could reference another shop's image and render it in their
own storefront — a cross-tenant read of the cheapest kind.

The signature covers every parameter the browser will send, so a client cannot add one of its
own; the folder is additionally built server-side from the caller's own shop id (D35), so the
only part they control is the leaf, and a traversal in it is rejected twice.

### D42 — the Cloudinary SDK, not a hand-rolled signature

The signed-upload algorithm is SHA-1 over an alphabetically sorted parameter string with the
secret appended, base64url-encoded. That was implemented by hand first and produced
`Invalid Signature` at every attempt with no useful diagnostic. The official `cloudinary-core`
plus `cloudinary-http45` pair is used instead:

- `cloudinary-core` is **API-only**. Without an HTTP transport artifact the client fails at
  construction with `Can't find Cloudinary platform adapter`, which reads like a missing
  dependency but is really a missing *runtime* strategy found by reflection.
- The 1.39.0 API differs from what the docs suggest: `uploader().upload(...)` returns
  `Map`, not `ApiResponse`; listing is `api().resources(...)`, not `uploader().listResources`;
  and `secure(true)` is on `Url`, not on `Transformation`.

Both jars are recorded in the pom with a comment explaining why, because the failure mode of
omitting the second is baffling.

### Bugs found by the tests in this phase

| # | Bug | Why it mattered |
|---|---|---|
| 1 | `jsonb` key order is not preserved, so option labels came back reordered | Customer-visible: "Large / Red" rendered as "red / L", inconsistently between reads. Fixed by D39. |
| 2 | `ProductService.delete` trusted a caller's stale `Product` instance | `soldCount` is updated by a bulk UPDATE that declares `clearAutomatically`, so the held entity was detached and still showed the pre-sale count — the sales guard silently passed and order history was stranded. `delete()` now re-reads. |
| 3 | `uploadAndRecord` stored Cloudinary's raw `secure_url` while the browser path stored a capped delivery URL | The same asset was reachable at two different sizes depending on how it was uploaded, and a shop's 6000px original was served in full on a listing page. Both paths now store the width-capped delivery URL. |
| 4 | `ProductNotFoundException` and `MediaNotFoundException` had no handler | Cross-tenant reads surfaced as **500** instead of 404. Added, with the 404-not-403 rationale recorded in the handler. |
| 5 | `VariantView.options` was a `Map` | Serialised as a JSON object, discarding the ordering that D39 exists to preserve. Now the ordered list plus the rendered label. |

### Test-count lesson: token `sub` must be `keycloak_sub`

`IdentitySyncService` resolves a token to a row by `keycloak_sub`, **not** by row id. A test
token carrying the `app_user.id` resolves to a *different* caller — who is auto-provisioned a
brand-new shop by `firstRequestProvisionsShop`. Every tenancy test then silently becomes a test
of the wrong shop and passes or fails for the wrong reason.

This produced a genuinely confusing failure: product endpoints returned 200 with zero products
and 404 for a product that plainly existed. The rule is now stated in the test helper:

> the token subject must be the user's `keycloak_sub`, or the request resolves to a newly
> provisioned caller who owns a different shop.

### Test fixtures

`TestFixtures` gained `createProduct(shop, name, category, priceMinor)` and a grocery-shop
convenience overload. Written through the repositories rather than the service, so a fixture
cannot fail because of a service rule the test is not about. `created_by` and `product_id` are
genuine foreign keys, so the actor is always the shop's real owner and the product always a
real row — a fabricated UUID fails the constraint before the test logic runs.

### Media usage taxonomy

One `media` table for every image in the system, so "the shop's media library" is a single
query and a shop's assets are removable in one sweep:

`PRODUCT_IMAGE`, `VARIANT_IMAGE`, `SHOP_LOGO`, `SHOP_FAVICON`, `SHOP_COVER`, `BANNER`,
`THEME_IMAGE`, `PAYMENT_QR`, `RECEIPT_PROOF`

Both `url` and `public_id` are stored, and both are needed. `url` is the only column the
storefront ever sees; `public_id` is what `destroy()` needs — without it every image is
undeletable and accumulates in the Cloudinary account for the life of the project.

`cloudinary_folder` is derived from the `public_id` (Cloudinary encodes the path into it) and
stored only so a shop's assets can be listed by folder without string parsing at query time.

### Schema change: V5

- `media.updated_at` added — `MediaAsset` extends `BaseEntity` like every other mutable entity,
  and media genuinely is mutable (alt text, sort order, product attachment).
- `product_variant.option_values` default changed from `'{}'` to `'[]'`, with a guarded
  `UPDATE` for any existing object. See D39.
- `idx_media_shop_product` for the per-gallery lookup.

### Verified live, end to end

Against the running app, real Keycloak and the real Cloudinary account:

1. Sign up a grocery shop → submit → admin verify.
2. Categories for the shop type served from the same validator that enforces the write rule.
3. A grocery listing `LAPTOP` → **400 `INVALID_CATEGORY`**.
4. Product + three variants created; mirrored base price = 12,000, the cheapest. ✓
5. Signature obtained → **file uploaded straight to Cloudinary** → `public_id` reported back →
   row recorded with a `c_limit,f_auto,q_auto,w_1600` delivery URL.
6. A lying client claiming another shop's folder → **400 `MEDIA_REJECTED`**.
7. Delete → database rows 0, and the Cloudinary account reports **0 assets total**. No leak.

### Remaining Phase 1 items

None. **Phase 1 is complete** — see Appendix M for the exit criterion measured against the
running application, and decisions D43–D55.

---

## Appendix M — Phase 1 COMPLETE: the vertical slice, end to end

**Status: 11 of 11 Phase 1 items done.** The exit criterion passes against the running
application, with real Keycloak, real Postgres, real RabbitMQ and the real Cloudinary account.

Full suite: **5,524 tests, 0 failures, 0 skipped.**

### The exit criterion, verified live

> *"a verified pharmacy shop publishes a product with category `MEDICINE`, a customer orders and
> pays by COD, the shop's wallet drops by exactly 5%, cancellation puts it back, the ledger
> reconciles against the balance cache, and attempting `KURTA` on that shop is rejected with
> `400 INVALID_CATEGORY`."*

Measured, not asserted by hand:

| Step | Result |
|---|---|
| `MEDICINE` on a grocery | `400 INVALID_CATEGORY` |
| Publish + stock 25 units | product visible on the public storefront, **no `costPrice` in any payload** |
| Guest checkout, no token | `ORD-0003` `PENDING`, total `Rs. 900.00` |
| Wallet `Rs. 1,910` → top up `Rs. 1,000` | `Rs. 2,910` |
| Accept | **`4,500` paisa commission = exactly 5% of `90,000`** |
| Wallet after accept | `291,000 − 4,500 = 286,500` — reconciles |
| `process → ship → deliver` | `PENDING → ACCEPTED → PROCESSING → SHIPPED → DELIVERED`, COD settled at dispatch |
| Stock | one movement: `−2 SALE` |
| Cancel a `DELIVERED` order | `409 ILLEGAL_STATE_TRANSITION` |
| No token on `/shop/orders` | `401` |

### D43 — commission is debited at ACCEPT, and frozen onto the order

Not at placement. A placed order can be abandoned, and charging a shop commission for an order
nobody intends to fulfil is how a marketplace loses its shops.

`OrderService.accept()` orders its six steps deliberately:

1. check the transition is legal
2. **compute** the commission (pure)
3. debit the wallet
4. **freeze** the commission onto the order
5. commit stock
6. move the status and record history

**Freeze comes after the debit, not before.** Freezing first was a bug: when the shop could not
pay, the exception rolled the transaction back but left the in-memory order already carrying
`commissionMinor`, so anything that caught the exception and continued in the same transaction
would persist a frozen commission for an order that was never accepted. The database was never
wrong — the rollback undid it. The *entity* was. Computing before writing means a failure at any
step leaves the order exactly as it was found. `OrderLifecycleIT` asserts the field is still
null after a refused accept.

The idempotency key is `"commission:" + orderId`, so a double-clicked Accept debits once.

### D44 — order numbers are allocated inside the caller's transaction

Originally `REQUIRES_NEW`, so a failed checkout would still consume a number on the reasoning that
gaps look like lost orders. Two things are wrong with that:

- it needs a **second database connection per checkout**, held for the whole order transaction;
- it **cannot see the caller's uncommitted writes** — a checkout inside a transaction that created
  the shop in the same transaction fails its foreign key, because the suspended transaction sees
  only committed data.

Both are worse than the perceived benefit. A failed checkout rolls the counter back with everything
else, so no gap appears; and orders are never hard-deleted, so the only way to create a gap is to
delete an order that an invoice already names. `CheckoutRollbackIT` asserts a failed checkout
leaves no order, no reservation, and no advance of the counter.

The counter is a **row lock**, not `MAX() + 1`. `MAX + 1` is the classic way to give two customers
the same order number; the pessimistic lock serialises one shop's checkouts while leaving every
other shop unblocked.

### D45 — reserving stock is a compare-and-set, not a read-then-write

Two customers ordering the last unit both read `available == 1` and both succeed. So the
reservation is a single UPDATE whose WHERE clause states the precondition:

```sql
UPDATE inventory SET quantity_reserved = quantity_reserved + :qty
 WHERE product_id = :p AND variant_id = :v
   AND quantity_on_hand - quantity_reserved >= :qty
```

A row count of 0 means someone else got there first, and the caller gets an
`InsufficientStockException` rather than an oversell. `quantity_reserved <= quantity_on_hand` is
the constraint underneath.

`InventoryService.reserve` is **`Propagation.MANDATORY`**: the reservation joins the order's
transaction, so a failed checkout cannot leave phantom stock held for an order that does not exist.

### D46 — committing a reservation attributes only what was reserved

A retried accept must be safe, because shop owners double-click and mobile networks retry. The
first version suppressed the movement log while still subtracting the full requested quantity from
on-hand — which *looked* idempotent in the log and silently destroyed stock. `InventoryIT` caught
it: 10 units became 2 in a test that had only checked the log.

`Inventory.commitReservation` now subtracts `min(quantity, reserved)` and **returns what it
attributed**. Zero attributed means nothing changed and nothing is logged.

A **backorder deducts nothing**: its goods were never on the shelf, so the honest position is to
take nothing and let the shop's restock be the movement that explains the shortfall. The test that
asserted the opposite was wrong, not the code.

### D47 — "terminal" and "closed" are different words

`DELIVERED` was in `TERMINAL_STATUSES` *and* had two successors in the graph. `OrderStateMachineTest`
caught the contradiction, because it asserts that terminal status agrees with having no successors.

- **closed** — no further fulfilment step. `DELIVERED`, `CANCELLED`, `RETURNED`, `REFUNDED`.
- **terminal** — can never change. `CANCELLED`, `RETURNED`, `REFUNDED`.

Delivered is closed but not terminal: a customer can still return it in Phase 8. Conflating the two
makes a promise the transition graph then breaks.

Only a **`PENDING`** order can be cancelled. That one rule removes the need to reason about
cancelling after the goods have shipped — which is a return, with a much more careful flow.

### D48 — an inclusive tax is reported without being added

The subtlest bug in this phase, and the only one that would have reached customers.

A tax rule has `inclusive`: an inclusive tax is *already inside* the displayed price, so it is
extracted from it rather than added on top. Getting this backwards overcharges every customer by
the tax amount.

`TaxRule.taxOn` handled it correctly. The quote engine did not. It derived the order's tax from
`total − subtotal`, which is **zero** for an inclusive rule — the total was right and the breakdown
silently said "no tax". Then, once reported separately, an intermediate version added it to the
total as well, charging 13% twice.

The fix is two numbers rather than one:

- `addedToTotal` — what the customer pays on top. Zero for inclusive.
- `reported` — how much of the price was tax, for the invoice. Non-zero for inclusive.

**They are identical for the ordinary exclusive case**, which is why an order's line taxes sum to
its total tax without anyone having to think about it. `QuoteServiceIT` pins both.

### D49 — the discount engine distributes so the parts sum exactly

Not approximately. `Money.distribute()` hands leftover paisa to the earliest weights, so the line
totals plus shipping equal the order total **by construction**. A customer who adds up the lines
and finds a one-paisa difference concludes the shop's arithmetic is broken.

`QuoteServiceIT` asserts `sum(lineTotals) == total − shipping` directly, on a basket of
deliberately awkward prices (3,333 × 3 + 7,777 × 2 + 1,001 × 7) with a 1,300bp tax and shipping.

Tax is computed **on the post-discount amount** — taxing the pre-discount amount would overcharge
the customer and the tax authority.

An invalid discount code **never fails checkout**. It charges full price and returns a message,
because a customer with a typo in a code should still be able to buy what is in their basket.

### D50 — guest checkout is public, and it says so

`/api/v1/checkout/**` is `permitAll`. A storefront must sell to someone with no account; that is
the single largest source of lost sales in a marketplace. A token is still read when present, so a
signed-in customer's order is attached to their account.

Every other shop endpoint takes a token, and none of them take a shop id — the shop is the
caller's own (D35), so cross-tenant access is a request a caller cannot express.

### D51 — a server error on a public endpoint is not reported as a 401

Found by the live check, and worth recording because it is a genuinely confusing failure mode.

A server-side error is dispatched back through the security filter chain with the original
request. On a `permitAll` endpoint, `anyRequest().authenticated()` then fires against a request with
no token, and a database error was reported to the caller as:

```
401 "A valid Bearer token is required"
```

The real error underneath was `invalid input syntax for type json` — from a hand-rolled
`Map.toString()` in the address serialiser, which produces `{name=Ravi, city=Kathmandu}`. Not JSON.
Fixed by serialising with Jackson.

The status code was the actual bug: **a 500-class error presented as 401 sends whoever is
debugging it looking at authentication instead of at the database.** Fixed with
`dispatcherTypeMatchers(ERROR, ASYNC).permitAll()`.

### D52 — eSewa refuses rather than pretending

`EsewaGateway` exists so nothing in the checkout flow has to be written twice when it lands in
Phase 5, and it **throws** rather than returning a plausible-looking redirect URL. A redirect to
nowhere produces a customer who has entered their details and then seen nothing happen — strictly
worse than being told the method is unavailable. `available()` excludes it from the customer's
choices so it is not even offered.

`CodGateway.verify` returns "cannot know" rather than "not paid", because the platform cannot
distinguish those, and `OrderService.markDelivered` **refuses to deliver an unverified bank
transfer** — money that never arrived cannot quietly become an order marked delivered.

### D53 — order lines are snapshots and do not extend BaseEntity

`order_item` carries `created_at` but no `updated_at`, so `OrderItem` does not extend `BaseEntity`
like almost every other entity. An order line is a frozen record of what was bought, at what
price, on one date; allowing it to be edited would be allowing a change to what the customer was
charged, which is the exact failure the snapshot exists to prevent.

`created_at` is load-bearing: it orders the lines the way the customer added them, and the line id
is a random UUID that sorts arbitrarily.

The same reasoning applies to `InventoryMovement`, `PaymentTransaction`, `Notification` and
`ShopFollower` — all written once, never edited. `Inventory` is the inverse: a current-state row
that is mutated forever, so it has `updated_at` and no meaningful `created_at`.

### D54 — the outbox, and why it exists

Writing the event in the same transaction as the state change closes the window where the database
has committed but the message has not been sent (R5). `OutboxService.record` is
**`Propagation.MANDATORY`** — not an assumption, an enforcement.

`OutboxRelay` publishes each event in **its own transaction**, so one poison message cannot block
the queue behind it; with `default-requeue-rejected: false` a requeued message goes to a dead-letter
queue rather than spinning forever. Attempts are **bounded at 10**, because a poison message retried
without limit grows the table and fills the logs while nobody ever sees the failure.

A failure is recorded **on the row**, not merely logged, so an operator can find what is stuck
without reading application logs.

### D55 — the follower counter moves only when a row was created

Following twice is common — a double-tap, a retry. Incrementing unconditionally is how a follower
count drifts above the number of real followers, and a count that overstates an audience is worse
than one that is imprecise because the owner trusts it.

So: check, `saveAndFlush` (which is what surfaces the concurrent duplicate), and only on a genuinely
new row increment. The tests assert against `countOf`, which **counts the rows** — asserting a
cache against itself proves nothing. `reconcileCounter` repairs drift for the same reason.

### Testing note: only one suite commits

Every other suite is `@Transactional` and rolls back. A rolled-back test **cannot** answer "did
this failure leave anything behind?", because inside a transaction the insert has been flushed
while not yet committed and the answer looks identical whether or not the rollback logic works.

`CheckoutRollbackIT` is the documented exception: it commits, and cleans up in `@AfterEach`
through `TestDatabaseCleaner`, which **refuses to run against any database whose name does not
contain "test"** — a mistyped JDBC URL there would otherwise empty the development database.

That cleaner deletes in dependency order, because `shop_order.shop_id` is `ON DELETE RESTRICT`
precisely so an order can never be orphaned. It also removes `outbox_event` and `notification`,
which have no `shop_id` column and so are not covered by the shop's cascade — found by a relay test
seeing rows another suite had committed.

### Items delivered in this appendix

| # | Item | Where |
|---|---|---|
| 9 | Order lifecycle, inventory reservation, order numbering | `order/OrderService`, `OrderStateMachine`, `OrderNumberService`, `inventory/` |
| 6 | Checkout: quote engine, server-side authority, order creation | `checkout/QuoteService`, `CheckoutService`, `CheckoutController` |
| 7 | `PaymentGateway` + COD + bank transfer live, eSewa stubbed | `payment/` |
| 10 | Outbox + RabbitMQ relay + notification worker | `notification/` |
| 11 | Follower counter and increment path (D13) | `social/` |
| 5 | SSR storefront data source (public catalogue reads) | `catalog/PublicProductController` |
| 8 | Manual top-up: request → admin approval → ledger credit (D7) | `wallet/WalletTopUpService`, `WalletController`, `AdminWalletController` |
| — | Shop's own stock control | `inventory/StockController` |

### Phase 2 next

Product editor depth (images in the UI, variant matrix, bulk edit) · inventory CSV import/export ·
Postgres FTS with trigram typo tolerance · SEO · duplication. The catalogue already carries every
column these need.

---

## Appendix N — Phase 2 COMPLETE: catalogue and inventory depth

**Status: Phase 2 complete.** Full suite: **5,604 tests, 0 failures, 0 skipped.**

The exit criterion — *"a shop manages a 500-SKU catalog across its type's categories, including
variants and stock adjustments, with every quantity change traceable"* — verified live against a
1,010-product catalogue.

### D56 — search is full-text AND trigram, because they fail differently

Full-text answers `rice` and is fast, but an owner typing `paracetmol` — a common misspelling of
paracetamol — gets nothing back and concludes the shop has no paracetamol. Trigrams match character
sequences, so near-misses land. Both are needed: full-text alone is too strict, and trigram alone
cannot help a two-character query because a 2-gram index has no useful selectivity there.

Ranking is deliberate, and asserted:

1. an exact word match outranks a fuzzy one (`rice` beats `ricee`)
2. a **name** match outranks a description match (`setweight 'A'` vs `'B'`)
3. sales break ties

`websearch_to_tsquery('simple', …)` rather than `plainto_tsquery`, so `"quoted phrases"`, `*`
prefixes and `-exclusions` work, and odd input never throws.

**`'simple'` not `'english'`.** English stemming suits an English-only catalogue but mangles every
non-Latin token — a Nepali or Hindi product name gets stemmed or dropped, and this platform sells
across 19 verticals in a multilingual market. `'simple'` does no stemming, so it never destroys a
token it does not understand; typo tolerance comes from trigram, which is language-agnostic.

### D57 — SKU matching is exact, not fuzzy — and trigram was tried and was wrong

Searching `SKU0042` found nothing while `SKU-0042` found the product: full text tokenises the first
into one lexeme and the second into two.

The obvious fix — trigram on the SKU list — **was wrong**, and the reason is worth recording.
Every SKU shares the `SKU-` prefix, so the trigrams `{sku, ku-, u-0, -00}` are common to all of
them and `similarity()` came out around **0.57 between any two SKUs**. Searching `SKU-0042X`
returned all 1,000 products that have a SKU.

**Codes are not words: they either match or they do not.** So `product.sku_search_key` holds the
SKU list with every non-alphanumeric character stripped, and the query term is stripped the same
way. `SKU0042` and `SKU-0042` both match; `SKU-0042X` matches nothing.

### D58 — the search document is generated, and SKUs are denormalised onto the product

`search_document` is a **generated** `tsvector` column, so it cannot drift from the columns it
indexes. A trigger-maintained vector would need updating by every service that touches a name, and
the one that forgets is invisible until a search is quietly wrong.

The SKU list cannot be in a generated column — it lives on `product_variant` and a generated column
cannot reference another table. It is denormalised onto `product` and refreshed by
`refresh_product_sku` inside the same transaction as any variant write. Derived by a database
function rather than passed in by callers, so no caller can get it wrong by forgetting.

### D59 — search is two queries, because drafts are hidden from customers but not from owners

`searchInShop` (the console) includes drafts and archived products; `searchInCategory` (the
storefront) is `status = 'ACTIVE'` only. That is not an oversight — a shop owner looking for their
own draft must find it. Drafts are hidden from *customers*, not from their owner.

### D60 — CSV import is per-row, never all-or-nothing, and defaults to ADD

An owner importing 500 SKUs who loses the whole file on row 137 ends up doing it by hand. Every row
is applied independently and reported on. `ImportResult` carries `rows` (one per data row) and
`errors` (whole-file problems, meaning nothing was imported at all).

**`ADD` is the default, not `SET`.** A spreadsheet handed to a shop is almost always a supplier's
delivery note; overwriting a stock count from one destroys it. `SET` is available because a
stocktake genuinely needs it, but it must be chosen.

**Import never creates products.** A CSV row naming an unknown SKU is an error, not an instruction
to invent a catalogue.

The parser is real RFC 4180, not `split(",")`. A product called `Rice, 5kg` would otherwise shift
every later column and corrupt the whole import.

**The header row is optional**, detected by looking for a `quantity` column rather than assumed to
be row one. A spreadsheet written from scratch often begins with data, and rejecting it with
"no quantity column" for a file that looks correct to its author is the worst outcome.

**Formula-looking fields are neutralised.** A product legitimately named `=SUM(A1:A9)` is executed
as a formula by Excel when the owner opens the export. Quoting alone does not prevent it; a leading
`=`, `+`, `-` or `@` gets a `'` prefix. This is CSV injection.

### D61 — CSV export writes on-hand, with reserved alongside

**Exporting `available` (on hand − reserved) was tried first and is wrong**, and the round trip
found it. `SET` writes on-hand, so re-importing an available figure silently understates stock by
exactly the reserved amount, every time. `quantity` is therefore on-hand — which is also what a
stocktake counts, since the reserved units are physically on the shelf.

`reserved` is exported beside it, **read-only**. It is ignored on import: the reservation is the
platform's to hold, and a column an owner can edit but that has no effect is a trap.

Verified: **export → halve → import → export is byte-identical to the intended values**, 0 of 1,008
mismatched.

### D62 — a duplicate SKU is reported, not guessed at

A SKU is unique per **product**, not per shop (the schema's choice, and a legitimate one). So two
products in one shop can carry the same code.

The import originally took the first match, which marked the row **applied** while quietly leaving
the other variant untouched — and the owner trusts the summary. The lookup now returns up to two
matches and the row is refused with "matches 2 variants in this shop. A SKU must be unique; rename
one before importing."

Verified live: importing 1,012 rows applied 1,007 and refused 5 — all of them duplicated variants
with no SKU and an ambiguous name. Reported, not guessed.

### D63 — CSV import is one indexed query per row, not O(products)

The first implementation resolved a SKU by loading every product in the shop and then querying each
one's variants. That is O(products) per row, so a 1,000-row import issued roughly a million queries
**and did not finish**.

Found by running the exit criterion itself, which is the argument for having one. Now a single
indexed query joining variant to product, with indexes on `lower(sku)` and `lower(name)`.

### D64 — bulk edits skip-and-report, and touch only what was supplied

An owner selecting 40 products wants 39 changed and one refused with a reason, not an error and no
changes. `BulkResult` carries `applied` and `skipped` with a reason each.

Only supplied fields are touched. A bulk price change must not blank descriptions — which is what
a naive "replace the whole object" implementation does, across 40 products, unrecoverably.

A bulk **category** change is re-validated against the shop type. A bulk action must not be a way
around the rule the single-product endpoint enforces.

Each variant in a bulk price change is adjusted from **its own** price, so a +10% preserves the
relative spacing between sizes. Clamped at zero rather than failing the batch: refusing to sell at
a loss the shop explicitly asked for is not this layer's decision.

### D65 — a duplicate is a draft with no SKUs; the variants matrix is a replace

A duplicate is always **DRAFT**. Duplicating a live product straight to `ACTIVE` puts a second copy
on the storefront before the owner has finished changing it — and the usual reason for duplicating
is to change something.

Variant SKUs are cleared, because a SKU is unique per product and carrying it over would collide on
the first save. Compare-at price and featured are cleared too: a strike-through price copied onto a
product that has just changed is a claim about the new product.

The variants matrix is a **replace, not a merge**, and that has a sharp edge: a variant omitted
from the request is **deactivated**. A merge would leave behind variants the owner believes they
deleted, still holding counted stock. Variants are matched by id, and an unrecognised id is an
error rather than an insert — silently creating a variant for a typo'd id gives a duplicate the
owner did not ask for.

### D66 — the storefront catalogue is grouped and capped, with the true total beside the shown count

`/api/v1/p/shops/{slug}/catalogue` returns every category group in one request, because a grocery
with six categories would otherwise be six round trips before the first product appeared.

Capped at **12 products per group**. A storefront that renders 500 cards is unusable on a phone,
and a customer scrolling past 500 products has already decided they are on the wrong shop. Each
group carries `total` as well as `shown`, so the UI can say "showing 12 of 100" rather than
implying 12 is all there is.

Featured first, then best-selling — a storefront's job is to show what the shop most wants to sell
above the fold, which is not the same as the newest products.

### Bugs found while building this phase

| # | Bug | Why it mattered |
|---|---|---|
| 1 | `refreshSkuIndex` declared `clearAutomatically` | Detached the product mid-method, so `refreshMinimumPrice` was silently ignored and tests read stale values. Only `flushAutomatically` is needed. |
| 2 | Spring Data appends a `Pageable` sort to a native query by wrapping it | Produced `(ORDER BY … order by createdAt desc)` — a syntax error. Every search from the console's product list was failing. Fixed by dropping the sort (the ranking *is* the ordering). |
| 3 | CSV import was O(products) per row | A 1,000-row import did not finish. The exit criterion caught it. |
| 4 | `RETURNS void` function called via `SELECT` | JPA rejects it: "a result was returned when none was expected". The function is for the migration backfill; the live path writes the UPDATE inline. |
| 5 | `SET p.column = …` in Postgres `UPDATE … AS p` | Postgres allows the alias but rejects a qualified target column. |
| 6 | `sku_search_key` declared *after* the function that writes it | PL/pgSQL bodies are checked at runtime, so the migration "succeeded" then failed on first call. |
| 7 | Trigram on the SKU list | Every SKU shares a prefix, so similarity was ~0.57 between any two and a bad query matched 1,000 products. |
| 8 | Export wrote `available`, import `SET` wrote on-hand | Round trip silently lost the reserved amount every time. |
| 9 | `restock(note)` accepted a note and dropped it | "Why did stock jump by 30 overnight" was unanswerable from the log — which is the log's whole purpose. |
| 10 | `CategoryGroup` documented a `total` field it did not have | The UI could not show "12 of 100". |
| 11 | `truncated()` is not a bean getter | Jackson omits it, so `truncated` was `undefined` in the client with no error anywhere. |

### A note on `flyway:repair`

`repair` updates a migration's stored checksum but **does not re-execute it**. So editing an
applied migration and repairing leaves the schema at the old state while the file claims the new
one, and the symptom is a misleading "column does not exist" at boot. The fix is to delete the row
from `flyway_schema_history` and let it re-run — safe only because these migrations are written
idempotently (`IF NOT EXISTS`, guarded `UPDATE`).

Worth remembering for any future edit to V7.

### Delivered in this appendix

| Capability | Where |
|---|---|
| Full-text + trigram search, typo tolerant | `ProductRepository.searchInShop`, `CatalogSearchIT` |
| SKU search, separator-insensitive | `product.sku_search_key`, V7 |
| CSV import/export with per-row reporting | `InventoryCsvService`, `InventoryCsvServiceIT` |
| Bulk field and price edits | `ProductService.bulkUpdate`, `bulkAdjustPrices` |
| Product duplication | `ProductService.duplicate` |
| Variants matrix (replace) | `ProductService.replaceVariants` |
| Grouped public catalogue | `PublicProductController.catalogue` |
| Low-stock list, shop-scoped | `StockController`, `findLowStockForShop` |

### Still Phase 3

Theme engine: section registry, zod schema, template marketplace, live preview, token system.

---

## Appendix O — Phase 3 COMPLETE: the theme engine

**Status: Phase 3 complete.** Backend **5,679 tests green, 0 skipped**. Frontend: all 10 packages
typecheck, both apps build.

Exit criterion — *"an admin publishes a pharmacy template, a pharmacy shop installs it, customises
sections and tokens, publishes, and the SSR storefront renders it — visually identical to the editor
preview"* — verified live.

### What was built

**Backend** (`com.storefront.store.theme`) — migration V8 (`theme_template`, `shop_theme`,
`theme_asset`), `SectionType`, `ThemeValidator`, `DefaultTemplates`, three entities, three
repositories, `ThemeService`, `ThemeTemplateSeeder`, and three controllers (admin marketplace, shop
editor, public storefront payload).

**Frontend** (`packages/theme-engine`) — `resolve.ts` (data resolution, no JSX), `sections.tsx` (24
components), `render.tsx` (the renderer), plus `packages/api-client/src/theme.ts` and the console
editor at `apps/console/src/pages/shop-theme.tsx`.

### D67 — the two validators are halves of one contract, and a test keeps them in step

`packages/theme-engine/src/schema.ts` (zod) and `ThemeValidator.java` are the same rules in two
languages, because the browser cannot be trusted to have validated a template and the server
cannot reach into the storefront's module graph.

They **drifted**, and it cost a blank storefront. See the bug below. `SectionRegistrySyncIT` now
reads the TypeScript file and asserts the section-type sets are identical. A test that reads a
source file from another repository is unusual and earns its place: the failure it catches is
silent on both sides. **Verified that it can actually fail** — adding a probe type to the Java enum
makes it fail and names the offending type.

### D68 — the storefront must never render blank, and never silently

`parseTheme` degrading to an empty theme is right for a parser and wrong for a renderer, and the
two have to be separated. A theme that does not validate now:

- renders its **valid** parts,
- shows a notice naming the problems,
- falls back to a shop-name block when nothing parsed,
- and logs to `console.error` on the server regardless.

Before this, a validation failure produced an empty `<div>`: no error, no partial render, no
trace. The renderer owns the failure; the parser only owns the parse.

### D69 — `#fragment` nav links, and the bug they caused

The server's `isSafeHref` accepted `#`, and **every shipped default template uses `#products`** for
its hero call-to-action. The zod `navItemSchema` allowed only `/` and `https://`.

So a theme the server had validated parsed to an **empty theme**, and the storefront rendered
nothing. Found by fetching the rendered HTML and finding `<main>` eleven bytes long.

Aligned the schema to the server (fragments allowed; protocol-relative rejected), added the
missing duplicate-section-id check for parity, and fixed the gallery page, which had been 500ing
since Phase 0 on `shops.map is not a function` — it assumed a bare array where the endpoint returns
a paged envelope. That was the home page, the one URL every visitor lands on.

### D70 — a shop is never left without a storefront

`ThemeService.ensureDefaultTheme` creates a per-type default on demand, as a **draft**. The
storefront renders from `renderableConfig`, which falls back to the type's default when nothing
is published — so a new shop has a complete, correct storefront immediately, and the default
improves for everyone when it improves for one.

Per-type defaults for `PHARMACY`, `FASHION` and `GROCERY`, plus a generic fallback. A pharmacy's
leads with "prescriptions accepted", because that is the question its customers have. A test
asserts **every** shop type's default passes the validator, so a default can never be
unrenderable.

### D71 — a template is a snapshot, and a live theme is never edited

`shop_theme.config` is a **copy** of the template's. Editing a template therefore cannot restyle
every shop that installed it, and deleting one cannot break them — which is why `template_id` is
provenance and `ON DELETE SET NULL`.

A live theme cannot be saved or deleted at all. Saving a half-finished configuration over what a
customer is looking at is the single thing this API exists to prevent, and it is enforced in the
entity, not just the UI.

### D72 — a dangling asset reference is a warning, not an error

A section may say `imageUrl: "asset:HERO/main"`, and that asset may not exist yet. A shop can lay
out a page before uploading its photograph; refusing to save would make the editor fight them. The
theme **saves**, and `danglingAssetHandles` reports it so the editor can warn. The renderer shows a
placeholder.

Verified live: installing the pharmacy template and saving it produced exactly one warning —
`Section 'hero-1' references asset HERO/main, which does not exist` — and the theme still saved.

### D73 — the preview IS the storefront

The editor's preview calls the same `ThemeRenderer` with the same props shape as the SSR
storefront. There is no second implementation to drift. `/api/v1/shop/theme/preview` returns a
document byte-identical to what the storefront serves — asserted live:

```
preview == storefront config: True
```

### D74 — the storefront payload is one request per page view

`GET /api/v1/p/shops/{slug}/storefront` returns the shop, the published theme, the category tiles,
featured products, latest products and the shop's assets together. The alternative is six calls
before the first product appears, and a page whose content depends on how many of them were fast.

`category-grid` resolves from the shop's `shop_type` enum — the D17 payoff — and returns only
categories the shop **has products in**. A grocery with 1,010 products across 10 categories shows
those 10, not an empty grid of tiles its vertical merely permits.

### Verified live

| Step | Result |
|---|---|
| Admin marketplace | `pharmacy-trust` (PHARMACY), `general-storefront` (any), both published |
| Grocery shop offered | `general-storefront` only — **pharmacy-trust correctly excluded** (D17) |
| Install | 7 sections, as a draft |
| Customise | `primary #7c3aed`, `radius 1rem`, hero heading changed |
| Save | warning about the dangling asset, theme saved |
| Draft not live | storefront still served the default |
| Publish | storefront served the customised theme, 7 sections |
| Preview == storefront | `True` |
| Bad theme | `INVALID_THEME` with 3 problems named by path |
| Edit a live theme | `409 ILLEGAL_STATE_TRANSITION` |
| Withdraw | falls back to the platform default, still renders |
| **SSR render** | HTTP 200, 99 KB HTML, `<h1>CUSTOMISED BY THE SHOP`, `--primary:#7c3aed` in the markup |

Every section type in the live theme rendered with real data — no `unknown-section` fallback, no
issues notice. Category grid links to `/s/my-shop/c/coffee`, `/s/my-shop/c/dairy` and so on, from
real product counts.

### Seeding, and why it is gated

`ThemeTemplateSeeder` publishes two starter templates on boot and **never overwrites**. It is
**off in the test profile** (`store.themes.seed=false`): a test asserting "a draft is not offered"
cannot also tolerate a seeder that published a template on the way into the context. The right
answer is to switch the seeder off, not to write tests that assume a fixed set of rows nobody
controls. (Rows it had committed to the test database were removed once.)

### Still deferred, deliberately

- **`html` section** renders a notice, not markup. Raw HTML needs the sanitiser the plan defers to
  Phase 9 (D15). The schema accepts the prop so a saved theme does not lose its data.
- **`newsletter`** renders a layout block with no form: there is no subscriber table, and a form
  that silently discards an address is worse than none.
- **`social-feed`** renders a placeholder; a third-party script on every customer page view is not
  a Phase 3 trade.
- **`countdown`** renders the shell server-side. A live timer needs a client component, which would
  make the section the one exception to "every section is a server component".

### Phase 4 next

Shop operations: orders board with bulk transitions, invoice generator, discount system, audience
list, full shop settings, payment method configuration (including eSewa merchant credentials).

---

## Appendix P — Phase 3 follow-up: the blank page and the missing CSS

Both were Phase 3 verification gaps, found only when a human opened the apps rather than when a
test asserted on them. Worth recording because both fail *silently*.

### D78 — `@store-app/auth` reads env from both Vite and Next.js

`packages/auth/src/config.ts` did `process.env[key]`. That is fine in Next.js and fatal in the
Vite console, which **does not define `process` in the browser at all**. The result was a
`ReferenceError` thrown while the module was still evaluating — so nothing rendered and every
page was white.

Vite's `define` cannot rescue this: it only rewrites **static** `process.env.NAME` text, and this
was a computed index. Nor would `define` necessarily apply, because the file lives in a linked
workspace package served through Vite's `/@fs/` path rather than in the app root.

`readEnv()` now tries `process.env` behind a `typeof` guard, then falls back to `import.meta.env`
— which is how Vite exposes `VITE_*` variables. The guard matters: the bare reference must never
be *evaluated* in a browser, not merely be absent.

### D79 — React mounts before auth initialises

`main.tsx` waited on `auth.init()` before rendering, to avoid a login-screen flash on reload. The
cost was total: any rejection, and any promise that never settles, left a permanently white page
with nothing to look at.

It now renders first and authenticates second. `RequireRole` already redirects an unauthenticated
visitor to `/login`, so the user reaches the login screen a few milliseconds later — and a login
flash is a cosmetic annoyance next to a blank page with no error. The init failure is
`console.error`'d rather than swallowed by `.finally()`.

This compounded D78 into an invisible failure and is the reason the first diagnosis took a while:
there was no error, no empty state, and no stack trace — just white.

### D80 — the Next.js app had no Tailwind wiring at all

Tailwind v4 is installed and the console uses `@tailwindcss/vite`. **The Next.js app declared
neither `tailwindcss` nor `@tailwindcss/postcss`, and had no `postcss.config.mjs`.** Next.js does
not go through the Vite plugin; it uses PostCSS. So `@import 'tailwindcss'` in `globals.css` was
never processed, the emitted stylesheet was effectively empty, and the storefront rendered every
element with the right class names and no styles at all.

Fixed by declaring `@tailwindcss/postcss`, `postcss` and `tailwindcss` in
`apps/customer/package.json` and adding `apps/customer/postcss.config.mjs`.

Both frontend pipelines now stay independent — the console through Vite, the storefront through
PostCSS — which is what lets them ship on different schedules.

### What Phase 3 verification missed

Everything in Appendix O was verified through the API and by inspecting server-rendered HTML.
That proved the theme engine works and the storefront renders it. It did not prove that

- the **console** boots in a browser, and
- the storefront's **stylesheet** is non-empty.

Both are only observable by loading the page. The lesson is narrow and worth keeping: *a build
that succeeds and HTML that contains the right markup can still be a blank page or an unstyled
one.* Neither failure produces an error, which is exactly what makes them easy to ship.

### Current state after the fixes

| | |
|---|---|
| Backend :8080 | UP — db, rabbit, redis, ssl |
| Storefront :3000 | HTTP 200, stylesheet linked, `<h1>CUSTOMISED BY THE SHOP`, `--primary:#7c3aed`, 8 category links |
| Gallery :3000 | HTTP 200, stylesheet linked, shop links render |
| Console :5173 | HTTP 200, no `process` reference, 14 KB of CSS |

Still to verify by hand, since no browser is attached to this session: **signing in through
Keycloak** (it redirects to the remote realm and back to `localhost:5173`), and **clicking through
the theme editor**.

---

## Appendix Q — the same `process` bug, three more times

Appending to Appendix P, because the lesson is the same one and the repetition is the point.

### The mistake

Appendix P fixed **one** `process.env` reference — the one in the file the browser's stack trace
named. There were four. `enums/hooks.ts`, `api-client/client.ts` and `theme-engine/registry.ts`
each read environment variables at **module scope**, where a `ReferenceError` blanks the app
before React mounts. Each surfaced as its own "still white page" report.

Patching the file an error names, rather than grepping for the pattern, is how four became three.

### Why Vite's `define` did not fix it

The obvious fix was `define: { 'process.env': ... }` in `vite.config.ts`. It is configured now and
kept — it covers any third-party dependency that reads `process.env` — but it **does not reach
workspace packages served from outside the app root** via Vite's `/@fs/` path. Verified: with
`define` in place, `/@fs/packages/enums/src/hooks.ts` still served
`process.env.NEXT_PUBLIC_API_URL` verbatim.

So the fix has to be in the source. Every shared package now uses a guarded read:

```ts
function readEnv(key: string): string | undefined {
  if (typeof process !== 'undefined' && process.env) {
    return process.env[key];
  }
  return (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.[key];
}
```

The `typeof` is load-bearing and is not stylistic: the bare reference must never be **evaluated**
in a browser, not merely be absent.

`auth/adapter.ts` and `auth/config.ts` now share one `readEnv` rather than each having their own,
so the duplication that caused this cannot recur inside that package.

### The rule this establishes

**A package shared by two bundlers may not reference `process.env` or `import.meta.env` directly.**
Neither bundler defines both, and neither tool warns at build or type-check time — the build
succeeds, the types check, and the failure appears only as a blank page.

All five env-reading modules were verified **as the browser receives them** over `/@fs/`, not by
reading the source: zero unguarded references.

### Favicon

The console now declares `<link rel="icon" href="/favicon.svg">`, which is why Chrome had been
requesting `/favicon.ico` and getting a 404 — it had nothing declared, so it guessed. A direct
request for `/favicon.ico` still returns 404, which is normal and harmless once the icon is
declared; the browser uses the declared SVG and does not ask again.

### The general lesson, which is now three appendices running

Phases 1–3 were verified through APIs, SQL assertions and server-rendered HTML. Every one of those
checks passed while the console could not render a single pixel and the storefront had no
stylesheet. None of them can catch these, because they are all failures of **delivery**, not of
logic:

- a bundle that throws before it renders,
- a stylesheet that is emitted but empty,
- a config value that never reaches the browser.

The cheapest possible check — open the page — found all three in under a minute, and none of the
several thousand automated assertions had. Any later phase should treat "load the page and look at
it" as part of done, not as an optional extra.

---

## Appendix R — memoising an async initialisation, and the `AppShell` unhandled rejection

Third console bug, and the first one in this sequence that is a genuine race rather than a
configuration mistake.

### D81 — cache the promise, not the result

The adapter guarded re-initialisation like this:

```ts
async init() {
  if (ready) return kc.authenticated;
  ready = await kc.init({ ... });
  return ready;
}
```

`ready` stays `false` for the whole duration of the `await`, so it is not a guard at all — it only
works if callers happen to be sequential. Two callers exist (`main.tsx` and `AppShell`), and React
StrictMode double-invokes effects in development, so on every reload both fire `kc.init()` and the
second throws:

```
Error: A 'Keycloak' instance can only be initialized once.
```

Fixed by memoising the **in-flight promise**. Every caller awaits the same initialisation, so
`kc.init()` is called exactly once.

The rejected case is cached too, deliberately. Retrying a failed `init()` would call `kc.init()`
again, which is precisely the operation that throws; a second, louder failure is worse than one
honest failure. `login()` can still be called explicitly.

Reproduced and proven with a standalone test of the guard, since `keycloak-js` needs a browser:

| | `kc.init()` calls | rejections |
|---|---|---|
| guard on result (before) | **3** | 2 |
| guard on promise (after) | **1** | 0 |
| init throws (after) | **1** | all callers see it |

### D82 — an effect must handle rejection, or it becomes an unhandled promise

`AppShell` did `void auth.init().then(…)`. A rejection therefore surfaced as an *unhandled promise
rejection* — the `(in promise)` in the console — and, worse, `ready` was never set, so the shell sat
on "Loading…" indefinitely with no route to a sensible screen.

It now `.catch`es, logs, and **fails closed to `/login`**. Failing closed is the deliberate choice:
an adapter that cannot initialise has no session to vouch for, and treating that as "signed in"
would render a shell full of links that all 401.

The effect also returns a `cancelled` flag. StrictMode's mount/unmount/remount cycle otherwise calls
`setState` after unmount and logs a warning — harmless on its own, but it trains you to ignore
warnings, which is how the real problem stayed hidden.

### What these three console bugs have in common

| Bug | Failure mode | Why no test caught it |
|---|---|---|
| `check-sso` never settles | white page, no error | the promise simply never resolved |
| `process.env` at module scope | white page, one ReferenceError per file | build and typecheck both pass |
| unguarded re-`init()` | unhandled rejection, console usable | needs two concurrent callers in a browser |

All three are **delivery and lifecycle** problems. The theme engine, the API, the SQL and the
5,679 backend tests were all green throughout — none of them execute a browser, and that is
precisely where every one of these lived.

Two process changes are now standing:

1. **Load the page.** Any phase is not done until someone has opened it.
2. **Front-end work gets a browser.** The checks in these appendices are all HTTP fetches of HTML,
   CSS and served modules — useful, and still not a browser. Any check that asserts "the app works"
   should be an actual load, and until a browser is attached to the development session that check
   has to be done by hand.

---

## Appendix S — UI kit ported from the reference storefront

`packages/ui` is now the reference project's own kit — its `@doko/shared` component set — rather
than the handful of primitives written in Phase 0. **The reference at
`~/Developer/web-storefront` was read only.** Files were copied out of it; nothing in it was
modified, and `git status` there shows only changes that predate this session (timestamps from
Oct 2, a day before it began).

### What the reference actually uses, measured rather than assumed

Scanned across its 198 source files:

| Primitive | files importing it | | Primitive | files |
|---|---|---|---|---|
| `button` | 80 | | `select` | 5 |
| `card` | 36 | | `dialog` | 5 |
| `input` | 30 | | `sheet` | 4 |
| `label` | 23 | | `table` | 4 |
| `badge` | 15 | | `avatar`, `checkbox` | 3 |

So its UI is overwhelmingly plain shadcn. Its own bespoke `Card/`, `Skeleton/`, `Dropdown/`,
`MenuItem/`, `Spinner/`, `ConfigProvider/` and `progressiveBar/` are used in **two, one, one, zero,
zero, zero and zero** files respectively. The port therefore brings in the shadcn set as the design
language to match, and keeps the bespoke set as `legacy-*` rather than promoting it.

### The usage count was wrong the first time, instructively

The first pass counted which components the reference's *application* files import and concluded
`MenuItem` and `Spinner` were dead. `Dropdown` imports `MenuItem`. A count taken from outside the
dependency graph misses the graph entirely — the drop list was then re-checked by **compiling**,
which is what caught it. Counting imports is not a substitute for building.

### Two components with the same name

`Card` and `Skeleton` each exist twice in the reference — once as shadcn, once as its own variant.
The shadcn one keeps the plain name, because that is what 36 reference files and this project's own
pages both import. The variants are `LegacyCard` and `LegacySkeleton`.

`Card/` also collided *by case* with `card.tsx`: on a case-insensitive filesystem those are the same
path, and TypeScript refuses to include both in one program (`TS1261`). Renamed to `legacy-card/`
rather than dropped, which keeps both components.

### Adaptations required, and why each was necessary

The kit targets React 18, Tailwind v3 and a looser tsconfig. This project is React 19, Tailwind v4,
`verbatimModuleSyntax` and `noUncheckedIndexedAccess`. Every change is mechanical and commented at
its site:

- **Type-only imports** (`verbatimModuleSyntax`): 5 files.
- **`useRef` needs an initial value** in React 19: 2 hooks.
- **`noUncheckedIndexedAccess`**: `input-otp`'s slot read is guarded and returns `null` rather than
  asserting; `shallowEqual`'s key is narrowed once; `Card`'s optional flags are coerced.
- **Dependencies are now declared.** Radix (18 packages), `cmdk`, `input-otp`, `motion`, `recharts`,
  `react-hook-form`, `react-resizable-panels`, `lodash`, `classnames`, `react-icons` and
  `react-use-measure` are real dependencies of `@store-app/ui`. **The reference's shared package
  declares none of them** — it relies on its two apps to hoist them, so a component there importing
  `recharts` only works because some app happens to depend on it. That is a missing dependency
  waiting to surface as a blank screen.
- **`@shared/*` became relative paths**, via an explicit layout map. The reference resolves them
  through a TS alias with no build step, which does not survive being consumed as a workspace
  dependency. The first attempt guessed paths and produced 29 unresolvable imports; the mapping is
  now written down in `scripts/rewrite_shared_imports.py`.

### D84 — Tailwind was never scanning `packages/`

This is the one that would have made the whole port look broken while everything "passed".

Tailwind v4 detects sources automatically, starting from the directory of the CSS file that pulls
in Tailwind — which here is `apps/customer` or `apps/console`. **Nothing in `packages/` was ever
scanned**, and the entire UI kit lives there.

The failure is silent and total for anything used only in the kit. Measured before the fix:

| Class | Used only in | CSS generated |
|---|---|---|
| `hover:shadow-lg` | `packages/ui/src/ui/card.tsx` | **0 rules** |

Every card in the app would have rendered with no hover elevation, and the eight overlay components
would have had no animations. Nothing errors; the classes simply do not exist.

Fixed with explicit `@source` directives in each app's entry CSS. The compiled stylesheet went from
**16 KB to 87 KB**, and `hover:shadow-lg` is now generated.

### D85 — three things had to move into each app's Tailwind entry

`@theme` is a Tailwind at-rule, not plain CSS, and an `@import` in a workspace package's stylesheet
resolves against the *consuming app* — where pnpm's isolated `node_modules` does not expose the
package. Both fail silently. Measured:

| Thing | In `packages/ui` | In the app entry |
|---|---|---|
| `@keyframes caret-blink` | compiled ✓ | — |
| `--animate-caret-blink` (binds it) | **not compiled** | compiled ✓ |
| `tw-animate-css` utilities | **not resolvable** | present ✓ |

So the animation import, the caret binding and the colour `@source` list all live in each app's
`globals.css`, where they take effect. `packages/ui/styles.css` keeps the plain custom properties,
which survive the pipeline unchanged.

Corner radius was a false alarm worth recording: `--radius-lg` never appears in the output because
`@theme inline` **substitutes** the value into utilities rather than emitting the variable. The
mapping was working all along — `.rounded-lg { border-radius: var(--radius) }` — and it is what
makes a theme's `radius` token reach every rounded corner.

### The rule that costs the most: `*/` inside a comment

A path like `apps/*/src/**/globals.css`, written inside a CSS comment, **closes the comment early**.
The rest of the line is then parsed as CSS and the stylesheet fails to compile with
`Syntax error: Unknown word` — a message that points nowhere near the cause.

This cost several iterations. It is now avoided by writing `apps/<app>/src/app/globals.css` in
prose, and the file is checked with a real PostCSS parse rather than a brace count, which is what
misled me twice (`grep -c` on minified CSS counts *lines*, and a hand-rolled brace counter
mis-strips block comments).

### Verified in the compiled CSS both apps actually receive

| | storefront :3000 | console :5173 |
|---|---|---|
| stylesheet | 87 KB | 84 KB |
| `animate-in`, `fade-in-0`, `zoom-in-95` | ✓ | ✓ |
| `slide-in-from-top`, `exit-*` | ✓ | ✓ |
| `bg-card`, `hover:shadow-lg` | ✓ | ✓ |
| `.rounded-lg` → `var(--radius)` | ✓ | ✓ |
| `--animate-caret-blink` | ✓ | ✓ |
| theme token applied | `--primary:#7c3aed` | ✓ |

All 10 packages typecheck and both apps build. The theme editor was migrated to the ported
components' real APIs — Radix `Select` with `SelectGroup`/`SelectLabel`/`SelectTrigger` rather than a
native `<select onChange>`, and a `Badge variant="outline"` with a dot, because the ported `Badge`
has no `warning` variant and inventing one would make our kit diverge from the reference.

---

## Appendix T — the console's role mapping, and why it 403'd on a valid token

Signing in as `ops.test@storeapp.local` produced a valid Keycloak token containing
`"realm_access":{"roles":["SHOP"]}` — and every guarded route still redirected to `/forbidden`.

### D86 — realm roles are mapped to authorities per realm, not compared directly

`APP_ROLE` holds Spring Security authority names (`ROLE_SHOP`, `ROLE_ADMIN`, `ROLE_CUSTOMER`).
`readRoles()` was filtering the token's realm roles against those values **directly**:

```ts
realmRoles.filter((r) => Object.values(APP_ROLE).includes(r as AppRole))
```

`'SHOP'` is not `'ROLE_SHOP'`, so the filter matched nothing, `readRoles()` returned `[]`, and
`RequireRole` found no role and redirected. The token was valid; the console simply could not read
it.

The backend already had this right. `KeycloakJwtAuthenticationConverter` maps explicitly, per
realm, rather than blanket-prefixing:

```java
"ops"       -> { "ADMIN": "ROLE_ADMIN", "SHOP": "ROLE_SHOP" }
"customers" -> { "CUSTOMER": "ROLE_CUSTOMER" }
```

Deliberately a **map rather than a prefix**: a prefix would let `ops.SHOP` and a same-named role in
any other realm both resolve, and a role must not be able to cross realms. The console now mirrors
that table in `REALM_ROLE_MAP`.

Verified against the real declarations in `adapter.ts` — not a copy that could drift:

| | realm roles `['SHOP']`, realm `ops` |
|---|---|
| old filter | `[]` → every guarded route 403s |
| new mapping | `['ROLE_SHOP']` → passes |

Cross-realm containment holds: `ops` maps `SHOP`; `customers` and any unknown realm discard it.

### The comment predicted this bug and shipped anyway

`APP_ROLE` carried this warning:

> *"The backend is the authority; these names must match the `ROLE_*` values the resource server
> produces. A mismatch shows up as a 403 rather than a compile error…"*

The mismatch was exactly that, and it was still there. A comment describing a failure mode is not a
fix for one — and this is the **fourth** console bug in a row that no test caught, because every
one of them only appears when a real browser runs the code.

`readRoles()` now also warns loudly when a token has realm roles that map to nothing:

> `[auth] none of the realm roles ["SHOP"] map to an authority in realm "ops". Known roles: … If
> this is a role mismatch with the backend, every page will redirect to /forbidden.`

A silent role mismatch is the hardest kind of auth bug to debug, because the token looks perfectly
valid in the browser and every API call from that token works. The warning names the cause.

### What this class of bug costs

| Bug | Symptom | Why nothing caught it |
|---|---|---|
| `check-sso` never settles | white page | promise never resolved; no error |
| `process.env` at module scope | white page | build and typecheck pass |
| unguarded re-`init()` | unhandled rejection | needs two concurrent callers in a browser |
| **role filter vs authority name** | **403 on a valid token** | **token looks right; API calls succeed** |

The last is the nastiest. A shop owner signs in, the token is populated, every backend call works
— and the console shows "you do not have access to this area", which reads as a permissions
problem in the platform rather than a string comparison in the browser. It needs a browser to find
and a real login to reproduce, which is why it took four attempts.

Standing process, now stated three times across appendices R, S and T: **a frontend change is not
verified until someone signs in and lands on a real page.**

---

## Appendix U — Phase 4: orders board and product list, replicated from the reference

Both pages built against the screenshots in `web/.ui-reference/`, with **pagination added to every
list table** — the reference has none, and a table that renders every row stops rendering at a few
hundred. Backend suite: **5,673 tests green**.

### D87 — pagination is part of the table component, not something each page adds

`packages/ui/data-table.tsx` owns it. Every list here grows without bound — orders, products,
audience, customers — and making each page remember to paginate is how the reference ended up with
unpaged lists.

Three decisions:

- **The page is a controlled prop, not internal state.** The URL carries it, so a bookmarked or
  shared link opens on the same rows, and Back steps through pages actually visited.
- **The range is shown, not just arrows.** "Page 3" does not tell an owner whether to go back or
  forward; "Showing 41–60 of 218" does.
- **Page size is selectable** (10/20/50/100), because an owner with 40 pages of orders does not
  want to click next thirty times.

### D88 — orders: the board defaults to what still needs the owner

`GET /api/v1/shop/orders` returns the page **plus the per-status counts** that label each filter
tab, in one request. Seven counts as seven separate queries is seven round trips to render one row
of buttons.

The default view excludes `DELIVERED`, `CANCELLED`, `REFUNDED` and `RETURNED`. Someone opening
the board is asking "what needs me", and last month's delivered orders are not it. The tabs still
reach everything.

Search covers order number, customer name, phone, email and address in **one** native query —
the fields live in two tables (`app_user` for a signed-in customer's own name), so four derived
queries would need four round trips and a merge.

Sort keys are **whitelisted**. An unvalidated sort property reaching `Pageable` is a 500 from the
database on a column that does not exist.

### D89 — the stock column shows *available*, not on hand

`quantity_on_hand - quantity_reserved`. The table answers "what can I still promise?", and showing
reserved stock as sellable is how an oversell happens. Summed across a product's variants, because
the table has one Stock column; the product screen shows the per-variant breakdown.

### D90 — a live product cannot be toggled from a table row

The reference's table has a status switch. Ours renders it **disabled**, and that is deliberate:
publishing requires a priced variant and a verified shop, and those rules belong on the product's
own screen where they can be explained. A toggle in a table that silently refuses is worse than one
that is visibly not interactive.

Bulk selection is scoped to the **visible page**, and the UI says so. A "select all" that covered
rows the owner cannot see is how bulk actions hit things nobody chose.

### The silent-SSO bug, and what it cost

Signing in worked; **refreshing logged you out**. `sessionStorage` was empty after every reload.

Root cause was my own Phase 3 fix. I removed `onLoad: 'check-sso'` because it hung forever — it
opens a hidden iframe at `silentCheckSsoRedirectUri` and waits for a callback, and **the console
served no such page**, so the promise never settled and the console was white.

Fixing the hang by dropping `onLoad` entirely fixed it by **never authenticating at all**: no
tokens, nothing in `sessionStorage`, signed out on every reload. The reference avoids this because
it ships `frontend/public/silent-check-sso.html` — which is just `parent.postMessage(location.href,
location.origin)`. The adapter running in the parent reads the tokens from the message; the page
needs no Keycloak code of its own.

Both halves are now in place, and `check-ui.mjs` asserts the callback page is served and contains
the `postMessage`. It is a four-line file that is easy to delete as unused, and there is no error
at all until someone tries to sign in.

### `RequireRole` was deciding before auth had answered

It read `getSession()` synchronously on first render, which returns `{authenticated: false}` until
`kc.init()` resolves. Harmless on a soft navigation — init had already run — but on a full reload
it redirected a signed-in owner straight to `/login`. The symptom was "the dashboard works when I
click into it, but refreshing bounces me out."

It now distinguishes **"still loading"** from **"loaded, and the role is wrong"**. Collapsing those
two states is what produced the bug: there is no difference between "not signed in yet" and "not
signed in" until init has actually answered.

### Two more bugs the screenshots caught

- **Every page was titled "Dashboard".** The top-bar title used the first nav item whose path
  *prefix-matched*, and `/shop` matches both "Dashboard" and the `/shop/orders` prefix. Longest
  prefix wins now.
- **A self-referencing Vite proxy.** Documenting `/silent-check-sso.html` in `server.proxy`
  pointed it at the same server it was served from, so it 500'd. The file is in `public/` and
  needs no proxy entry at all.

### What is verified, in a real browser

```
ORDERS    h1=Orders    rows=2     tabs=7   pagination=yes   Showing 1–2 of 2
PRODUCTS  h1=Products  rows=20    pagination=yes   Showing 1–20 of 1,011
```

The orders board shows real data: two accepted COD orders, `Ravi KC`, Rs. 900.00, status
coloured by state. The product table pages over 1,011 rows at 20 per page.

### Not yet built

The order **detail** page and the **add-product form** — both screens have references in
`.ui-reference/`, and both are straightforward now that the shell, the table and the API exist.

---

## Appendix V — Phase 4: order detail and the add-product form

Two screens closed out the Phase 4 core. Both were built against references in `.ui-reference/`,
and both turned up more bugs in the surrounding code than they did in themselves.

### `authorizedFetch` had the wrong shape for every write

The helper takes `(adapter, url, init)`, not `(adapter, url, body, method)`. Every write call site
had been written against the shape it seemed like it should have, which is a type error three times
over — the compiler caught all of them, but the fix is worth stating once:

```ts
export async function postJson(
  adapter: AuthAdapter,
  url: string,
  body: unknown,
  method: 'POST' | 'PATCH' | 'PUT' | 'DELETE' = 'POST',
): Promise<Response>
```

It lives beside `authorizedFetch` in `packages/auth`. Three reasons it earns its place:

- `method`, `headers` and `JSON.stringify` cannot be forgotten independently. Getting any one of
  them wrong fails as a confusing HTTP 400 rather than a type error.
- A caller cannot send `[object Object]`.
- Endpoints that take **no** body — the order transitions — still pass `{}` explicitly. `postJson`
  always serialises a body, so omitting the argument is a type error instead of a silent mismatch.

### Case-sensitive substring matching decided which step the form showed

`validate()` returned English strings. Choosing which step to jump to then meant
`problem.includes('name')` — which is **case-sensitive**, and none of `'Product Name is required'`,
`'Product Category is required'` or `'Product Description is required'` contains a lowercase `name`
or `category`. Every lookup missed.

The result was not a subtle one. Submitting the empty form listed four problems and then **navigated
to the Inventory step**, hiding the four fields that were actually empty. The owner was told what was
wrong in a block at the bottom of a screen that no longer showed the inputs.

Validation now returns structured problems:

```ts
interface Problem {
  step: string;                  // which step owns it
  field: 'name' | 'category' | 'price' | 'stock' | ...;
  message: string;               // shown to the owner
}
```

The step is data, not prose. English can be reworded, capitalised or translated without changing
which screen the form lands on. Each problem in the summary is also a button that jumps to its step.

This is the same failure class as matching on a status **label** instead of the status: identity
must never be carried in text that is meant for humans.

### A `<label for>` pointed at a `<button>`

The Radix `Select` trigger is a `<button role="combobox">`. Buttons are **not labelable elements**,
so `<label htmlFor="product-category">` associates with nothing: clicking the label does not open the
dropdown, and the trigger has no accessible name.

The visible label is real and must stay real. It gets an `id`, and the trigger points back at it:

```tsx
<Field label="Product Category" labelId="product-category-label" required>
  <Select value={category} onValueChange={setCategory}>
    <SelectTrigger aria-labelledby="product-category-label">
```

`aria-label` would have worked and was the first thing tried, but it **silently overrides the
visible text**. The label the owner can see is the one the control should be named by; if they ever
disagree, the sighted owner and the screen-reader user are being told different things.

`Field` now distinguishes `htmlFor` (real labelable inputs) from `labelId` (composite widgets).

The same pass added the missing `htmlFor`/`id` pairs for every other field. Before this, `getByLabel`
found **nothing** on the form — no input had an accessible name, so a screen reader announced
"edit text, blank" with no hint of which field it was.

### The dropdown was empty because the property was named wrong

The server returns `CategoryOptions(shopType, allowed, inUse)`. The form read `meta.categories`.

`meta?.categories ?? []` made a wrong property name indistinguishable from a shop that has no
categories: an empty listbox, no error, no warning. Fixed to `allowed`, and an empty list now renders
a disabled `No categories for {shopType} shops` row. **A list that cannot be empty must not render as
one that is.**

The categories come from the same validator the write path enforces (D18), so the shop is only ever
offered the categories its type permits. The grocery shop behind `ops.test` is offered 10:
`Rice, Lentil, Spices, Oil, …`

### `noUncheckedIndexedAccess` versus `found[0].step`

After the refactor the compiler objected that `found[0]` might be undefined, even though
`found.length > 0` had just been checked. This one is left as the language intends: the compiler is
right that it cannot prove the invariant, and destructuring

```ts
const [first] = found;
if (first) { setStep(first.step); return; }
```

is both honest and no worse to read. It is not a rule to satisfy — it is the checker declining to
take an assertion.

### Verified in a real browser

```
empty submit      refused, 1 problem shown, stays on Product Information
categories offered 10 -> Rice, Lentil, Spices, Oil
after submit      path=/shop/products   page errors: none
back on the board Showing 1–20 of 1,012   product present: true
```

Both pages were also walked end to end through `check-ui.mjs`: **ALL PAGES OK**. Ten packages
typecheck; both apps build.

### One test-script error worth recording

Two of the failures above were in the check script, not the app, and both are the same trap:

- `getByLabel('Product Name')` with a **plain string** is an *exact* match. The label reads
  `Product Name*` (the required asterisk is rendered inside it), so it matched nothing and timed out.
  `getByLabel(/Product Name/)` works. Use regexes in these scripts.
- Adding `role="alert"` to each field error made `getByRole('alert')` **ambiguous** and throw, which
  `.catch(() => null)` then reported as "no alert found". Target `.first()` on a locator instead.

The recurring lesson: a check that silently reports "not found" when the thing it looked for is
ambiguous is worse than no check, because it reads as a pass.

### Still open

- **Product media** — the dropzone is laid out and honest about not uploading yet. Signed
  browser→Cloudinary uploads (D40) are the next pass; the `public_id` retention rule (D19) applies
  from the first version, because deletion cannot be added afterwards.
- **Receipt button** — disabled, with a title saying invoices arrive with Phase 4 reporting. It is
  never a button that silently does nothing.
- Reference pages still wanted from the user: Audience, Settings, Appearance.

---

## Appendix W — Phase 4 completed: the shop can run its day without an admin

The phase-4 exit criterion is *"a shop runs its entire day-to-day operation without admin
intervention."* This appendix records what closed that gap, and — more usefully — the six real bugs
the work turned up, five of which were silent.

### What was already there, and what was not

The Phase 1 baseline schema anticipated nearly all of Phase 4: `shop_setting`, `discount`,
`shop_payment_method`, `customer`, `order_number_sequence` and the eSewa credential columns all
existed. What did not exist was **any Java for them**. Discounts had a domain entity and a
quote-time engine but no owner-facing CRUD; settings, audience and payment configuration had no
entity, no service and no endpoint; the `openpdf` dependency was present and entirely unused.

So the shape of the work was narrower than the phase list suggests:

| Item | State on arrival | Added |
|---|---|---|
| Discount system | entity + quote integration done | owner CRUD, validation, console |
| Media library | full API done | console screen |
| Invoice PDF | nothing (dependency only) | `InvoiceService`, endpoint, console |
| Shop settings | table only | 8 typed sections, service, 8 endpoints |
| Audience | table only | entity, service, list, notes, CSV |
| Payment config | table only | entity, cipher, service, endpoint |
| Bulk transitions | nothing | service + endpoint |
| Low-stock notification | `/stock/low` only | crossing detection, outbox event |
| Printable/receipt | nothing | invoice PDF |

### `REQUIRES_NEW` cannot see the caller's uncommitted writes

`BulkTransitionService` needs each order to commit or roll back on its own, so a single refused
order cannot undo the ones that worked. The obvious implementation is `REQUIRES_NEW` per order.

It is wrong, and the test suite is what showed it:

```
An order cannot go from PENDING to PROCESSING…   ← no, it said "not found"
```

Every order came back as though it did not exist. `REQUIRES_NEW` **suspends the caller's
transaction**, so the inner one cannot see the outer one's uncommitted writes. In production the
caller has no transaction, so it appeared to work; under a test transaction the fixtures were
invisible. This is the same lesson `OrderNumberService` records at length — where the fix was to
*join* the caller's transaction rather than escape it.

The resolution inverts the usual advice:

```java
@Transactional(propagation = NOT_SUPPORTED)   // the batch: no transaction at all
public BulkResult transition(...)             // so each call opens its own
```

and each single transition is plain `@Transactional`. Same per-order commit, one connection at a
time instead of one per order, and it works under a test transaction because there isn't one.

**A `REQUIRES_NEW` that works in production and fails in tests is not a test artefact. It is a bug
that only has one customer.**

### `shop_setting`'s order-number column could break checkout

`shop_setting` rows are created lazily with `order_number = '{}'`. An owner who opened the settings
screen and saved **any** tab therefore produced a row whose order number deserialised to a `null`
format — and every subsequent checkout threw a `NullPointerException` from
`OrderNumber.render`:

```
NullPointerException: Cannot invoke "String.replace(…)" because "this.format" is null
```

The shop became unable to sell because someone edited their support email.

The fix is a compact constructor on `OrderNumber` that defaults `format` and `next`, which makes
an empty section indistinguishable from an absent one — the invariant the entire read path assumes
and which only held for *missing* sections. `ShopSettingService` already logged and degraded on
unparseable JSON; `{}` is perfectly parseable, which is why it slipped through.

There is now a named regression test for it.

### Saving the settings 500'd, then 405'd

Two separate failures, both in `postJson` usage:

- **`postJson` defaults to `POST`; the settings endpoints are `PUT`.** The server answered
  `HttpRequestMethodNotSupportedException: Request method 'POST' is not supported`, surfaced to the
  owner as "The server rejected this (HTTP 405)". The 405 was the server being *correct* and the
  client being wrong, in a way the error text could not explain.
- **The "Saved" banner never appeared**, because saving invalidated the settings query, which
  refetched, which changed the draft fingerprint, which reset `saved` in the effect. The
  confirmation appeared and vanished in the same tick. Fix: `set` clears `saved` (the owner edited
  again), the effect does not (the server confirmed).

### `Page<T>` meant two different things

`client.ts` exports `Page<T>` as a **cursor** page (`items`, `nextCursor`, `hasMore`).
Phase 4's endpoints return **offset** pages (`content`, `page`, `totalPages`). Adding a second
`Page<T>` made `import { Page } from '@store-app/api-client'` ambiguous for every caller, and the
compiler's fix — an explicit re-export — has to be repeated at each import site, which is the
arrangement where one eventually gets it wrong.

The new one is `OffsetPage<T>`. Two shapes, two names.

### The audience summary projected `Long` sums into `int` fields

`/audience/summary` returned 400 for every request:

```
select coalesce(sum(c.totalSpentMinor), 0), coalesce(sum(c.totalOrders), 0), count(c)
```

`SUM` and `COUNT` both return `Long`. Coalescing them against an `int` literal `0` is a type
mismatch, and projecting them into `int` record fields fails exactly when the numbers get large —
which is the shop this screen matters most to. Every field is now `long` and the fallbacks are `0L`.

### Two checks that lied

Both are in this appendix because a check that reports "not found" when it actually failed is
worse than no check — it reads as a pass.

- `findByShopIdOrderByLastOrderAtDescNullsLast` does not exist as a derived query. Spring Data
  reads `NullsLast` as a **property name** and fails at startup. The one sort that matters most on
  the audience screen — never-ordered customers last, not first — needed a hand-written `@Query`.
- A service test signed in with a `UsernamePasswordAuthenticationToken`. `CurrentUser` reads the
  principal *as a `Jwt`* and returns null for anything else, so every call looked anonymous and
  `TenantGuard` answered "This account does not own a shop" for all of them. And the JWT `sub` must
  be the owner's **Keycloak subject**, not their database id: `IdentitySyncService` resolves by
  subject and creates a *new* user when nothing matches, producing a second user who owns nothing.
  The symptom points at the shop, not at the token.

### Two deliberate API choices worth defending

**Discounts have no delete, only pause.** Deleting resets `usage_used`, so a campaign redeployed
next season would let every customer who already used it use it again. The endpoint is `DELETE` in
the path and a pause in the body, and the response carries the reason.

**The audience CSV omits private notes.** An export is the artefact most likely to be emailed or
pasted into a spreadsheet. An owner's private annotation about a real person has no business
travelling that way. It is also capped at 10,000 rows, and the response says so in
`X-Export-Truncated` — a silently shortened customer list is worse than no export, because the owner
would reconcile against the wrong number.

### Money never crosses a float boundary

Every Phase 4 amount is a `bigint` of paisa end to end. Three places where that is load-bearing:

- **Discount values.** `PERCENT` is stored in basis points. A percentage taken as a raw `10` and
  scaled somewhere downstream is how "10% off" becomes 1000% or 0.1%.
- **CSV.** Names are quoted through a helper, because `Sharma, Priya` unquoted shifts every value
  after it onto the wrong customer.
- **The invoice.** Amounts come from the order's frozen snapshot, never from the current catalogue,
  so a later price change or product rename cannot rewrite an already-issued invoice.

### Secrets are write-only

`SecretCipher` is AES-256-GCM with a random 96-bit nonce per encryption, stored in front of the
ciphertext. GCM rather than CBC because the key protects money-routing instructions: a bank account
number that decrypts to a *different* account number sends someone's payment elsewhere with nothing
to notice it, and CBC would return plausible bytes for a modified ciphertext.

The API accepts `esewaSecret` and returns `hasSecret: true`. **A console that is never sent a
secret cannot leak it** — not into a network log, a React state dump, or a screenshot of the
settings page. Saving with the field blank keeps the stored value; forgetting it is a separate,
two-step, irreversible call.

With no `STORE_SECRETS_KEY` configured the cipher **refuses** and names the variable. Generating a
random key would lose every secret on restart; shipping a default in the jar would mean every
deployment shares one. The settings screen disables the fields and says why.

### Verified

```
backend      5,770 tests, 0 failures, 0 skipped (was 5,673 at the start of Phase 4)
frontend     10/10 packages typecheck; console + customer build
check-ui.mjs ALL PAGES OK
browser      5 screens render, 0 console errors
             discount created, listed, and an impossible cap refused by the server
             settings saved and survived a reload
             eSewa refused to be enabled half-configured
             invoice downloaded through the UI as %PDF-1.5
             bulk selection and partial-result toast verified
```

### Phase 4 reference screens the user still owes

Four screens had no screenshot in `.ui-reference/` (Audience, Settings, Discounts, Media) and were
built to the existing console shell's tokens and primitives, on the user's instruction. They are
consistent with the referenced screens but are not verified against them.

---

## Appendix X — two faults hiding each other in the theme editor

A final sweep of all nine console screens — rather than only the ones I had just changed — turned
up a crash on the Appearance page that had been there since Phase 3. Two independent bugs, and
**either one alone would have hidden the other.**

### The shared API client never sent a token

`packages/api-client`'s `request()` built its headers, set `Accept`, set `Content-Type` and sent
it. It never set `Authorization`. Every authenticated endpoint reached through `api` therefore
answered 401.

This was invisible for a long time because almost nothing used `api` for protected routes — the
Phase 4 screens all use `authorizedFetch` from `@store-app/auth`, which does attach the token. The
one place that used `api` was the theme editor, whose failure mode is to render an empty page
rather than an error. So "the appearance page shows nothing" looked like a theme problem for two
phases.

The fix is an explicit seam rather than a hard dependency:

```ts
let tokenProvider: (() => Promise<string | null>) | null = null;
export function setTokenProvider(provider: (() => Promise<string | null>) | null): void
```

A **function**, not a token string. The Keycloak access token expires and must be refreshed on
use; registering the string once means every request after the first refresh carries an expired
token, which presents as a page that worked for ten minutes and then started 401ing.

Left unset, requests are anonymous — which is correct for the SSR storefront (public endpoints, no
interactive session) and for the shop-type and category pickers.

### The renderer called a setter on its parent mid-render

With the theme page finally loading a real theme, it crashed immediately:

```
Maximum update depth exceeded
  at ThemeRenderer (packages/theme-engine/src/render.tsx)
```

`ThemeRenderer` had an `onIssues` prop and invoked it **in its render body**. That is `setState` in
the parent while the child renders, which React rejects outright. Worse, `parseTheme` returns a
*fresh* `issues` array each time, so the parent's new state re-rendered the child, which called
`onIssues` again — an unbounded loop.

The tempting fix is to move the call into a `useEffect`. **That would have been wrong**, because the
file's own contract is at the top of it:

> A pure server component: … No fetching, no hooks, no effects — which is what makes the page
> indexable and share-previewable (D1)

Adding a hook to satisfy a prop would have quietly broken the reason the renderer exists: the
editor preview and the live storefront call the *same function*, and a hook would have made them
diverge the moment one of them rendered differently.

So `onIssues` was **removed**. A component cannot both report and stay pure; reporting belongs to
the caller. The editor — a client component, where hooks are fine — now parses the same document
with the same exported `parseTheme` and reads the issues itself:

```ts
useEffect(() => {
  if (!draft) return;
  setIssues(parseTheme(JSON.stringify(draft)).issues);
}, [draft]);
```

The editor's list and the storefront's inline `<ThemeIssues>` notice therefore cannot disagree
about what is wrong, and `ThemeRenderer` is pure again. The prop is gone rather than deprecated,
because a prop that is illegal to use correctly is a trap.

### The lesson, which is the transferable part

Neither bug was findable by testing the thing you changed. Both were found by **listing every
screen and looking at all of them**, including the ones untouched for two phases.

And the failure was *compositional*: the 401 meant the renderer was never reached with a real theme,
so the loop could not fire. Fixing the 401 exposed the loop. Had the second fix reintroduced an
existing behaviour — which the "just move it into an effect" instinct would have done — the
loop would have returned silently and nobody would have known why.

Also worth recording: the sweep initially reported the dashboard as failing because it tested
`/shop/dashboard`. That route does not exist; the dashboard is `/shop`. A check with a wrong URL
produces a confident false negative, which is the same failure mode as the "no alert found" case in
Appendix V.

### Final state

```
backend      5,770 tests, 0 failures, 0 skipped
frontend     10/10 packages typecheck; console + customer build
check-ui.mjs ALL PAGES OK (storefront + console)
9/9 console screens render with zero console errors, zero 4xx/5xx
```

---

## Appendix Y — eSewa, and the documentation that lies

Phase 5's exit criterion says a customer can "order via eSewa". This appendix records the single
most misleading piece of vendor documentation encountered in the project, and what was actually
verified.

### Three of eSewa's four published examples are wrong

eSewa's developer pages publish four worked signature examples. Recomputing all four shows that
**three do not match the algorithm the same pages describe**:

```
HMAC-SHA256 over bare values      "110241028EPAYTEST"        → GaTJxAh6ob/…   ≠ published
HMAC-SHA256 over key=value pairs  "total_amount=110,trans…"  → i94zsd3oXF6Zs…  = published ✓
```

The third — the one the user's own integration notes carried — is the only correct example on the
page. An implementer who copies the page faithfully writes a gateway that fails **every** payment
with:

```json
{"code":"ES104","message":"Invalid payload signature."}
```

and the documentation gives them nothing to work with. The two other examples (one on the algorithm
page, one in the demo form-data) fail against both schemes, so they cannot be rescued by guessing
a different one.

### The scheme, once established

```
message   = "total_amount=100,transaction_uuid=241028,product_code=EPAYTEST"
signature = base64( HMAC-SHA256( message, merchantSecret ) )
```

The message is **comma-separated `key=value` pairs** in the order named by `signed_field_names` — not
the bare concatenation of the values. That single distinction is the whole bug, and the stale
examples obscure it precisely because they look like the concatenation.

### Verified live, not read off a web page

```
POST https://rc-epay.esewa.com.np/api/epay/main/v2/form   signed  → HTTP 200, eSewa's login page
GET  .../api/epay/transaction/status/?transaction_uuid=…    → {"status":"PENDING", …}
```

The second call is the important one: eSewa reports the transaction **it** created for **our**
signed form. That is ground truth, and it is why `EsewaSignatureTest` pins the expected value
rather than merely asserting the function returns a non-empty string.

The form and the status API also live on **different hosts** in UAT (`rc-epay` and `rc`). The status
path under `rc-epay` answers 404, which is a thoroughly confusing way to discover that.

### What is *not* verified, and why it does not put money at risk

A completed payment needs eSewa's Angular login, whose submit button stays disabled under
synthetic input events. That is a browser-automation limit against a sandbox, not an integration
defect.

It also does not matter, because of how the flow is arranged:

> **An order is marked paid only by a server-to-server status query. Never by the browser redirect.**

The redirect's signed payload is an optimisation — it lets the return page render immediately
instead of waiting on a round trip. `EsewaGateway.verify()` ignores it entirely and asks eSewa
directly. So a wrong guess about the response scheme costs a customer a slower page, never a
shipped order. `EsewaSignature.verifyResponse` is written to the documented scheme and asserts the
structural properties that protect the order (tampering, a narrowed signed-field list, a foreign
secret) rather than a published signature value that nobody can reproduce.

### The response also signs `signed_field_names`

The request signs three fields. The **response** signs five values plus the field list *itself* —
six parts, eleven comma-separated tokens. Omitting that last field is the most likely way to write
a verifier that rejects every genuine response, so it is called out in the code and in the test.

### Secrets

`SecretCipher` (Phase 4) holds both merchant credentials. The API accepts `esewaSecret` and returns
`hasSecret: true` — **a console that is never sent a secret cannot leak it**. Saving with the field
blank keeps the stored value, because a masked form that sends back an empty string would otherwise
delete a working credential on every save.

### State after this step

```
backend  5,798 tests, 0 failures, 0 skipped
eSewa    signature verified against the live sandbox; status API verified
not verified  a completed sandbox payment (sandbox login resists automation)
```

---

## Appendix Z — Phase 5, part 1: the two credentials, and what they actually bought

Phase 5's two external integrations are now live against real services rather than stubs. This
records what was verified, what was not, and the parts of the customer portal that are still to do.

### eSewa (D11) — verified against the sandbox, not against documentation

See Appendix Y for the signature discovery. Summary:

```
POST rc-epay.../v2/form          signed with our algorithm  -> HTTP 200, eSewa's login page
GET  rc.../api/epay/transaction/status/                     -> {"status":"PENDING", ...}
```

The status call reports the transaction **eSewa itself** created from **our** signed form, which is
ground truth. That is why `EsewaSignatureTest` pins the exact expected value.

Not verified: a *completed* payment. That needs eSewa's Angular login, whose submit button stays
disabled under synthetic input. It is a sandbox-automation limit, and the design means it cannot
cost anything: **an order is marked paid only by the server-to-server status query**, never by the
browser redirect. `EsewaGateway.verify()` never looks at the redirect.

The shop's credential stays per-shop and encrypted (`shop_payment_method.esewa_secret_enc`), with
the sandbox secret only as a fallback so the integration works with no setup.

### Firebase — verified by *deliberately* failing

A push sender that "did not throw" proves nothing. `PushSenderLiveIT` sends to a **fabricated
token**, because the error is the evidence:

```
Push to fcm-token-th… failed: FirebaseMessagingException:
  The registration token is not a valid FCM registration token
```

That is FCM saying *"I authenticated you, I accepted your request, and this token does not exist."*
An invalid credential would instead fail with an authentication error — so the assertion
distinguishes a wired integration from a silently-broken one.

### Three things worth recording about push

**Registration is public, and that is deliberate.** A service worker can only obtain a token after
the page loads, which is usually *before* anyone signs in — a first visit to a storefront is
exactly when a token arrives. Requiring auth would move registration onto the account page, which
customers never visit, and order-status push would then reach only people who had already found
their way to settings. It is not a privilege escalation: an unattached token belongs to nobody and
the fan-out query is keyed by **user**, so it can never be sent anything until a customer claims it.
`SecurityConfig` permits `POST /api/v1/customer/devices` and nothing else on that path.

**A push is never the only delivery route.** A customer who has push blocked, no device registered,
or is offline still sees the same state in the app. So the sender never propagates a failure — a
failed push must not fail the order transition that caused it — and the notification worker catches
anything that escapes anyway, because it runs inside the consumer and a requeue would re-send the
email to a customer who already has it.

**`customer_device` is keyed by token, with no surrogate id.** A token identifies an *install*, not a
person: it is regenerated when site data is cleared and an install cannot be "logged out". Two rows
for one token would send every push twice. The first draft of the table had both an `id` primary key
and a `token` primary key, which Postgres rejects with *"multiple primary keys ... are not
allowed"* — the constraint that exists precisely to stop that.

### Schema: four new tables (V10, V11)

| Table | Key decision |
|---|---|
| `customer_address` | one default per customer, enforced by a **partial unique index**, not just a boolean |
| `customer_device` | keyed by FCM token; cross-realm via `app_user` |
| `product_review` | one per person per product; `author_name` survives account deletion |
| `wishlist_item` | `UNIQUE (user_id, product_id)` |

`product_review` keeps `author_name` and `is_verified` after the user row is gone: a rating is a
statement about a product, and deleting it because someone deleted their account loses the shop's
social proof. `is_visible` hides rather than deletes — moderation is not erasure, and an owner who
changes their mind should get the review back.

### Still to do in Phase 5

Deliberately not attempted in this pass, and none of it is stubbed or faked:

- customer account: order history, tracking timeline, reorder
- addresses CRUD (table done, no service or controller)
- reviews and ratings (table done, no service or controller)
- wishlist (table done, no service or controller)
- search (the trigram and full-text **indexes exist** from Phase 1; the query layer does not)
- gallery browsing by product category — the D17 payoff
- Nepali localisation scaffolding
- the customer portal UI (the app is still five files: a gallery and a storefront)

---

## Appendix AA — Phase 5, part 2: the customer's half

The customer portal's back end. What follows is the four decisions that were not obvious, because
each of them is a place where the convenient choice is the wrong one.

### Cross-shop search is not shop-scoped search with the clause removed

Phase 1 built the trigram and full-text **indexes** and a shop-scoped query for shop owners. What
was missing was the customer's question: "where can I buy paracetamol?" — which spans shops they
have never visited and, by D17, is how a customer finds *all* pharmacies rather than one at a time.

`PublicSearchRepository` adds three cross-shop queries, and the shape is deliberately **shop-first**:

```
GET /api/v1/p/search?q=paracetamol
  → { shops: [ {pharmacy, 1 match}, {grocery, 1 match} ], products: [...] }
```

A customer searching a medicine wants to know *which pharmacies* sell it, not to scroll forty
listings from one shop. So the shop-level result is the primary one and the products are the detail
behind it — returned in the same response, because two round trips to draw one screen is how a
search box feels slow.

Only `ACTIVE` products in `VERIFIED` shops, enforced **in SQL** rather than in Java, so a retired
product cannot appear by way of a filter the service forgot. That is asserted directly: the test
creates a live-looking product in an unverified shop — which `ProductService` refuses to publish — by
inserting the row directly, precisely to prove the search filter does not leak it.

### `citext` is the reason a native projection needs a cast

`shop.slug` is `citext`, and the PostgreSQL driver returns it as a `PGobject`, not a `String`. With a
derived query Hibernate knows the column type from the entity mapping and converts; with a native
query it does not, and the failure is:

```
Cannot project org.postgresql.util.PGobject to java.lang.String
```

which names neither the column nor the projection. The fix is `s.slug::text AS slug` in the SQL,
with a comment saying why — because the obvious reading of that cast is "redundant, remove it".

The same query uses an **interface projection** rather than `select new SomeRecord(...)`. A record
constructor over a native query binds positionally by exact JDBC type and fails with "argument type
mismatch", again naming neither cause. An interface maps by column alias and lets Hibernate convert.

### A wishlist stores products, not variants

A customer who taps the heart on "Sona Masoori Rice — 5kg" has saved the *product* and will pick a
size at checkout. Pinning the variant makes the row undeletable once that variant is retired, and
turns a list of things they might buy into a list of SKUs they once looked at.

`wishlist_item` is `(user_id, product_id)` and nothing else.

Thumbnails are resolved **one query per shop on the page**, not one per tile — a customer's wishlist
spans shops and media is scoped to one, so grouping is unavoidable, but it is bounded by the number
of shops rather than the number of items. The JPQL projection cannot carry the image at all:
`product` has no image column, images are media rows, and joining them would multiply the rows for
any product with more than one image.

### Review averages are summed in SQL, over the whole set

A product card shows "4.3 from 128" on every page of the catalogue. An average computed from the
twenty reviews that happened to fit on screen would be wrong for exactly the products with a
reputation worth having. `RatingSummary` therefore also has a `summariesFor(ids)` variant, because
the per-card version is a hundred aggregate queries for a hundred products.

"Verified purchase" means **a real order contained this product**, not "the reviewer is signed in".
The distinction is the entire value of the badge — a competitor can sign in and review, and without
it an owner cannot tell a customer's review from a competitor's.

### Four audit columns, three migrations

`BaseEntity` maps both `created_at` and `updated_at`. V10 created four tables with `created_at`
only, so each was caught by Hibernate's schema validation at boot:

```
missing column [updated_at] in table [customer_device]
missing column [updated_at] in table [wishlist_item]
```

V11 and V12 add them. Not edits to V10 — it has already run on two databases, and changing an
applied migration breaks Flyway's checksum. Each migration says so at the top, because the instinct
on the second occurrence is to go back and fix the original.

### `customer_device` is keyed by token, and Postgres said so

The first draft declared both `id` and `token` as primary keys:

```
ERROR: multiple primary keys for table "customer_device" are not allowed
```

A token identifies an *install*, not a person — it is regenerated when site data is cleared, and an
install cannot be logged out. So the token is the key, there is no surrogate, and two rows for one
token would send every push twice.

### Scope

Every customer endpoint is scoped by **customer and shop**. Order numbers are allocated per shop, so
`ORD-0001` exists in every shop, and a customer lookup that ignored the shop would let a crafted
link to `/orders/ORD-0001` return another shop's order — including its delivery address. Another
customer's order answers **404**, not 403, because "forbidden" confirms the number exists, which is
the only thing an enumeration attempt needs.

Reorder **re-quotes** rather than replaying. Each line is looked up again and priced today, and lines
that can no longer be bought come back with a reason instead of being dropped: a reorder that
quietly loses the milk the customer wanted is worse than one that says the milk is gone.

---

## Appendix AB — the customer portal's front end, and four faults that hid each other

The customer app was five files. It is now a working portal, and getting there exposed a chain of
bugs where **each one concealed the next**.

### The chain

1. **No `/api` proxy.** Client-side `fetch('/api/v1/customer/wishlist')` hit the Next server, which
   has no such route, and answered **404**. Every account page appeared to work and every request
   failed identically. `rewrites()` in `next.config.ts` sends `/api/:path*` to the backend — the
   same origin the console uses through Vite.

2. **No `silent-check-sso.html`.** The adapter sets
   `silentCheckSsoRedirectUri: ${redirectUri}/silent-check-sso.html`, the console serves that page,
   and the customer app did not. keycloak-js opened the iframe, got a 404, nothing ever posted
   back, and `init()` sat unresolved for **four seconds** — which is why the sign-in button looked
   permanently disabled rather than briefly loading. One four-line file, copied.

3. **Two Keycloak adapters.** The shell built one with `createKeycloakAdapter`, and the login page
   built another. Both were internally consistent and neither knew about the other, so
   `isAuthenticated()` on the shell's instance said "no" while the page's said "yes". The symptom
   was a Sign-in button that never enabled and a header that never showed account links — with **no
   error anywhere**. Fixed by a single shared adapter in `lib/auth`, and the comment in that file
   now explains the failure rather than just asserting the rule.

4. **`isAuthenticated()` asked before `init()` had resolved.** It is `init'd && authenticated`, so
   asking first always answers false. The shell now awaits `initAuth()` and — the part that matters
   — wraps it in `try/finally`, because a *rejected* init (deliberately cached, so a retry cannot
   call `kc.init()` twice) otherwise left the header rendering a skeleton forever with no way
   forward.

Two of these were only visible *because* an earlier one was fixed. Fixing the 404s revealed the
silent-check failure; fixing that revealed the double adapter.

### `login(returnTo)` was sending an unregistered URI

```ts
await kc.login({ redirectUri: returnTo ?? config.redirectUri });   // before
```

`returnTo` looks like a path, and Keycloak validates `redirect_uri` against the client's registered
list — so passing one fails **every time** with `Invalid parameter: redirect_uri`, leaving the
customer on a blank page with a Keycloak error rather than signed in.

The destination is now stashed in `sessionStorage` (not a query parameter — it would otherwise
persist into browser history and Keycloak's logs) and the registered URI is always sent.
`takeReturnTo()` clears on read and refuses anything not starting with a single `/`, so a stored
absolute URL cannot turn the login link into an open redirect.

### A shared package should not need the other app's variables

`@store-app/auth` exports `consoleAuthConfig` **and** `customerAuthConfig`, so importing anything from
it evaluated both. The customer app's build failed with `Missing required env var:
VITE_KEYCLOAK_URL` — a variable it has no use for.

Both configs are now **getters**, which defers each `env()` read to first property access. The object
shape is unchanged so no call site moved, and a genuinely missing variable still fails hard — which
is the point: a production deployment must never silently inherit a development Keycloak URL. That
hard failure is also why `apps/customer/.env.local` now exists; `.env.example` said to copy it there
and its comment ("the app boots without this file") was only true of the dev server.

### Prerendering is not optional

`next build` statically renders `/account/*`, and a component calling `useSearchParams` or
`useParams` has no value to read during that pass — the build fails with `prerendering page
/account/orders` rather than at runtime. Each route wraps its client component in `Suspense` with a
skeleton fallback, so the page also does not visibly jump once real content arrives.

### Where it stands

```
backend    5,810 tests, 0 failures, 0 skipped
frontend   10/10 packages typecheck; console + customer build
check-ui   ALL PAGES OK
browser    orders, wishlist, addresses, login, shell — 0 console errors, 0 4xx/5xx
```

---

## Appendix AC — Phase 6: subscriptions and revenue (backend)

Phase 6's back end, completed and tested. The exit criterion was: *a shop upgrades, its commission
drops to 0% and paid templates unlock, the ledger records the charge, and expiry restores the free
tier correctly.* Those four clauses are four named tests in `SubscriptionLifecycleIT`.

**Scope note:** backend only, as asked. The shop subscription screen and the admin revenue dashboard
have no front end yet — the endpoints are live and documented, and the UI is Phase 6's remaining half.

### The free tier is data, not "no rows"

The decision that shapes everything else. Entitlements resolve in two layers:

```
materialised shop_entitlement rows   (copied in on activation)   ← shadows
FREE plan's plan_entitlement rows    (the baseline)             ← falls through to
```

Representing the free tier as "no rows" — the obvious thing — means a shop that subscribed once and
lapsed has its 25-product cap deleted along with its paid entitlements, and can then list **without
limit**. Lapsing would make a shop *more* capable. The test asserts this directly:

```java
assertThat(entitlementService.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS)).hasValue(25L);
```

Keeping FREE as a real plan with real rows also means raising the free cap is a migration rather than
a code change, and a fresh database cannot disagree with a migrated one about what "free" means.

### Cancelling does not revoke anything before the paid period ends

`cancel()` records the *intent* not to renew and leaves `status = ACTIVE`. The shop keeps its
entitlements until `expires_at`, and only then becomes `CANCELLED`.

A shop that paid for 31 days and cancels on day 3 has bought 28 more days of commission-free trading.
Revoking on the spot makes cancellation a silent refund, and makes "try it for a month" a trap. D7's
manual model has no way to express a pro-rata refund, so the honest version of "stop immediately" is
not available — and pretending otherwise would be worse.

`EXPIRED` and `CANCELLED` are kept apart because they answer different business questions. "How many
shops left" is not "how many subscriptions ran out", and collapsing them would answer the first with
the second.

### The sweep is a cleanup, not the enforcement point

Every read tests the grant's *own* expiry, and `findRunningByShopId` refuses to call a lapsed row
current. If the scheduler never runs, a subscriber still loses access the moment their window closes.

The asymmetry is deliberate:

| failure | consequence |
|---|---|
| sweep never runs | stale rows; a briefly wrong `commission_plan`; self-corrects |
| enforcement lived in the sweep | **every lapsed subscriber keeps working, indefinitely** |

`shop.commission_plan` is the one thing that does drift, because `OrderService.accept` reads it
without running a subscription query per order. It is a derived cache, so it is set only through
`grantCommissionWaiver()` / `revokeCommissionWaiver()` rather than by assignment.

### Nine bugs, and the chain that found them

Every one of these was found by a test written *after* the code looked finished.

1. **`subscription_id` silently never written.** `WalletService.debit` is transactional, so the entry
   it returns is **detached** by the time the caller called `attachSubscription` on it. Every
   subscription charge landed in the ledger with a null `subscription_id` — un-reconcilable against
   MRR, and invisible to any test that only checked the amount. Fixed with a dedicated
   `debitSubscriptionCharge` that sets the link *before* the insert, rather than a nullable parameter
   on `debit` that nine other call sites would have to pass `null` to.

2. **`forSubscription` called after approval.** It refuses to re-point a request that is no longer
   `PENDING`, so **every single approval** threw "This top-up has already been APPROVED". The link
   belonged at submit time, which is where it already happened.

3. **`expire()` used to close a subscription on a plan change.** It deliberately refuses to close one
   that has not lapsed — that guard stops a clock skew from revoking a paid period. So every mid-term
   upgrade died on `uq_shop_subscription_one_active`, an index naming neither the cause nor the fix.
   Added `closeForPlanChange`, which records `CANCELLED` because the shop moved rather than lapsed.

4. **`Instant.plus(1, ChronoUnit.MONTHS)`** — "Unsupported unit: Months". An Instant has no calendar to
   walk through. Month arithmetic belongs on a `LocalDate`.

5. **A signed ledger sum returns negative for debit-only types.** `Money.ofMinor(-40000)` then threw
   "amount cannot be negative", which reads as a money bug rather than a sign convention buried in a
   query. Replaced with `sumByTypeAndDirection`, unsigned, direction explicit; the subtraction that
   makes commission net is now a visible line in code named for it.

6. **"Upgrade to Free (Rs. 0.00)"** — `upgradePathFor` offered the FREE plan, because FREE grants
   `max_products`. A free shop hitting its cap was told to buy the plan it was already on, in the one
   message it would read carefully. Now filtered to paid plans.

7. **404s that were 500s.** `NoSuchPlanException` and `NoSuchSubscriptionException` both documented
   "Maps to HTTP 404" from the day they were written, with no handler anywhere. Spring turned them
   into 500s.

8. **A rejected plan was still created.** `create` flushed the plan row and *then* validated its
   entitlements, so a typo'd feature key returned 400 and left a committed plan that grants nothing.
   Fixed by validating the whole draft **before the first insert** — correct ordering, and
   independent of whether the controller is proxied for `@Transactional`.

9. **A paying shop could not upgrade.** `request()` refused *any* second plan while one ran, so a
   monthly subscriber who decided to go annual had to wait out the month — and would say so in a
   support message. Split by direction: an **upgrade** (costs more) is allowed immediately; a
   **downgrade** is refused, because switching early hands the shop a cheaper plan's entitlements for
   a period already paid for.

### One thing the tests got wrong, twice

The product cap is "**at most** 25", not "fewer than 25". I asserted twice that a shop holding
exactly 25 could add a 26th. Both times the code was right.

The other correction was mine in the source, not the test: I had written that an archived product
stops consuming the allowance. It does not — `archive()` hides a product but leaves it live and
un-archiveable, so freeing the cap on archive would let a shop archive all 25, create 25 more,
un-archive, and hold 50. Only `delete()` frees it, and that is refused once a product has sales.

### Schema

`V13__subscriptions_and_entitlements.sql`: four tables plus two columns on existing tables
(`wallet_ledger_entry.subscription_id`, `wallet_topup_request.subscription_id` / `plan_id`).

Constraints verified against the live database rather than assumed:

| constraint | what it prevents |
|---|---|
| `subscription_plan_paid_must_expire` | a paid plan with no `duration_days` — its commission waiver could never end |
| `subscription_plan_price_matches_period` | a priced FREE plan, which MRR would count as revenue |
| `uq_shop_subscription_one_active` | two ACTIVE rows → two commission waivers, and which one a reader found first would be the answer |
| `uq_shop_subscription_one_pending_per_plan` | a double-clicked button being charged twice |
| `*_disabled_has_no_limit` | "no custom CSS, up to 3 of them" |

Plus the recurring one: `BaseEntity` maps `created_at` and `updated_at`, and every new table needs
both. V10 and V12 exist for that reason; V13's tables had it from the start.

### What is deliberately absent

- **No `DELETE` on plans.** Withdrawn with `is_active = false`: a cancelled subscriber's history must
  still name its plan, and deleting cascades away the entitlements it granted.
- **No `renewals` table.** D7 has no recurring charging, so a renewal is a new subscription.
- **No plan version column.** Editing a live plan is *refused* while an ACTIVE subscriber holds it —
  a stronger guarantee than a version column nobody updates. Display-only edits (name, description,
  sort order, withdrawal) are still allowed, because refusing them would make the pricing page
  unmaintainable exactly while it is in use.
- **No currency column.** One country, one currency, `price_minor` in paisa like everything else.

### Money

The credit and the charge net to zero by design. The shop transfers to the platform; the platform
credits the shop's wallet so it has a balance to pay its subscription from; the subscription charge
takes it back. It looks redundant in isolation, but it means every rupee the platform takes from a
subscriber is a ledger row with a reason, rather than a bank statement reconciled by hand against a
table of subscriptions. The ledger is the audit trail; the bank is the settlement.

The amount is **not** a request parameter — it comes from the plan, so a shop cannot ask to be
charged one plan's price and be granted another. Stronger than validating a number it supplied.

### On the revenue dashboard

Every figure names its denominator, because a ratio without one means something different on every
screen it appears on: `subscriptionArpu` (per subscriber), `platformPerTradingShop` (per verified
shop, counting commission *and* subscriptions), `commissionPerSubscriber`.

MRR **divides an annual plan by twelve**. Counting its full price as one month reports a twelvefold
jump every January, and a dashboard that does that is a dashboard nobody trusts by the second one.

Two commission figures, because there are two true ones: **realised** (what the ledger debited) and
**waived** (what orders did *not* charge because the shop was subscribed). Reporting only realised
makes a working subscription programme look like falling revenue — which is exactly the mistake that
leads to a platform killing the thing retaining its shops.

The waived figure is measured on **orders, not the ledger**: a waiver writes no ledger row, because no
money moved. The frozen order figures are the only place the forgone amount is recorded.

### Test discipline

Every ledger assertion is a **delta**, not an absolute. The test database carries committed rows from
seed data and earlier runs, so `assertThat(topUps).isEqualTo(500_000)` fails depending on what a
previous run left behind — the worst property a test can have. Each test captures a baseline and
asserts the change it caused, which is also a stronger statement.

One test bug worth recording: I passed `UUID.randomUUID()` as a ledger entry's `orderId` to look
realistic, and got

```
ERROR: insert or update on table "wallet_ledger_entry" violates foreign key constraint
       "wallet_ledger_entry_order_id_fkey"
  Detail: Key (order_id)=(8d8fdbf2-…) is not present in table "shop_order".
```

The foreign key was doing exactly its job. `null` is the honest value.

### Status

### A tenth bug, this one mine, and the regression it caused

The paid-template gate was the last thing wired, and it broke **six existing theme tests**:

```
java.lang.IllegalArgumentException: 'Test' is not offered to a Pharmacy shop.
  at ThemeService.installTemplate(ThemeService.java:222)
```

I had gated installation on `template.isOfferedTo(type, subscribed)`, which bundles three
conditions — *published*, *tier*, *type*. Installing a **draft** template is exactly what the
preview flow does, so the gate refused it. Split the one condition that is actually about the shop
into `suitsShopType(type)`, and left publication to the marketplace listing and the tier to the
entitlement.

Worth recording because the failure looked like my new code being wrong when it was my new code
understanding the wrong question. The existing tests were right.

### Status

```
backend   5,901 tests, 0 failures, 0 errors, 0 skipped   (was 5,810; +91)

Phase 6 backend   complete
  6 new test classes, 91 tests:
    SubscriptionPlanTest          16  unit     — plan validity, MRR normalisation
    ShopSubscriptionTest          16  unit     — the state machine and the money rules
    SubscriptionLifecycleIT       21  jdbc     — the four exit-criterion clauses
    EntitlementEnforcementIT       7  jdbc     — product cap, paid-template install
    RevenueServiceIT             16  jdbc     — MRR, commission, denominators
    SubscriptionControllerIT     15  mvc      — authorisation boundaries

  enforced where it matters: product cap on createDraft, tier on installTemplate
  authorisation: admin-only at class level (D36), shop derived from the token,
                  cross-tenant answered 404
  no front end yet — endpoints are live and documented; the shop subscription
  screen and the admin revenue dashboard UI are the remaining half of Phase 6
```

---

## Appendix AD — Phase 6 front end: four screens, and the flow that was never finishable

The back end was complete and the purchase button was on screen. What it could not do is *work*:
D7's flow needs an admin to approve, and there was **no screen for an admin to approve anything**.
The shop could queue a plan, transfer money, submit proof — and then nothing, forever.

That is the kind of gap that typechecks, builds, and passes every unit test, because each half is
correct on its own.

### Four screens, on the existing shell and components

| screen | route | what it is |
|---|---|---|
| Plan & billing | `/shop/billing` | current plan, features, pricing, purchase, history |
| Wallet approvals | `/admin/wallet` | the top-up queue, approve/reject with a required reason |
| Revenue | `/admin/revenue` | MRR, commission (realised *and* waived), ratios, trend |
| Plans | `/admin/plans` | plan CRUD, entitlement editor, withdraw |

Reused rather than rebuilt: `DataTable` with its pagination, `Card`, `Dialog`, `Badge`,
`Checkbox`, `Input`, `Skeleton`, the console's `authorizedFetch`/`postJson`, `lib/format`'s money
helpers, and `AppShell`'s existing nav and `ROUTE_TITLES`. Nothing new was styled from scratch.

### Two approval endpoints, and picking the wrong one fails silently

```
POST /admin/wallet/topups/{id}/approve             → credits the wallet
POST /admin/subscription-payments/{id}/approve     → credits, charges, activates, grants
```

A queue that showed only the list would send every subscription to the first. The shop's bank
statement matches, its balance goes up, and it has **no subscription** — which reads as a successful
payment from both sides. So `QueueItem` now carries `isSubscription`, `planCode` and `isPlanPrice`,
and the row is routed accordingly and labelled so the difference is visible *before* the click.
`isPlanPrice` is asserted even though `forSubscription` already guarantees it: a mismatch is the
failure this field exists to prevent, so surfacing it in the queue beats discovering it at approval.

### Four bugs, three of them found by looking at a screenshot

**1. The MRR chart drew no bars.** Month labels, a correct "Peak Rs. 2,000.00" caption, and an
empty plot area. `height: N%` resolves against the parent's height, and a `flex items-end` container
sizes its children to *content* — so each column collapsed to the height of its own label and every
percentage was taken against zero. Now pixels, against a named `CHART_PX`.

**2. The test counted the columns, not the bars.** `[role="img"] > div` reported "6 bar(s)" on a page
that had rendered none, and the journey script went green. It now counts `[data-testid="mrr-bar"]`
*and* asserts the tallest bar is taller than a stub — `tallest 141px of 6`. A count of elements that
exist is not evidence that they have size.

**3. Reloading lost the payment step.** It lived in `useState`, set only after a successful request.
A shop that requested a plan and then refreshed came back to a history table reading "Awaiting
payment" with no amount to send and no reference to quote — the flow stuck, with no error. The step
is now derived from `me.history`: a pending row carries `amountMinor`, `amountDisplay` and
`reference`. **The screen survives a reload**, and that assertion is in the journey script.

**4. An admin signing in landed on `/forbidden`.** The root index hardcoded `<Navigate to="/shop">`.
The login *button* used `homeRouteFor` and got this right; Keycloak's redirect does not return to the
button, it returns to `redirectUri` — the root. Two entry points, two answers, and the one Keycloak
actually uses was the broken one. The index is now role-aware via a `Landing` component.

Also fixed, pre-existing: `AppShell` fetched `/shop/dashboard` for **every** authenticated user, so
every admin page logged a 403 it could do nothing about. It is now gated on `ROLE_SHOP`. A 403 in the
console on every admin screen is background noise that hides a real 403 the day one appears.

### The cancel button says what it does

It reads **"Keep my plan until 8 Nov 2026"**, not "Cancel". That is exactly what happens — cancelling
records the intent and the period already paid for runs to its end. A button labelled "Cancel" on a
paid subscription reads as immediate revocation, which is the one thing it is not, and an owner who
believes that will not press it.

### Denominators on the revenue screen

Every ratio's label states what it divides by: *subscription ARPU* is MRR ÷ paying subscribers;
*per trading shop* is MRR **+ commission** ÷ verified shops, counting both because a free shop pays
commission and a subscriber pays a subscription. The window is printed under the heading — "since the
start of the current month, except MRR which is a live figure" — rather than implied.

Realised and waived commission sit side by side, and waived is tinted. Reporting only realised makes
a working subscription programme look like falling revenue.

### The journey, driven through the UI

`scripts/check-phase6-journey.mjs` signs in as a shop owner, requests a plan, reads the quoted
amount and generated reference, reloads to prove the step survives, submits payment, switches to the
admin, approves from the queue, and then re-reads the dashboard:

```
SHOP: starts on commission (5%) · amount and reference quoted · survives a reload · nothing changed yet
ADMIN: lands on /admin not /forbidden · approves from the queue
NUMBERS MOVE: subscribers 0 → 1 · MRR Rs. 0.00 → Rs. 2,000.00 · 6 bars, tallest 141px
SHOP SEES IT: No commission · a running plan · history shows Running
zero console errors, zero 4xx/5xx
```

### Scope left out, deliberately

- **The MRR trend is O(shops × subscriptions)** and fine at this size; a large marketplace would want
  it materialised. Said in the card's own description.
- **No plan reordering drag-and-drop.** `sortOrder` is editable in the dialog; a drag handle for three
  plans is a feature nobody asked for.
- **No invoice for a subscription.** D7 has no recurring charging, so a renewal is a new subscription
  rather than a charge on an old one, and an invoice format for it would be inventing a document.

### Status

```
backend   5,905 tests, 0 failures, 0 errors, 0 skipped   (+4: the queue item's routing fields)
frontend  10/10 packages typecheck; console builds
check-ui  all pages OK (1 pre-existing storefront failure: a remote image URL
          in the dev database pointing at vecteezy, unreachable — unrelated)
check-phase6          all 5 pages, zero console errors, zero 4xx/5xx
check-phase6-journey  JOURNEY OK — the whole D7 flow through the UI

Phase 6   COMPLETE
```

---

## Appendix AE — Phase 7 foundation: the audit log, and a deadlock that taught me something

The two things Phase 7's exit criterion rests on, built before the admin features rather than after.

### `audit_log` already existed. From Phase 1. And was wrong.

V2 declared `audit_log`. Nothing ever used it — no entity, no service, **no rows in any database**. So
V14 replaces it, and says why in the migration:

```
RAISE EXCEPTION 'audit_log has % row(s) but no code has ever written to it. Refusing to drop real records.'
```

The first version of V14 was `CREATE TABLE IF NOT EXISTS`, which **silently did nothing** — the table
existed with `bigserial` id, `actor_role`, `ip inet`, and no `summary` or `reason`. The entity then
failed schema validation against a table I thought I had written. `IF NOT EXISTS` on a table whose
shape you have not checked is a way to believe you have migrated something you have not.

What changed and why:

| was | now | why |
|---|---|---|
| `bigserial` id | `uuid` | an append-only log's id should not be a sequence that reveals volume and hands out ids |
| `actor_role` | `actor_roles` | a record should preserve every authority in force at the time |
| `ip inet` | `ip_address text` | the recorder does not read it yet, and `inet` has no clean Java mapping |
| *(none)* | `summary text NOT NULL` | a list of blank descriptions is a list nobody can scan |
| *(none)* | `reason text` | "an admin suspended a shop" without a reason helps nobody |
| `shop_id` | **kept** | Phase 1 was right — "everything that happened to this shop" is a one-column index scan, not a string match |

### `REQUIRES_NEW` is not free, and it made the log a liar

The recorder originally ran in its own transaction with swallowed exceptions, so an audit failure
could never take down a suspension. Two things wrong with that:

**It deadlocks.** `REQUIRES_NEW` suspends the caller's transaction and takes a **second connection**.
The audit row has foreign keys to the shop and the admin being acted on, so inserting it needs a
shared lock on rows the suspended transaction holds exclusively and has not committed. The inner
write waits for the outer commit; the outer commit waits for the caller; the caller waits for the
inner write. Every test in `AuditTrailIT` hung — not slowly, not with a timeout, just *nothing* for
thirty minutes. Three parties in a circle, each politely waiting.

**It makes the log lie.** A separate transaction commits the audit row whether or not the action
commits. So a suspension that rolls back leaves a record saying a shop was suspended, and one that
succeeds can lose its record. The log stops being evidence.

So it joins the caller's transaction (`REQUIRED`) and lets failures propagate. **Failing closed is
correct for an audit log**: "we could not record it, so we did not do it" is defensible; "we did it
unrecorded" is the failure Phase 7 exists to prevent.

### Audit at the service, not the controller — which the tests caught

I first wired auditing into `AdminShopController` and `AdminWalletController`. Four tests failed
because they call the *services* directly and recorded nothing.

The tests were right and the wiring was wrong. A service is the only layer every caller passes
through; recording in a controller makes the log complete for HTTP and silent for everything else —
so "every privileged action lands in the audit log" would be true of the admin UI and false of the
platform. A scheduled retry, a future support tool, a CLI script: all invisible.

The audit calls now live in `VerificationService`, `WalletTopUpService` and
`SubscriptionPaymentService`. The controllers were stripped back, because recording in both places
writes two rows for one action and **a log with duplicates in it is a log nobody can count**.

### A closed action vocabulary, for the same reason `FeatureKey` is one

`AuditAction` is an enum, so an action nobody classified is a compile error rather than a string
nothing matches. Each value knows its `group()` (what the operator filters by), its `verb()` (how a
summary reads), and whether it `requiresReason()`.

`WALLET_ADJUST` is deliberately its own action rather than a top-up variant: it is the only way to
change a balance without money arriving, so it is the first thing an investigator looks for.

### The expiry sweep is an actor

The hourly sweep records `SUBSCRIPTION_EXPIRED` with a **null human actor** and `actor_email = 'system'`.
A subscription can be revoked with nobody having touched it, and an investigator looking at a shop
that lost its commission needs to *read* "the scheduler did this" off the log, not infer it from a
missing row. Human and machine actions have to be separable or the log answers half its questions
with a shrug.

### Platform settings, and a commission rate that can finally be changed

`CommissionCalculator.DEFAULT_RATE_BP = 500` meant the platform's single most consequential number
could not be changed by anyone entitled to change it. It is now a seeded setting, **seeded at 500** —
byte-for-byte what the constant was — so moving it from code to data changes no behaviour and no
historical order. That is what makes it provably safe rather than probably safe.

`OrderService.accept` reads the rate at the one moment commission is decided and freezes it onto the
order, so a rate change affects orders accepted afterwards and **never** one already accepted. There
is a test for exactly that, because it is the way this feature could quietly cost real money.

Refusals are specific rather than generic, because each one is a different absurd outcome:

| input | refused because |
|---|---|
| `-1` bp | negative commission pays a shop to sell |
| `10001` bp | takes more than the order is worth; produces negative order totals |
| `"five percent"` | would silently fall back to 5% while the screen says otherwise |
| `"rupees"` | a currency is an ISO code |

### The jsonb trap, twice

Both new entities hit it: Hibernate cannot infer a JDBC type for a `String` on a jsonb column and
fails at boot with `wrong column type encountered in column [value] ... expecting varchar(255)` — a
message that reads like a migration mistake rather than a missing `@JdbcTypeCode(SqlTypes.JSON)`.
Recorded in V14's note so the fourth occurrence is not a surprise.

And the third appearance of the `BaseEntity` trap (V11, V12, V14): every entity extends `BaseEntity`,
which maps `created_at` **and** `updated_at`. Included here even though this log is append-only and
nothing will ever update a row — so the column will equal `created_at` forever, which a reviewer
should read as "nothing has ever updated a row". That is the point of an append-only log.

### One bug worth recording

`rawValue` was `.map(Function.identity())`, left over from chasing a compile error — so it returned
the whole `Setting` entity. Every reader then called `String.valueOf(...)` on it, got
`com...PlatformSettings$Setting@134b2645`, failed to parse that as a number, and fell back to the
default. **Every read returned 500 and every write appeared to do nothing** — which is the worst
failure mode a settings screen can have, because it looks like the feature is not taking effect
rather than like a bug.

### Status

```
backend  6,083 tests, 0 failures, 0 errors, 0 skipped   (+67 since Phase 7)

Phase 7 COMPLETE — foundation, admin features, and ten admin screens
  V14  audit_log (replaces the Phase 1 declaration, with a guard)
       platform_setting seeded for every key the code reads
  V15  shop.is_featured  (described in Phase 1, never actually created)
  V16  app_user.roles   (there was nowhere to ask "is this an admin?")
  audit    AuditAction (closed vocabulary, groups, verbs, reason rules)
           AuditLog (append-only, only changed fields in before/after)
           AuditTrail (REQUIRED, fails closed)
           AuditLogRepository (no update, no delete method)
           AdminAuditController (filtered, paginated, per-entity, summary)
  settings PlatformSettings (typed keys, validated writes, defaults)
  kpis     KpiRepository (aggregate SQL, JDBC), KpiService,
           AdminPlatformController (windowed, every ratio names its denominator)
  shops    ShopAdministrationService (feature/unfeature, filtered browse),
           Shop.feature()/unfeature() — audited, with the diff recorded
  users    UserAdministrationService (both realms, suspend needs a reason,
           admins cannot be suspended), AdminSupportController
  reports  ReportService (7 reports, CSV rendered from the same rows,
           formula-injection-safe and numbers left as numbers)
  support  SupportService (notification history; the email log is the same
           rows with channel=EMAIL, not a second table)
  explorer DataExplorerController (the live routing table), DataExplorerProbe
           (a real HTTP call back in, forwarding the caller's token),
           OpenApiConfig
  ui       admin-dashboard · admin-shops · admin-users · admin-audit ·
           admin-reports · admin-explorer · admin-support
  wired    VerificationService, WalletTopUpService,
           SubscriptionPaymentService, SubscriptionExpiryJob, OrderService
  tested   AuditTrailIT 15 · PlatformSettingsIT 15 · AdminPlatformIT 18 ·
           UserAdministrationIT 15 · ReportServiceIT 14 ·
           SupportServiceIT 14 · DataExplorerIT 10 · AdminAuditQueryIT 10

frontend 10/10 packages typecheck
browser  check-phase7.mjs — 7 screens, nav reachability, a live probe (HTTP 200),
         a diff view opening. PHASE 7 UI OK.
         check-phase6.mjs ALL PAGES OK · check-ui.mjs: 1 pre-existing failure
         (a remote vecteezy.com image in the dev DB, unreachable, in no source file)
```

## Appendix AF — Phase 7 complete: the admin platform, and four bugs that looked like something else

Phase 7's foundation (Appendix AE) gave the platform an audit log and a settings store. This
appendix records the admin features built on top of them, and — more usefully — the four failures
that each presented as a completely different problem than the one they were.

### What was built

| Area | What it does | Where |
|---|---|---|
| KPI dashboard | GMV, orders, commission, ARPU ×2, churn, active shops, buyers, revenue split by shop type | `KpiService`, `KpiRepository`, `admin-dashboard.tsx` |
| Shop management | Filter by type/status/featured, search, feature and unfeature — audited | `ShopAdministrationService`, `AdminShopController`, `admin-shops.tsx` |
| User management | Both Keycloak realms in one list, suspend with a required reason, reinstate | `UserAdministrationService`, `AdminSupportController`, `admin-users.tsx` |
| Reports | Sales, products, shops, types, taxes, payouts, ledger — JSON and CSV from the same rows | `ReportService`, `AdminReportController`, `admin-reports.tsx` |
| Audit log | Append-only list with a field-level diff | `admin-audit.tsx` |
| Data explorer | The live routing table, browsable; admin `GET`s only | `DataExplorerController`, `DataExplorerProbe`, `admin-explorer.tsx` |
| Support tooling | Notification history and the email log, with retries and errors | `SupportService`, `admin-support.tsx` |

Two migrations: `V15__featured_shops.sql` (the `is_featured` column the plan described in Phase 1
but which was never created) and `V16__app_user_roles.sql`.

### Four bugs, each of which pointed somewhere else

**1. A misplaced annotation, reported as a type error.** Adding `roles` to `AppUser` by inserting
before `@Column(name = "status")` put it *between* the `@Enumerated` above that column and the
column itself. 634 tests failed with:

```
Property 'AppUser.roles' is annotated '@Enumerated' but its type 'java.lang.String' is not
```

Nothing in that message points at the annotation sitting two lines above. The field looked fine in
review — it was, syntactically. Lombok's `@Getter` stopped being generated across the whole
module at the same time, which is what made it look like a build problem rather than an edit.

**2. A documented Spring Data shape that does not register.** `KpiRepository` began as a plain
interface whose every method was a `@Query`. Spring Data does not scan it — documented, but real —
and the application failed to start with `NoSuchBeanDefinitionException: KpiRepository`. The
documented fix is `extends Repository<KpiRepository, Void>`, which was tried and also did not
register. Rather than keep guessing at a scanner's acceptance rules, it became a `@Repository`
class over `NamedParameterJdbcTemplate`: no scanning question at all, and the right shape for
aggregate SQL that has no entity behind it.

**3. A verb-case mismatch that made the feature work perfectly and do nothing.**
`SAFE_METHODS.contains(method.toLowerCase())` against `List.of("GET")` is always false. So the data
explorer marked *every* operation as unsafe — the screen rendered perfectly, showed the whole
contract, and offered nothing to run. Nothing threw. The only reason it was caught is a test that
asserted `runnableCount() > 0` rather than one that asserted the page loaded.

**4. A correct-looking CSV export that would have broken every spreadsheet.** The obvious
formula-injection guard prefixes any cell starting with `= + - @`. That includes `-250` — so every
refund, every debit, every negative balance in a financial export would have been written as text,
and the file would no longer open as numbers. The fix is to parse rather than pattern-match:
`Double.parseDouble` already knows what a number is, and a formula never parses as one.

### Decisions worth recording

**Featuring is editorial, not an entitlement.** `is_featured` is a flag on the shop and is
deliberately absent from `plan_entitlement`. A slot a shop can buy is an advertisement; calling it a
feature would be dishonest about what it is. The plan and the prominence are different things, and
merging them would let subscription money buy editorial judgement.

**Churn is activity, not subscription.** A shop that stopped receiving orders has churned whether
or not it still pays for a plan. Counting only subscription lapses would report a marketplace full
of dormant shops as healthy — they are all still paying for something they no longer use.

**The summary tiles ignore the filters.** "Of everything in the last 30 days, how much failed" is
the question someone opens the support screen with. A summary that moved as the filters changed
would describe the current selection instead, and the two read as the same number.

**The email log is `channel = EMAIL`, not a second table.** A separate record of the same send
would disagree with the first — most visibly on a retry, where one table has a row with two
attempts and a separate log would have two rows.

**`app_user` gained a `roles` column.** There was nowhere to ask "is this account an admin?", which
made an `ops` platform admin indistinguishable from an `ops` shop owner. That mattered concretely:
the guard that stops an admin suspending another admin needs to know who is an admin, and the only
alternatives were omitting the guard (any admin could lock every operator out, with no recovery
path from inside the platform) or asking Keycloak on every check. Roles are stamped by
`IdentitySyncService` on the one path every authenticated request takes, so a role change takes
effect on the holder's next request rather than at some cleanup job nobody remembers to run.

**One endpoint per path.** Three separate ambiguous-mapping failures during this phase, each
naming two controllers and neither saying the cause was duplication. `/api/v1/admin/shops` is now
owned by `AdminShopController` and `/api/v1/admin/users` by `AdminSupportController`.

**The explorer runs a real HTTP call back into the app, forwarding the caller's own token.** A
direct service call would answer a different question — it would bypass the security filters,
method security and validation, which are exactly the things an operator is trying to see. Forwarding
the token also keeps it honest: a probe can only ever show what the caller could already see.

### Honest notes

- A crafted path is rejected by a `@Pattern` on the request body *and* re-checked in the
  controller. The body validation is at the edge; the re-check is because the contract shown on
  screen is a snapshot, and a hand-typed form is not.
- Every optional SQL filter is written `CAST(:x AS text) IS NULL`. As `:x IS NULL`, Postgres has
  nothing to infer the parameter's type from and fails with "could not determine data type of
  parameter $2" — an error that names a placeholder number rather than the filter that caused it.
- `Instant` cannot be bound or read through the Postgres driver as `TIMESTAMP_WITH_TIMEZONE`: it
  wants an `OffsetDateTime` in and an `OffsetDateTime` out. Both messages point at the schema.
- `check-ui.mjs` still reports the one pre-existing storefront failure — a remote image URL in the
  dev database pointing at `vecteezy.com`, which is unreachable and is in no source file.

## Appendix AG — Phase 8: returns, refunds and social selling, and the bugs that looked like data

Phase 7 built the admin platform. Phase 8 is the customer-facing half of the marketplace: money
going back, and orders arriving from outside the storefront. The decisions were taken on 18 Oct 2026
and the blocking Open Question #7 is answered.

### D14, resolved

The **shop's wallet pays** a refund and the platform **reverses commission in proportion** to the
returned portion. The alternative — reverse nothing until the whole order comes back — is simpler
and means the platform earns commission on goods it has just given a refund for, a cost that grows
exactly as fast as returns do.

Proven on real data, not asserted: an order of Rs. 900 with Rs. 450 of commission, one of two units
returned.

| | before | after |
|---|---|---|
| Wallet | 2,886,500 | 2,843,750 |
| `REFUND_DEBIT` | — | −45,000 |
| `COMMISSION_REVERSAL` | — | +2,250 |

2,250 is exactly half of 4,500, and the ledger's own description says `(50.0% of the order)`.

### What was built

| Feature | Where | Notes |
|---|---|---|
| Return requests | `ReturnRequest`, `ReturnItem`, `ReturnService`, `ReturnController` | A separate table, not a status on the order |
| Partial refunds | `WalletService.reverseDebitPartially` | Floors, never rounds up |
| Social selling | `ShareService`, `ShareController`, `share-sheet.tsx` | WhatsApp primary; Messenger, Viber, copy |
| Click attribution | `share_click` + `ShareClickRepository` | Per channel, per product, rate-limited |
| Pickup orders | `ShopOrder.FulfilmentMethod`, `switchToPickup` | Shipping zeroed, total recomputed |
| Abandoned carts | `AbandonedCartService` | Claimed in the same statement that selects them |
| Campaigns | `Campaign`, `CampaignService`, `CampaignController` | Window, not a cron string |
| Tax per shop | `ReportService.taxesByShop` + `taxes-by-shop` report | From the orders' frozen figures |

Migration `V17__returns_pickup_campaigns.sql` — returns, pickup, abandoned carts, campaigns and
share clicks.

### Decisions worth recording

**A return is a row, not a status.** The order moves to `RETURNED` only when the *entire* order value
has come back. Marking it returned while three of four items are still with the customer is a claim
the order cannot make, and "the whole order came back" is frequently two partial returns — which is
why that check is a query, not a field.

**Approve and refund are separate, on both sides.** Approving records a decision and takes no money;
refunding debits the wallet. One endpoint would credit a customer's refund the moment a shop
clicked a button, and the payment would then fail with the wallet already debited. The screen says
so before anything is clicked, and the test pins it.

**A refund the shop cannot afford leaves the return approved and visible.** `WalletService` refuses
a debit that would go negative and the refusal propagates, so nothing partial is written. The
alternative — skipping it — is a state somebody has to notice without being told.

**Rounding floors.** A credit that over-reverses by one paisa is a balance that never reconciles
again. The test asserts the direction, not the value, because the value is arithmetic and the
direction is the decision.

**Featuring and promotions are not entitlements.** A campaign is a window a shop sets. A paid slot
on the front page would be an advertisement.

### Three bugs, each of which presented as something else

**1. A user id where a shop id belonged.** `ReturnController` passed
`currentUser.require().getId()` — an `app_user` id — where `queueFor` expected a `shop` id. The
queue returned an empty list for **every** shop, which reads exactly like "you have no returns
yet" and is indistinguishable from the feature simply having nothing to show. Found because a real
return, created through a real customer token, did not appear in the real shop's queue. Both
`ReturnController` and `CampaignController` had it; both now use `tenantGuard.requireOwnedShop()`,
and `TenantGuard` gained `currentUserId()` so the account id and the shop id are named separately —
a rule enforced in the wrong direction produces an empty result rather than an error.

**2. A lazy collection read after the transaction closed.** `ReturnRequest.items` is `@OneToMany`
lazy, and the controller mapped it after the service returned, so every queue request 500'd with
`LazyInitializationException: failed to lazily initialize a collection ... could not initialize
proxy - no Session`. The message names proxies and says nothing about the missing fetch or the
transaction that had already closed. The views are now built *inside* the service, which is the only
place that can see them.

**3. A share endpoint with no ownership check.** `ShareController` took a shop id in the path and
checked only the caller's role, so any shop owner could read another shop's share links and, through
the click-stats endpoint, its traffic. Fixed with `@tenantGuard.owns(#shopId)` on all four
shop-scoped endpoints — and pinned by a test that reflects over the annotations and fails if one is
missing, because the hole is in an annotation and no service-level unit test could ever have caught
it.

Also worth recording: the console's product row had a "share" link pointing at
`/s/your-shop/<slug>` — the literal string `your-shop`, so it linked nowhere. It looked like a
working share button, which is exactly why nothing had caught it.

### Honest notes

- The WhatsApp *group* invite is not a server-minted link. `chat.whatsapp.com` invites are created
  inside the WhatsApp app by an admin and expire; the API cannot produce one. The share sheet says
  so rather than handing every shop a link that 404s.
- The abandoned-cart sweep runs every 30 minutes and reminds once, two hours after the last change.
  Abandoned is measured from `last_active_at`, not from creation — a cart somebody keeps adding to
  is not abandoned, and emailing about it is the fastest way to get a shop's reminders switched off.
- The dev database had to be given a customer for the browser check to drive a return end to end.
  That was done through the real API (`IdentitySync` created the account from a real customer
  token), not by inserting rows, so the state is one the platform would actually have produced.
- Guest carts are recorded but never emailed — there is nobody to email. The sweep skips them
  deliberately rather than marking them reminded, so they are not silently consumed.

## Appendix AH — Phase 8.5: letting people in, and the one bug that locked everybody out

Until this phase the platform had two accounts and no way to get a third. `POST /api/v1/shop/signup`
had been written in Phase 1 with nothing calling it, and the shop a new owner received was a
placeholder called "My shop" of type GROCERY.

### What changed

- **`registrationUrl(config)`** in `@store-app/auth` — one builder, both apps. Keycloak owns the
  form; reimplementing password policy and email verification here would mean reimplementing all of
  it, worse. The `client_id` it carries is what puts the realm's default client role on the account.
- **Registration links** on the console login ("Start a new shop", ops realm) and the storefront
  login ("Create an account", customers realm).
- **`/shop/start`** — shop name, vertical, description, optional web address. It calls the endpoint
  that already existed.
- **An onboarding guard in the app shell** that sends an unfinished owner to `/shop/start`.
- **The storefront landing page** at `/`, with the gallery moved to `/shops`.

### The bug: every existing shop owner was locked out

The guard reads `shop.profile_completed_at`. A new column, null for every row that predates it —
which is every existing shop. So the guard read a verified, trading shop as "has never signed up" and
redirected its owner to the sign-up form, from which they could not get back to their products,
orders or wallet.

**The screen rendered perfectly, for the wrong person.** No test caught it, because the guard is
client-side and the placeholder shop is correct. It was caught by running `check-phase8.mjs` after
building the guard and noticing that Phase 8's assertions had started failing: "products offer a
share action — 0 product(s)".

That is the whole argument for running the *previous* phase's checks after a change that was not
supposed to touch them. The failure surfaced as an unrelated feature going quiet.

The fix is a backfill in `V18`, and it is the interesting part:

```sql
UPDATE shop SET profile_completed_at = now()
WHERE profile_completed_at IS NULL
  AND (name IS DISTINCT FROM 'My shop' OR status <> 'DRAFT');
```

A blanket stamp would have been wrong for the placeholders, which really are unfinished. Comparing
the placeholder name looks fragile — and here it is not, because this is a **one-shot** judgement,
made once, about which rows predate a feature that did not exist. "Did this row come from the
provisioner or from a person" is precisely what the auto-generated name records, and only at the
instant the migration runs.

**At runtime the string is never compared.** The guard reads the timestamp, which is why the fragile
approach can be used once without becoming load-bearing. `ShopOnboardingIT` pins that separation:
a shop genuinely named "My shop" is still not complete, and a signed-up shop stays complete when
signed up again — because re-signing-up is not a reset, and a fresh timestamp would re-open the
wizard for a shop whose URL is already in printed material.

### A white button on the front page

The landing page's primary calls to action used `bg-neutral text-neutral-foreground`. This project
defines no `neutral` token, so the class produced no background at all and the front page's main
button rendered white on white.

Every automated check passed: the link existed, the text was there, the element was clickable. It
was found by looking at a screenshot, which is the only one of these checks that would have noticed.

Fixed to `bg-foreground text-background` — the theme's own pair, so it is correct in light and dark
and follows whatever the tokens say rather than a colour picked once.

### Verification

| | |
|---|---|
| backend | 6,083 tests, 0 failures, 0 errors, 0 skipped |
| frontend | 10/10 typecheck; console and storefront build |
| browser | `check-phase85.mjs` — landing page, gallery at /shops, both registration links, the wizard, the guard. **PHASE 8.5 UI OK** |
| regression | phase 6, 7 and 8 checks re-run after the gallery moved — this is how the lockout was found |

### Still needed from you

The Keycloak default-role configuration in the plan's Phase 8.5 section. Without it a person who
registers can sign in and is refused every screen, because their token carries no role the backend
recognises — which fails loudly and immediately rather than quietly, and is the intended failure
mode.

## Appendix AI — Checking the registration, and a 403 that had been hiding since Phase 0

Asked to check the Keycloak configuration after the default client roles were set. The answer was
that a different switch was still off.

### Realm registration is off on both realms

```
ops         400  Registration not allowed
customers   400  Registration not allowed
```

A realm switch, not a client one: **Realm settings → Login → User registration**. Both switches are
needed and neither substitutes for the other — the realm switch permits registration at all, the
default client role is what makes the new account able to open a console.

The trap here is worth naming. The OIDC discovery document advertises a `registration_endpoint` on
both realms, which reads as "registration is on". It is not the same endpoint and it is not evidence
of anything: it is the **Dynamic Client Registration** API, a different facility entirely. The
browser registration form lives at `/protocol/openid-connect/registrations` and is refused before the
client is ever consulted. `registrationUrl()` in `@store-app/auth` already warned about this.

### `check-registration.mjs`

Drives both journeys end to end with brand-new accounts: register through Keycloak's form, land on
`ROLE_SHOP` in the token, reach the wizard, complete onboarding, reach a dashboard — and the
customer equivalent.

It **prints `NOT RUN` and exits 0 while registration is off**, and says so as a configuration state
rather than a failure. Its first version printed `REGISTRATION OK` after having created nothing,
which is the exact false pass this project has been bitten by before; it now tracks how many
journeys actually ran and refuses to claim a pass when the answer is zero.

It creates real Keycloak accounts and cannot delete them — there is no admin credential here — so it
prefixes them `phase85-` and prints them for cleanup rather than pretending otherwise.

### The 403 that had been hiding since Phase 0

Chasing this surfaced something older. The console's shop queries were gated on `roles.includes(SHOP)`
— but the platform's own administrator account holds **both** `SHOP` and `ADMIN` and owns no shop, so
every admin screen fired a shop request that could only 403.

`AppShell`'s code has carried a comment since Phase 0 warning that exactly this noise *"hides a real
403 the day one appears"*, and the gate never fixed it, because it asked the wrong question. Not
"does this account have a shop" — "does it have the role". Those have different answers, and the
administrator account is the proof.

**Fixed at both ends:**

- `GET /api/v1/shop/me` now answers **200 with `owned: false`** for a caller who holds `SHOP` and
  owns nothing, because that is a real state — a fresh owner before provisioning, and permanently,
  every administrator who also holds the shop role.
- It still answers **404 for a caller without the SHOP role**, which an existing test pins and which
  must not be weakened: "200, you own nothing" turns a shop endpoint into a public *no*, revealing
  which accounts have shops.

A first attempt made it 200 for everybody and broke `customerCannotReachShopDashboard`. That was me
weakening a deliberate security boundary for convenience, and the test caught it in one run. The
distinction worth keeping is the **role**, not the existence of a shop.

The console now gates on `owned`, so an administrator owning nothing fires no shop requests at all.

### Two check scripts that reported working code as broken

Both were my error and both are worth recording because a check that cries wolf is a check nobody
runs.

- `check-ui.mjs` looked for the gallery at `/`. It moved to `/shops` in Phase 8.5. The site was fine.
- `check-phase85.mjs` asserted the onboarding wizard was showing. The account it signs in as had
  **already signed up** — so the wizard correctly said so, and the check called that a failure. It
  now asserts one of the two correct states and reports which.

## Appendix AJ — Registration actually working, and the bug it was hiding

The realm switches were on. The next question was whether a person who has never been here before
can get in. The answer was no, and finding out why took three separate defects.

### The registration links never worked, and the check could not tell

Both realms require **PKCE**. A hand-assembled registration URL carries no `code_challenge`, so
Keycloak refuses it:

```
302 → http://localhost:5173?error=invalid_request
        &error_description=Missing parameter: code_challenge_method
```

The browser lands back on the login page carrying an error nothing displays. The link looks fine in
the source, and the check asserted it was fine — `check-phase85.mjs` read the anchor's `href` and
confirmed it contained the right realm and client id. **It asserted the shape of a URL that fails.**

A verifier cannot be minted server-side and handed to the browser: the app has to present the same
one at the token exchange. So `registrationUrl(config)` is gone and replaced by `auth.register()`,
which delegates to `keycloak-js` — generate, store, challenge, redeem.

Three call sites moved with it:

| file | change |
|---|---|
| `packages/auth/src/adapter.ts` | `register(returnTo?)` on `AuthAdapter` |
| `apps/console/src/pages/login.tsx` | "Start a new shop" is now a button |
| `apps/customer/src/app/login/page.tsx` | "Create an account" is now a button |
| `apps/customer/src/app/page.tsx` | landing page (a server component) uses the new `RegisterButton` client component |

The landing page is statically generated and has no JavaScript budget to spare, so the button lives
in `components/register-button.tsx` and the page keeps its HTML-first shape.

### The platform could onboard exactly one shop owner, ever

Registering the first real second owner produced a **500 from `/api/v1/shop/me`**:

```
ERROR: duplicate key value violates unique constraint "shop_slug_key"
  at IdentitySyncService.provisionShop(IdentitySyncService.java:108)
```

The chain:

- `Shop`'s constructor derives its slug from the name: `SlugGenerator.from(name, this.id)`
- `SlugGenerator.from` uses the id **only when the name folds to something blank**
- `provisionShop` names every draft shop the constant `"My shop"`
- `shop.slug` is unique

So every provisionally-created shop was assigned `my-shop`. The first owner got one. **Every owner
after that met a unique-constraint violation on their first authenticated request**, before doing
anything at all — no shop, no wizard, a 500.

`ShopService.signUp` has de-duplicated slugs this whole time. `provisionShop` bypassed it by saving
directly. Fixed at the shared layer: `ShopService.allocateUniqueSlug(base)` is now public and
provisioning uses it, so the two paths cannot disagree. The dev database shows the result —
`my-shop`, `my-shop-2`, `my-shop-3`.

**No existing test could have caught this.** Every test that creates a shop goes through
`signUp`. Provisioning runs only on a brand-new Keycloak account and had been executed exactly once
in this project's life, by the owner who had been here since Phase 1. A single execution cannot find
a bug that needs two. `IdentitySyncServiceIT` now has a `TwoOwners` block that provisions two owners
and pins the slugs apart; it was confirmed to fail with the fix reverted.

### `/shop/me` answers a different question now

Followed from this. `admin.test` holds **both** `SHOP` and `ADMIN` and owns no shop, so the console's
`roles.includes(SHOP)` gate passed and every admin screen fired a shop request that could only 403 —
the noise `AppShell`'s comment has warned about since Phase 0.

- `GET /api/v1/shop/me` → **200 `{owned: false}`** for a caller holding `SHOP` and owning nothing.
- It still returns **404 for a caller without the SHOP role.** An existing test pins this and it must
  not be weakened: "200, you own nothing" turns a shop endpoint into a public *no*, revealing which
  accounts have shops.

The console now gates on `owned`.

### Keycloak 26 registration form

Worth writing down, because the selectors for an older Keycloak fail *silently*:

- submit is `<input type="submit" value="Register">` with **no id**
- the form is `#kc-register-form`
- both realms run **email-as-username**, so there is no `#username` field
- PatternFly markup throughout, not the older freeform template

### Checks

- `check-registration.mjs` no longer builds its own URL — it **clicks the control**, so it can only
  fail the way a person would. Creates two throwaway `phase85-*` accounts per run.
- `check-phase85.mjs` asserts Keycloak will *accept* the URL: realm, client id, `response_type=code`
  and a `code_challenge_method=S256`. The PKCE assertion is the one that cannot be satisfied by a
  broken link.

### Accounts and data this created

Nothing here can be deleted from Keycloak — there is no admin credential in this project — so the
accounts are listed for the user to remove:

```
phase85-owner-400912@example.test   registered before the slug fix; no shop
phase85-owner-728968@example.test   full journey, shop "my-shop-2"
phase85-owner-538544@example.test   full journey, shop "my-shop-3"
phase85-buyer-728968@example.test   full journey
probe-cust-*@example.test           one manual probe
password for all: iloveu-Phase85!
```

Two DRAFT shops named "Phase Eighty Five Books" remain in the dev database.
