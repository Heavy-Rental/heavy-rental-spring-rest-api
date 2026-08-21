# Heavy Rental Spring REST API — Project Documentation

Comprehensive as-built overview of the **Heavy Rental** backend: architecture, business processes, domain model, configuration, and HTTP endpoints.

| Item | Value |
|------|--------|
| **Module** | `heavy-rental-spring-rest-api/` |
| **Base package** | `com.heavy_rental.rest_api` |
| **Stack** | Java 21 · Spring Boot 4.1 · PostgreSQL · OAuth2 Resource Server JWT (HS256) · Stripe · Resilience4j |
| **Default port** | `8080` |
| **Packaging** | WAR |
| **Living contracts** | [`heavy-rental-spring-rest-api/openspec/`](heavy-rental-spring-rest-api/openspec/) |
| **Upstream AI service** | [Heavy-Rental/haystack-fast-api](https://github.com/Heavy-Rental/haystack-fast-api) (private network only) |

This document consolidates process and endpoint detail for developers and integrators. OpenSpec under `openspec/specs/` remains the living source of truth for feature contracts and proposed changes.

---

## Table of contents

1. [What this system does](#1-what-this-system-does)
2. [Architecture](#2-architecture)
3. [Security and authentication](#3-security-and-authentication)
4. [Domain model](#4-domain-model)
5. [Core business processes](#5-core-business-processes)
6. [Endpoint reference](#6-endpoint-reference)
7. [Configuration and environment](#7-configuration-and-environment)
8. [Running, testing, and tooling](#8-running-testing-and-tooling)
9. [Design-only / not built](#9-design-only--not-built)
10. [Related documentation](#10-related-documentation)

---

## 1. What this system does

The Spring REST API is the authenticated backend for a **heavy-equipment rental** product. Clients include:

- A **web portal** (equipment browse, rental plans, AI project-spec recommendations, bookings, payments)
- **Mobile / ops** flows (bookings list, deliveries, returns)
- **Admin** tools (users, monthly utilization)

The API owns:

- User identity and JWT session tokens
- Fleet catalog (assets, categories, images)
- Rental plans and booking lifecycle
- Stripe deposit and balance payments
- Delivery / return operational status transitions
- Orchestration of the private **Haystack** AI recommender (ingest → quote → knowledge Q&A)

The browser **never** calls Haystack or Stripe secrets directly. Recommendation traffic is always:

```text
Browser → Spring (JWT) → Haystack FastAPI (internal) → Spring → Browser
```

---

## 2. Architecture

### 2.1 Layering

Controllers stay thin. Business rules live in services. Controllers must not call external HTTP clients directly.

```text
controller/     HTTP mapping, auth principal, validation annotations
    ↓
service/        Business rules, sagas, ownership checks
    ↓
repository/     Spring Data JPA
entity/         Persistence model
dto/            Request/response records

client/haystack/  RestClient + Resilience4j → haystack-fast-api
security/         JWT mint/verify, token denylist
config/           Security, CORS, Stripe, exception handler
```

### 2.2 Package map

| Package | Responsibility |
|---------|----------------|
| `controller` | REST endpoints (12 controllers) |
| `service` | Auth, assets, bookings, payments, rental plans, recommender saga, admin, utilization |
| `entity` / `repository` | JPA model and data access |
| `dto` | API request/response shapes |
| `client.haystack` | Typed Haystack client (Call 1/2/3, health, resilience) |
| `security` | `JwtService`, in-memory `TokenDenylist` |
| `config` | `SecurityConfig`, `RestExceptionHandler`, Stripe, CORS/JWT properties |
| `mapper` | Booking → response mapping for list/delivery/return views |

### 2.3 External systems

| System | Role |
|--------|------|
| **PostgreSQL** | Sole application database (no H2 default) |
| **Stripe** | Deposit PaymentIntents, webhooks, off-session balance charges |
| **Haystack FastAPI** | Project-spec ingest, equipment recommendations/quotes, knowledge Q&A |

### 2.4 Haystack proxy map (as-built)

| Spring route | Calls Haystack? | Notes |
|--------------|-----------------|-------|
| `POST /api/recommendations/project-spec` | **Yes** — Call 1 then Call 2 | Dual-hop saga |
| `POST /api/recommendations/{id}/knowledge-query` | **Yes** — Call 3 only | No re-ingest |
| `GET /api/recommendations/{id}` | **No** | DB session only |
| `POST /api/rentalPlans/{id}/quote` | **No** | Spring-only `baseDailyRate × days` |
| Bookings, payments, equipment, users, etc. | **No** | Local business logic |

---

## 3. Security and authentication

### 3.1 Model

- **Stateless** sessions (`SessionCreationPolicy.STATELESS`)
- **CSRF disabled** for the API
- **Passwords**: BCrypt
- **Tokens**: JWT signed with **HS256** (`app.jwt.secret` ≥ 32 characters)
- **Authorities** from JWT claim `roles` (no `SCOPE_` prefix)
- Shared error JSON: `{ "error": "<code>", "message": "<reason>" }`

### 3.2 Auth process

```text
1. GET  /api/auth/getBearerToken     → interim JWT (public, text/plain)
2. POST /api/auth/login              → access JWT JSON; interim jti denylisted
3. Call protected APIs with          Authorization: Bearer <access-jwt>
4. POST /api/auth/logout             → access jti denylisted until exp
```

| | Interim token | Access (session) token |
|--|---------------|------------------------|
| Issued by | `GET /api/auth/getBearerToken` | `POST /api/auth/login` after credential success |
| `sub` | Random UUID | Authenticated **email** |
| `tokenType` | `interim` | `access` |
| `roles` | `["ROLE_INTERIM"]` | e.g. `["ROLE_USER"]`, `["ROLE_ADMIN"]` |
| May call | Login only | Logout + USER/ADMIN APIs |

On successful login the interim token’s `jti` is denylisted. On logout the access token’s `jti` is denylisted. The JWT decoder rejects denylisted tokens.

### 3.3 Security rules (summary)

| Path | Access |
|------|--------|
| `GET /api/auth/getBearerToken` | Public |
| `POST /api/auth/login` | `ROLE_INTERIM` |
| `POST /api/auth/logout` | `ROLE_USER` or `ROLE_ADMIN` |
| `POST /api/payments/webhook` | Public (auth = Stripe-Signature) |
| `GET /actuator/health`, `/actuator/info`, `/error` | Public |
| `/api/users/**` | `ROLE_ADMIN` only |
| `/api/monthly-utilization` | `ROLE_ADMIN` only |
| All other `/api/**` | `ROLE_USER` or `ROLE_ADMIN` |

CORS is restricted to configured origins (`app.cors.allowed-origins`; defaults `http://localhost:5173` and `http://localhost:4173`). Allowed methods: GET, POST, PUT, PATCH, DELETE, OPTIONS. Allowed headers: `Authorization`, `Content-Type`.

### 3.4 Error body convention

```json
{ "error": "unauthorized", "message": "Authentication required. …" }
```

Common `error` codes: `unauthorized`, `forbidden`, `invalid_credentials`, `bad_request`, `validation_failed`, `not_found`, `conflict`, `recommender_unavailable`, `recommender_timeout`, `recommender_upstream_error`, `payload_too_large`.

Validation failures (`@Valid` on request bodies) return HTTP `400` with `error` = `validation_failed`.

---

## 4. Domain model

### 4.1 Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| `User` | `users` | Auth principal; unique `name`; email login; roles USER / ADMIN / DRIVER |
| `AssetCategory` | `asset_categories` | Fleet categories (e.g. Excavator, Scissors Lift, Boom Lift, Fork Lift) |
| `Asset` | `assets` | Fleet item: rates, capacity/height, condition, location |
| `AssetImage` | `asset_images` | Base64 image text linked to asset; also feeds recommender `items[].equipment.img` |
| `RentalPlan` | `rental_plan` | Customer plan; status DRAFT / SAVED / QUOTED / CONVERTED |
| `RentalPlanRecord` | `rental_plan_records` | Plan line items |
| `Booking` | `bookings` | Rental booking: dates, status, totals, site address, optional plan link |
| `BookingItem` | `booking_items` | Asset lines with rates and subtotals |
| `Payment` | `payments` | Deposit / balance / full; Stripe fields |
| `DeliveryRecord` | `delivery_records` | Delivery ops record (booking + driver) |
| `ReturnRecord` | `return_records` | Return ops record (booking + driver) |
| `AIRecommendation` | `ai_recommendations` | S2b session + Haystack handles (`ingest_id`, correlation, budget, warnings, …) |
| `RecommendationItem` | `recommendation_items` | Optional ranked lines |

`Booking.sitePostalCode` is derived (`@Formula`) from the trailing 6 characters of `siteAddress`. API create/update requests enforce that `siteAddress` ends with a 6-digit postal code via Bean Validation.

### 4.2 Important enums

| Enum | Values |
|------|--------|
| `User.UserRole` | `USER`, `ADMIN`, `DRIVER` |
| `ConditionType` | `EXCELLENT`, `GOOD`, `FAIR`, `NEEDS_REPAIR` |
| `RentalPlan.PlanStatus` | `DRAFT`, `SAVED`, `QUOTED`, `CONVERTED` |
| `Booking.BookingStatus` | `PENDING_DEPOSIT`, `PENDING_CONFIRMED`, `CONFIRMED`, `MOBILISED`, `COMPLETED`, `CANCELLED` |
| `Payment.PaymentType` | `DEPOSIT`, `BALANCE`, `FULL_PAYMENT` |
| `Payment.PaymentStatus` | `PENDING`, `SUCCESS`, `FAIL` |
| `AIRecommendation.RecommendationStatus` | `GENERATED`, `ACCEPTED`, `REJECTED`, `EXPIRED` |

### 4.3 Booking status sets (shared constants)

| Set | Statuses | Used for |
|-----|----------|----------|
| **ACTIVE_STATUSES** | PENDING_DEPOSIT, PENDING_CONFIRMED, CONFIRMED, MOBILISED | Block overlapping asset availability |
| **UTILIZATION_STATUSES** | CONFIRMED, MOBILISED, COMPLETED | Monthly utilization / revenue reporting |

### 4.4 Seed data (development)

Loaded from `src/main/resources/data.sql` after Hibernate DDL (`ddl-auto=update`, `defer-datasource-initialization=true`). Flyway is disabled on the default profile. Production (`SPRING_PROFILES_ACTIVE=prod`) runs Flyway and `ddl-auto=validate`. Set GitHub Environment var `APP_SEED_DATA_SQL=true` on Academy CD to also run `data.sql` after Flyway (default off).

| Email | Password (dev) | Role |
|-------|----------------|------|
| `admin@localhost` | `admin1234` | ADMIN |
| `alex.tan@example.sg` | `customer123` | USER |
| `ravi.kumar@example.sg` | `admin123` | ADMIN |
| `ah.tan@example.sg` | `driver123` | DRIVER |
| `mei.ling@example.sg` | `customer234` | USER |
| `farid.rahman@example.sg` | `customer345` | USER |
| `mei.lin@example.sg` | `customer456` | USER |

Approximate seed scale: ~27 assets across 4 categories, ~90 bookings spanning statuses, mock JPEG images under `src/main/resources/mock-images/` stored as base64 in `asset_images`.

---

## 5. Core business processes

### 5.1 Authentication journey

```mermaid
sequenceDiagram
  participant C as Client
  participant API as Spring API
  participant DB as PostgreSQL

  C->>API: GET /api/auth/getBearerToken
  API-->>C: interim JWT (text/plain)
  C->>API: POST /api/auth/login (Bearer interim + email/password)
  API->>DB: authenticate user
  API-->>C: accessToken, expiresIn, username
  Note over API: interim jti denylisted
  C->>API: protected APIs (Bearer access)
  C->>API: POST /api/auth/logout
  Note over API: access jti denylisted
```

**Login steps (normative):**

1. Assert interim JWT present and `tokenType == interim`
2. Validate email/password non-blank
3. `AuthenticationManager.authenticate(email, plainPassword)` — do **not** BCrypt-encode the request password before authenticate
4. Issue access JWT (`sub` = email, roles from DB, `tokenType` = access)
5. Denylist interim `jti` until original `exp`
6. Return `LoginResponse`

### 5.2 Equipment browse and catalog

- List/filter assets by optional `category`, `search` (name substring), `condition`, and optional `startDate`/`endDate` window.
- When a date window is supplied, `available` reflects whether the asset has an overlapping booking in **ACTIVE_STATUSES**.
- Images are returned as JPEG data URIs: `data:image/jpeg;base64,<raw>`.
- CRUD routes support create, full replace (PUT), partial update (PATCH), and delete.

### 5.3 Rental plan quote

```text
POST /api/rentalPlans          → DRAFT plan (one active plan per customer)
POST /api/rentalPlans/{id}/items → add asset line
DELETE .../items/{itemId}      → remove line
POST /api/rentalPlans/{id}/quote → compute totals, lock as QUOTED
```

**Pricing (as-built):** `DefaultPricingClient` uses inclusive day count:

```text
days = ChronoUnit.DAYS.between(start, end) + 1
subtotal = baseDailyRate × days
```

This is **Spring-only**. A future FastAPI-backed `PricingClient` is designed but not built. A customer may have only one plan in DRAFT/SAVED/QUOTED at a time (`409 conflict` otherwise).

`siteAddress` on create must end with a 6-digit postal code (same rule as bookings).

### 5.4 Booking create and payments

#### Create booking

```text
POST /api/bookings
  → resolve customer from JWT
  → validate items, dates (end after start), siteAddress postal rule
  → optional rentalPlanId link
  → reject if any asset overlaps ACTIVE booking (HTTP 409)
  → price lines: days = max(1, DAYS.between(start, end))  // booking convention
  → deposit = 30% of total (DEPOSIT_RATE = 0.30)
  → status = PENDING_DEPOSIT
```

**Note:** Booking day count uses `ChronoUnit.DAYS.between(start, end)` (minimum 1). Rental-plan quoting uses **inclusive** `between + 1`. Treat them as separate conventions.

#### Deposit payment

```text
POST /api/payments/deposit-intent  { "bookingId": N }
  → owner or admin
  → create Stripe PaymentIntent for depositAmount (currency sgd)
  → setup_future_usage = off_session (saves method for later balance)
  → return clientSecret + paymentIntentId
```

#### Webhook

```text
POST /api/payments/webhook
  → verify Stripe-Signature on raw body
  → handle payment_intent.succeeded / payment_intent.payment_failed
  → update Payment rows; advance booking status on successful deposit
```

#### Balance charge scheduler

`BalanceChargeSchedulerService` runs daily at **02:00 Asia/Singapore**:

- Finds bookings with `startDate == tomorrow` and status `PENDING_CONFIRMED`
- Charges remaining balance off-session using the payment method saved at deposit
- Each booking is processed in its own transaction so one failure does not abort the batch

### 5.5 Delivery and return operations

Operational state machine exposed by these APIs:

```text
CONFIRMED  --[PATCH /api/deliveries/{bookingId}/status { bookingStatus: MOBILISED }]-->  MOBILISED
MOBILISED  --[PATCH /api/returns/{bookingId}/status    { bookingStatus: COMPLETED }]-->  COMPLETED
```

- **Today’s deliveries:** bookings with `startDate == today` and status in (CONFIRMED, MOBILISED)
- **Today’s returns:** bookings due for return today (see `ReturnService`)
- Invalid transitions return `400`

Other booking statuses (PENDING_*, CANCELLED) are not advanced by these routes.

### 5.6 AI recommender saga (S2b)

Portal journey for project-spec → equipment quote → follow-up Q&A.

```mermaid
sequenceDiagram
  participant Portal as Web portal
  participant Spring as Spring API
  participant HS as Haystack FastAPI
  participant DB as PostgreSQL

  Portal->>Spring: POST /api/recommendations/project-spec (JWT)
  Spring->>HS: Call 1 ingest (JSON or multipart)
  HS-->>Spring: ingest_id, summary, budget, warnings
  Spring->>DB: persist AIRecommendation session
  Spring->>HS: Call 2 get asset recommendations / quote
  HS-->>Spring: quoteRef, items, estimatedTotal, …
  Spring-->>Portal: recommendationId + quote fields

  Portal->>Spring: POST /api/recommendations/{id}/knowledge-query
  Spring->>DB: load session, assert owner/admin
  Spring->>HS: Call 3 project-knowledge/query
  HS-->>Spring: answer, sourcesUsed
  Spring-->>Portal: answer

  Portal->>Spring: GET /api/recommendations/{id}
  Spring->>DB: load session
  Spring-->>Portal: session summary (no Haystack call)
```

#### Hard rules

- Controllers never call Haystack; orchestration is in `RecommenderSagaService` → `HaystackRecommenderClient`
- Haystack `user_id` is **server-derived** from the JWT principal — never trust a client-supplied haystack user id
- On Call 2 failure after successful Call 1: **do not re-ingest**; keep the session row
- **Never invent** equipment, rates, or synthetic catalog objects on failure
- Equipment images on quote lines: when haystack `id` matches a numeric `assets.id` with an image, Spring attaches a JPEG data URI; otherwise pass through haystack `img`

#### Haystack internal paths (Spring client)

| Op | Method | Upstream path |
|----|--------|---------------|
| Health | GET | `/health` |
| Call 1 Ingest | POST | `/internal/v1/recommendations/submitprojectspecification` |
| Call 2 Recommend | POST | `/internal/v1/recommendations/project-knowledge/getassetrecommendations` |
| Call 3 Q&A | POST | `/internal/v1/recommendations/project-knowledge/query` |

#### Resilience (Resilience4j)

Configured under `haystack.*` in `application.properties`:

- Per-operation **timeouts** (connect, health, QA, recommend, ingest)
- **Retry** with idempotency keys (ingest retry off by default until S2a confirmed)
- **Circuit breaker** (failure rate / sliding window / open wait)
- **Bulkheads** limiting concurrent ingest / recommend / QA calls

Typical portal error mapping when Haystack is unhealthy: `503 recommender_unavailable`, `504 recommender_timeout`, `502 recommender_upstream_error`.

### 5.7 Admin processes

- **Users:** full CRUD under `/api/users` (admin only). Create returns a temporary password.
- **Monthly utilization:** trailing six months of fleet utilization % and revenue for admin dashboards.

### 5.8 Depots stub

`GET /api/depots` always returns `[]`. There is no Depot entity; site addresses live on bookings/plans. The route exists so the frontend equipment page does not fail when it also requests depots.

---

## 6. Endpoint reference

Base URL (local): `http://localhost:8080`

Unless noted, send:

```http
Authorization: Bearer <access-jwt>
Content-Type: application/json
```

### 6.1 Master route map

| Method | Path | Auth | Domain |
|--------|------|------|--------|
| `GET` | `/actuator/health` | Public | Health |
| `GET` | `/actuator/info` | Public | Health |
| `GET` | `/api/auth/getBearerToken` | Public | Auth |
| `POST` | `/api/auth/login` | ROLE_INTERIM | Auth |
| `POST` | `/api/auth/logout` | USER/ADMIN | Auth |
| `GET` | `/api/equipment` | USER/ADMIN | Equipment |
| `GET` | `/api/equipment/{id}` | USER/ADMIN | Equipment |
| `POST` | `/api/equipment` | USER/ADMIN | Equipment |
| `PUT` | `/api/equipment/{id}` | USER/ADMIN | Equipment |
| `PATCH` | `/api/equipment/{id}` | USER/ADMIN | Equipment |
| `DELETE` | `/api/equipment/{id}` | USER/ADMIN | Equipment |
| `GET` | `/api/depots` | USER/ADMIN | Depots (stub) |
| `POST` | `/api/rentalPlans` | USER/ADMIN | Rental plans |
| `GET` | `/api/rentalPlans` | USER/ADMIN (own) | Rental plans |
| `GET` | `/api/rentalPlans/{id}` | Owner | Rental plans |
| `POST` | `/api/rentalPlans/{id}/items` | Owner | Rental plans |
| `DELETE` | `/api/rentalPlans/{id}/items/{itemId}` | Owner | Rental plans |
| `POST` | `/api/rentalPlans/{id}/quote` | Owner | Rental plans |
| `POST` | `/api/bookings` | USER/ADMIN | Bookings |
| `GET` | `/api/bookings` | USER/ADMIN | Bookings |
| `GET` | `/api/bookings/{bookingId}` | USER/ADMIN | Bookings |
| `PUT` | `/api/bookings/{bookingId}` | USER/ADMIN | Bookings |
| `GET` | `/api/deliveries` | USER/ADMIN | Deliveries |
| `PATCH` | `/api/deliveries/{bookingId}/status` | USER/ADMIN | Deliveries |
| `GET` | `/api/returns` | USER/ADMIN | Returns |
| `PATCH` | `/api/returns/{bookingId}/status` | USER/ADMIN | Returns |
| `POST` | `/api/payments/deposit-intent` | USER/ADMIN (owner/admin) | Payments |
| `POST` | `/api/payments/webhook` | Public + Stripe-Signature | Payments |
| `POST` | `/api/recommendations/project-spec` | USER/ADMIN | Recommender |
| `POST` | `/api/recommendations/{id}/knowledge-query` | Owner/admin | Recommender |
| `GET` | `/api/recommendations/{id}` | Owner/admin | Recommender |
| `GET` | `/api/users` | ADMIN | Admin users |
| `GET` | `/api/users/{id}` | ADMIN | Admin users |
| `POST` | `/api/users` | ADMIN | Admin users |
| `PATCH` | `/api/users/{id}` | ADMIN | Admin users |
| `DELETE` | `/api/users/{id}` | ADMIN | Admin users |
| `GET` | `/api/monthly-utilization` | ADMIN | Admin utilization |

---

### 6.2 Health

| Method | Path | Auth | Success |
|--------|------|------|---------|
| `GET` | `/actuator/health` | Public | Actuator health JSON |
| `GET` | `/actuator/info` | Public | Actuator info |

---

### 6.3 Auth — `/api/auth`

#### `GET /api/auth/getBearerToken`

- **Auth:** Public  
- **Response:** `200` `text/plain` — raw interim JWT (no `Bearer` prefix)

#### `POST /api/auth/login`

```http
Authorization: Bearer <interim-jwt>
Content-Type: application/json

{ "email": "admin@localhost", "password": "admin1234" }
```

**Success `200` — `LoginResponse`:**

| Field | Type | Description |
|-------|------|-------------|
| `accessToken` | string | Session JWT |
| `tokenType` | string | `"Bearer"` |
| `expiresIn` | long | Seconds until expiry |
| `username` | string | Authenticated email (field name is historical) |

**Errors:** `400` bad request · `401` invalid credentials / unauthorized · `403` forbidden (wrong token tier)

#### `POST /api/auth/logout`

```http
Authorization: Bearer <access-jwt>
```

**Success `200`:** `{ "message": "Logged out successfully" }`

---

### 6.4 Equipment — `/api/equipment`

#### `GET /api/equipment`

Optional query parameters:

| Param | Notes |
|-------|--------|
| `category` | Exact category name |
| `search` | Case-insensitive name substring |
| `condition` | `ConditionType` enum name |
| `startDate` / `endDate` | ISO dates; both or neither for availability |

**Success `200`:** array of `EquipmentResponse`, e.g.:

```json
{
  "id": 1,
  "name": "CAT 320 Excavator",
  "category": "Excavator",
  "baseDailyRate": 450.00,
  "minDailyRate": 400.00,
  "maxDailyRate": 500.00,
  "capacity": null,
  "platformHeight": null,
  "purchaseYear": 2021,
  "condition": "GOOD",
  "available": true,
  "desc": "...",
  "img": "data:image/jpeg;base64,/9j/...",
  "location": "Tuas",
  "tags": []
}
```

`available` is `null` when no date window is provided. `tags` is always `[]` as-built.

#### `GET /api/equipment/{id}`

Same body shape. Optional `startDate` / `endDate` for availability.

#### `POST /api/equipment` → `201`

Body `EquipmentRequest`: `name`, `serialno`, `categoryId`, `baseDailyRate`, `minDailyRate`, `maxDailyRate`, `capacity`, `platformHeight`, `purchaseYear`, `condition`, `desc`, `location`.

#### `PUT /api/equipment/{id}`

Full replace with `EquipmentRequest`.

#### `PATCH /api/equipment/{id}`

Partial update with `EquipmentRequest` (null fields left unchanged in service).

#### `DELETE /api/equipment/{id}` → `204`

May return `404` if missing or `409` if delete conflicts with existing usage.

---

### 6.5 Depots — `/api/depots`

#### `GET /api/depots`

**Success `200`:** `[]` (stub; no Depot entity).

---

### 6.6 Rental plans — `/api/rentalPlans`

Ownership is scoped to the JWT subject (email).

#### `POST /api/rentalPlans` → `201`

```json
{
  "startDate": "2026-08-09",
  "endDate": "2026-08-13",
  "siteAddress": "20 Jurong Port Road, 619094"
}
```

Creates a **DRAFT** plan. `siteAddress` must end with a 6-digit postal code or `400 validation_failed`. One active (DRAFT/SAVED/QUOTED) plan per customer or `409`.

#### `GET /api/rentalPlans`

Caller’s plans only → `RentalPlanResponse[]`.

#### `GET /api/rentalPlans/{id}`

Owner only; else `404`.

#### `POST /api/rentalPlans/{id}/items` → `201`

```json
{ "assetId": 1 }
```

#### `DELETE /api/rentalPlans/{id}/items/{itemId}`

Removes a line item; returns updated plan.

#### `POST /api/rentalPlans/{id}/quote`

Spring-only pricing; sets plan status toward quoted/locked totals. **Does not** call Haystack.

**`RentalPlanResponse` fields:** `id`, `startDate`, `endDate`, `siteAddress`, `status`, `totalAmount`, `items[]`.

---

### 6.7 Bookings — `/api/bookings`

#### `POST /api/bookings` → `201`

```json
{
  "items": [{ "assetId": 1 }],
  "startDate": "2026-08-09",
  "endDate": "2026-08-13",
  "rentalPlanId": null,
  "siteAddress": "20 Jurong Port Road, 619094",
  "deliveryNotes": ""
}
```

- Customer = JWT principal  
- Deposit rate **30%** of total  
- Initial status: `PENDING_DEPOSIT`  
- Overlapping active booking on any asset → `409 conflict`  
- Bad postal `siteAddress` → `400 validation_failed`

#### `GET /api/bookings`

List bookings → `BookingResponse[]`.

#### `GET /api/bookings/{bookingId}`

Single booking or `404`.

#### `PUT /api/bookings/{bookingId}`

Update `startDate`, `endDate`, `siteAddress`, `deliveryNotes` (`BookingUpdateRequest`; postal rule applies).

**`BookingResponse`:**

| Field | Type |
|-------|------|
| `bookingId` | long |
| `customerName` | string |
| `startDate` / `endDate` | date |
| `bookingStatus` | string |
| `siteAddress` | string |
| `items` | `{ assetName, serialNumber }[]` |
| `deliveryNotes` | string |
| `totalAmount` / `depositAmount` / `remainingBalance` | number |

---

### 6.8 Deliveries — `/api/deliveries`

#### `GET /api/deliveries`

Today’s deliveries (`startDate == today`, status CONFIRMED or MOBILISED) → `DeliveryItemResponse[]`.

#### `PATCH /api/deliveries/{bookingId}/status`

```json
{ "bookingStatus": "MOBILISED" }
```

Only legal transition: **CONFIRMED → MOBILISED**. Other transitions → `400`.

---

### 6.9 Returns — `/api/returns`

#### `GET /api/returns`

Today’s returns → `ReturnItemResponse[]` (includes `returnNotes` when present).

#### `PATCH /api/returns/{bookingId}/status`

```json
{
  "bookingStatus": "COMPLETED",
  "returnNotes": "optional notes"
}
```

Advances **MOBILISED → COMPLETED** (invalid transitions → `400`).

---

### 6.10 Payments — `/api/payments`

#### `POST /api/payments/deposit-intent`

```json
{ "bookingId": 1 }
```

**Success `200`:**

```json
{
  "clientSecret": "pi_..._secret_...",
  "paymentIntentId": "pi_..."
}
```

Requires booking ownership (or admin). Currency: **sgd**. Duplicate non-failed deposit → `409`. Stripe API failures → `502`.

#### `POST /api/payments/webhook`

```http
Stripe-Signature: t=...,v1=...
Content-Type: application/json

<raw Stripe event JSON>
```

- **No JWT**  
- Signature verified against raw body and `stripe.webhook.secret`  
- Invalid signature → `400` empty body  
- Handled events: `payment_intent.succeeded`, `payment_intent.payment_failed`

---

### 6.11 Recommendations (S2b) — `/api/recommendations`

Requires access JWT (`ROLE_USER` or `ROLE_ADMIN`). Session ownership: matching user unless admin.

Optional header on submit: `X-Correlation-Id` (propagated to Haystack; generated if absent).

#### `POST /api/recommendations/project-spec` (JSON)

```json
{
  "projectText": "Build a two-storey warehouse with deep excavation…",
  "startDate": "2026-09-01",
  "endDate": "2026-10-15",
  "userName": "Alex Tan",
  "query": "focus on excavators and boom lifts",
  "topK": 5
}
```

Orchestrates **Call 1 then Call 2**. Response is quote-shaped (not chatbot answer).

#### `POST /api/recommendations/project-spec` (multipart)

`Content-Type: multipart/form-data`

| Part / field | Required | Notes |
|--------------|----------|--------|
| `file` | one of file or text | Project document upload |
| `projectText` | one of file or text | Free-text specification |
| `startDate` / `endDate` | no | ISO dates |
| `userName` | no | Display/audit |
| `query` | no | Call 2 focus |
| `topK` | no | Call 2 cap |

Max upload size: `haystack.max-in-memory-size` / servlet multipart (default **20MB**).

**Success `200` — `SubmitProjectSpecResponse`:**

| Field | Source | Notes |
|-------|--------|--------|
| `recommendationId` | Spring | PK for later GET / knowledge-query |
| `ingestId` | Call 1 | Stored for Call 2/3 |
| `userRequirementSummary` | Call 1 | |
| `tentativeStartDate` / `tentativeEndDate` | Portal/session | |
| `needsSummary` | Call 1 | Display needs; not fleet recs |
| `expectedBudget` | Call 1 | Never invented client-side |
| `warnings` | Call 1 + 2 | Merged soft issues |
| `correlationId` | Spring | Log join key |
| `quoteRef` | Call 2 | |
| `confidenceScore` | Call 2 | |
| `days` | Call 2 | |
| `estimatedTotal` | Call 2 | |
| `specSummary` / `rationale` | Call 2 | |
| `items[]` | Call 2 | Ranked quote lines with nested `equipment` |

Each `items[]` element includes `rankOrder`, `matchScore`, `reason`, `lineTotal`, `quantity` (Haystack pass-through; may be greater than 1 when Call 2 collapses duplicate equipment), and nested `equipment` (`id`, `name`, `category`, rates, `img`, `tags`, …). `platformHeight` may be omitted from JSON when null.

**Not returned on submit:** Call 3 `answer` / `sourcesUsed` (use knowledge-query).

#### `POST /api/recommendations/{recommendationId}/knowledge-query`

```json
{ "query": "Why was a boom lift recommended?", "topK": 5 }
```

**Call 3 only** (no ingest, no Call 2).

**Success `200`:**

```json
{
  "answer": "…",
  "sourcesUsed": ["…"]
}
```

#### `GET /api/recommendations/{recommendationId}`

DB-only session summary: ingest id, summary, dates, budget, warnings, status, correlation id, created time, etc. **No Haystack call.**

#### Recommender errors

| Condition | HTTP | `error` |
|-----------|------|---------|
| Validation | 400 | `bad_request` / `validation_failed` |
| Not found | 404 | `not_found` |
| Not owner | 403 | `forbidden` |
| Haystack 4xx | 400/422 | mapped FastAPI error when present |
| Circuit open / bulkhead | 503 | `recommender_unavailable` |
| Timeout | 504 | `recommender_timeout` |
| Upstream 5xx after policy | 502/503 | `recommender_upstream_error` |
| Payload too large | 413 | `payload_too_large` |

---

### 6.12 Admin users — `/api/users`

**Auth:** `ROLE_ADMIN` only.

| Method | Path | Body | Success |
|--------|------|------|---------|
| `GET` | `/api/users` | — | `UserResponse[]` |
| `GET` | `/api/users/{id}` | — | `UserResponse` |
| `POST` | `/api/users` | `{ "name", "email" }` | `201` `UserCreateResponse` (+ `temporaryPassword`) |
| `PATCH` | `/api/users/{id}` | partial `{ name?, email?, role? }` | `UserResponse` |
| `DELETE` | `/api/users/{id}` | — | `204` |

`UserResponse`: `{ id, name, email, role }`.

---

### 6.13 Monthly utilization — `/api/monthly-utilization`

**Auth:** `ROLE_ADMIN` only.

#### `GET /api/monthly-utilization`

Trailing six months:

```json
[
  {
    "id": 1,
    "month": "2026-03",
    "utilization": 12.5,
    "revenue": 1500.00
  }
]
```

Utilization math uses shared `Booking.UTILIZATION_STATUSES` and inclusive overlap day counts so fleet-wide and per-asset views stay consistent.

---

## 7. Configuration and environment

Primary file: `heavy-rental-spring-rest-api/src/main/resources/application.properties`.

### 7.1 Core

| Property | Default / notes |
|----------|-----------------|
| `server.port` | `8080` |
| `spring.datasource.url` | `jdbc:postgresql://${POSTGRES_HOSTNAME:db}:5432/${POSTGRES_DB:postgres}` (password from `POSTGRES_PASSWORD`, no plaintext default) |
| `spring.jpa.hibernate.ddl-auto` | `update` (default); `validate` in `prod` |
| `spring.flyway.enabled` | `false` (default); `true` in `prod` |
| `spring.flyway.locations` | `classpath:db/migration` (prod) |
| `spring.sql.init.mode` | `always` locally (seed `data.sql`); `never` in prod |
| `app.seed.data-sql` | prod only; `${APP_SEED_DATA_SQL:false}` — Academy CD overlay |
| `spring.jpa.open-in-view` | `false` |

### 7.2 JWT

| Property | Env override | Notes |
|----------|--------------|--------|
| `app.jwt.secret` | `APP_JWT_SECRET` | ≥ 32 chars for HS256; no plaintext default |
| `app.jwt.issuer` | `APP_JWT_ISSUER` | default `heavy-rental-rest-api` |
| `app.jwt.expirationMinutes` | `APP_JWT_EXPIRATION_MINUTES` | default `60` |

### 7.3 CORS

| Property | Env | Default |
|----------|-----|---------|
| `app.cors.allowed-origins` | `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:4173` |

No wildcard origin (incompatible with credentialed Authorization-header APIs).

### 7.4 Stripe

| Property | Env |
|----------|-----|
| `stripe.api.key` | `STRIPE_API_KEY` (no plaintext default) |
| `stripe.publishable.key` | `STRIPE_PUBLISHABLE_KEY` |
| `stripe.webhook.secret` | `STRIPE_WEBHOOK_SECRET` (no plaintext default) |

Currency for PaymentIntents is hardcoded **`sgd`** as-built.

### 7.5 Haystack client

| Property | Purpose | Default |
|----------|---------|---------|
| `haystack.base-url` | FastAPI base | `http://haystack-fast-api:8000` |
| `haystack.timeouts.connect` | Connect timeout | `5s` |
| `haystack.timeouts.health-read` | Health read | `5s` |
| `haystack.timeouts.qa-read` | Call 3 | `45s` |
| `haystack.timeouts.recommend-read` | Call 2 | `90s` |
| `haystack.timeouts.ingest-read` | Call 1 | `180s` |
| `haystack.max-in-memory-size` | Body/file cap | `20MB` |
| `haystack.retry.ingest-enabled` | Ingest retry | `false` |
| `haystack.retry.*-max-attempts` | Retry caps | `2` |
| `haystack.resilience.circuit-breaker-*` | CB thresholds | 50% / window 10 / min 5 / wait 30s |
| `haystack.resilience.bulkhead-*-max-concurrent` | Concurrency limits | ingest 5 · recommend/QA 10 |

Env prefixes: `HAYSTACK_*` (see properties file for exact names).

---

## 8. Running, testing, and tooling

### 8.1 Prerequisites

- **Java 21**
- **PostgreSQL** reachable at `POSTGRES_HOSTNAME` (default host `db`)
- Optional: **haystack-fast-api** for live recommender (tests use WireMock)
- Optional: **Stripe** keys for real deposit intents and webhooks

### 8.2 Run the API

Secrets have no plaintext defaults in `application.properties`. Export at least:

```bash
export APP_JWT_SECRET='<at least 32 characters>'
export POSTGRES_PASSWORD='<postgres password>'
# optional, needed for payments / geocoding:
# export STRIPE_API_KEY=...
# export STRIPE_WEBHOOK_SECRET=...
# export ONEMAP_EMAIL=...
# export ONEMAP_PASSWORD=...
```

```bash
cd heavy-rental-spring-rest-api
./mvnw spring-boot:run
```

Health check:

```bash
curl -s http://localhost:8080/actuator/health
```

Auth smoke:

```bash
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@localhost","password":"admin1234"}'
```

### 8.3 Tests

```bash
cd heavy-rental-spring-rest-api
./mvnw test
```

Notable suites:

| Area | Classes |
|------|---------|
| Auth | `AuthenticationIntegrationTest` |
| Recommender client resilience | `HaystackRecommenderClientTest`, `HaystackRetryIdempotencyTest`, `HaystackTimeoutRetryTest`, `HaystackCircuitBreakerTest`, `HaystackBulkheadTest` |
| Recommender saga | `RecommenderSagaServiceTest`, `RecommenderSagaWireMockTest`, `RecommendationControllerIntegrationTest` |
| Booking / plans / returns | `BookingServiceTest`, `RentalPlanServiceTest`, `ReturnServiceTest` |
| Utilization | `MonthlyUtilizationAccuracyTest` |

### 8.4 Postman

Collection and local environment:

- `heavy-rental-spring-rest-api/postman/Heavy-Rental-Spring-REST-API.postman_collection.json`
- `heavy-rental-spring-rest-api/postman/Heavy-Rental-Spring-REST-API.postman_environment.json`
- Guide: [`heavy-rental-spring-rest-api/postman/README.md`](heavy-rental-spring-rest-api/postman/README.md)

Recommended order: Health → Auth (interim → login) → domain folders. Default seed user in the environment is typically `alex.tan@example.sg` / `customer123`.

---

## 9. Design-only / not built

Documented in OpenSpec active changes; **not implemented** in the current codebase:

| Item | Notes |
|------|--------|
| `POST /api/pricing/estimate` | Design under `openspec/changes/pricing-estimate/` — Spring-only arithmetic by design, not a Haystack proxy |
| Plan → booking checkout conversion | Design under `openspec/changes/rental-plan-checkout-conversion/` — richer `rentalPlanId` checkout behavior proposed |
| Haystack-backed `PricingClient` | Rental plan quote remains local `DefaultPricingClient` |
| Real Depot resource | `/api/depots` is an empty stub |

Do not treat design docs as as-built API behavior.

---

## 10. Related documentation

| Path | Contents |
|------|----------|
| [`heavy-rental-spring-rest-api/openspec/project.md`](heavy-rental-spring-rest-api/openspec/project.md) | OpenSpec index and constitution |
| [`heavy-rental-spring-rest-api/openspec/specs/api-index/contracts/routes.md`](heavy-rental-spring-rest-api/openspec/specs/api-index/contracts/routes.md) | Living route map |
| [`heavy-rental-spring-rest-api/openspec/specs/`](heavy-rental-spring-rest-api/openspec/specs/) | Per-capability behavior + contracts |
| [`heavy-rental-spring-rest-api/openspec/specs/haystack-recommender/contracts/portal-api.md`](heavy-rental-spring-rest-api/openspec/specs/haystack-recommender/contracts/portal-api.md) | Portal recommender request/response detail |
| [`heavy-rental-spring-rest-api/openspec/changes/2026-08-20-call2-quote-quantity-passthrough/`](heavy-rental-spring-rest-api/openspec/changes/2026-08-20-call2-quote-quantity-passthrough/) | FR-S2B-011 Call 2 quantity pass-through (OpenSpec + OpenSPDD + ADR) |
| [`heavy-rental-spring-rest-api/openspec/specs/spring-proxy-endpoints/spec.md`](heavy-rental-spring-rest-api/openspec/specs/spring-proxy-endpoints/spec.md) | Which routes hop to Haystack |
| [`heavy-rental-spring-rest-api/Feasibility_Study_Spring/`](heavy-rental-spring-rest-api/Feasibility_Study_Spring/) | Spring ↔ Haystack wire notes and handoff |
| [`heavy-rental-spring-rest-api/postman/`](heavy-rental-spring-rest-api/postman/) | Manual API collection |
| [`heavy-rental-spring-rest-api/CHANGELOG.md`](heavy-rental-spring-rest-api/CHANGELOG.md) | Release notes |

---

## Quick process cheat sheet

```text
Auth:     getBearerToken → login → (API calls) → logout
Plan:     create plan → add items → quote (Spring math)
Book:     create booking (30% deposit) → deposit-intent → Stripe pay → webhook
Ops:      CONFIRMED → deliver MOBILISED → return COMPLETED
AI:       project-spec (Call1+Call2 quote) → knowledge-query (Call3) → get session
Admin:    /api/users , /api/monthly-utilization
```

---

*Generated as as-built project documentation for the Heavy Rental Spring REST API. Prefer OpenSpec contracts when implementing or changing behavior.*
