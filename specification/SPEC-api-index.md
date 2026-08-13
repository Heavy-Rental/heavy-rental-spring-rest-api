# Specification: REST API Index

| Field | Value |
|-------|--------|
| **Document type** | Cross-cutting index — not a feature contract itself |
| **Status** | As-built across `develop` (which now includes former `HR-72`/`HR-80` work — see §3.1 — and, as of PR #37/commit `13ec76c`, `36-link-rest-api-users-to-front-end` — see §2.7) + `hr-27-payment-checkout` (§2.4, local/unpushed) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related specs** | [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md), [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md), [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md), [`SPEC-entity-repository.md`](./SPEC-entity-repository.md), [`SPEC-stripe.md`](./SPEC-stripe.md), [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md), [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md), [`SPEC-admin-users-api.md`](./SPEC-admin-users-api.md) (§2.7 — merged, see below), [`SPEC-pricing-estimate.md`](./SPEC-pricing-estimate.md) (§2.5.2 — design only, not built), [`SPEC-spring-proxy-endpoints.md`](./SPEC-spring-proxy-endpoints.md) (every route that does, or deliberately doesn't, call `haystack-fast-api`) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |

This document is the **single place to see the entire REST surface** — every route, which client it's for, what branch it lives on, and which feature spec (if any) owns its detailed contract. It does not restate request/response shapes already documented elsewhere; it points to them.

Each feature-scoped SPEC (auth, equipment, …) remains the source of truth for *its own* contract, per this project's existing convention (see e.g. `SPEC-request-bearer-token.md` §7, which was deliberately split out of a combined file to keep contracts independently owned). This index exists only to solve a different problem: with contracts scattered one-per-file, there was no single place to answer "what's the full API surface, and who's it for" — that gap is what this file closes.

**When a route is added, removed, or reassigned to a different client, update this index in the same change set** — same discipline `SPEC-entity-repository.md` already commits to for its own content.

---

## 1. Status legend

| Status | Meaning |
|---|---|
| ✅ Merged → `develop` | Live on `develop` today |
| 🧪 Branch `hr-27-payment-checkout` (local) | Rebased onto `develop` locally as of 2026-08-11; not yet pushed to `origin` or merged. Carries `develop`'s full route set (see §3.1) plus the payments routes in §2.2 |
| 🧱 Stub | Route exists and returns `200`, but has no real backing entity/logic — placeholder so a frontend call doesn't 404 |
| ⏳ Not started | Branch name/intent exists; no code written yet |
| 🔀 Branch (local/unmerged) | Route exists and is fully implemented + verified on the named branch, not yet merged to `develop` |

---

## 2. Endpoint index

### 2.1 Auth — shared by both clients

| Method | Path | Client | Roles allowed | Status | Contract |
|---|---|---|---|---|---|
| `GET` | `/api/auth/getBearerToken` | Shared | Public | ✅ Merged → `develop` | [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md) |
| `POST` | `/api/auth/login` | Shared | `ROLE_INTERIM` | ✅ Merged → `develop` | [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) |
| `POST` | `/api/auth/logout` | Shared | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) |

There is currently **one** login flow. No `platform`/`audience` field or web-vs-mobile distinction exists anywhere in the request, the JWT claims, or `SecurityConfig` — see §4.

**`POST /api/auth/logout` behavior, in brief (added 2026-08-13 — this route's server-side effect was previously only inferable by following the contract link, not visible from this index):** it is real **server-side token invalidation**, not a no-op and not merely an audit log. Confirmed against `AuthService.logout`/`TokenDenylist.java`: the caller's access-token `jti` is added to an in-memory denylist (keyed by `jti`, entry expires at the token's original `exp`), and `JwtDecoder` rejects any subsequently-presented denylisted `jti` — so the exact same access token cannot be reused after logout, verified end-to-end in `SPEC-auth-login-logout.md` §8 (reuse after logout → `401`). The denylist is process-local/in-memory (`SPEC-auth-login-logout.md` §10.3) — it does not survive an app restart and isn't shared across instances; that limitation is pre-existing and unchanged by this note. Full contract: [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) §4.2 (denylist rules), §7.3 (logout processing), §6.2 (wire contract).

### 2.2 Mobile (+ two shared routes) — bookings, deliveries, returns, payments

Per branch author: every route in this section was filed as **mobile**-only. **Corrected 2026-08-13** (web-portal audit): the React web portal calls `POST /api/bookings` and `POST /api/payments/deposit-intent` directly — confirmed against the portal's own request code, not inferred. Both are reclassified `Shared (mobile+web)` in the table below; the remaining eight routes are unaffected and stay mobile-only. Nothing server-side changes — `SecurityConfig`'s blanket rule already covers both clients equally (§4) — this is a documentation-ownership correction only.

| Method | Path | Client | Roles allowed | Status | Contract |
|---|---|---|---|---|---|
| `POST` | `/api/bookings` | **Shared (mobile+web)** | `ROLE_USER`, `ROLE_ADMIN` (caller becomes the booking's customer) | 🧪 Branch `hr-27-payment-checkout` (local) | §2.2.1 below |
| `GET` | `/api/bookings` | Mobile | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) §5.2 |
| `GET` | `/api/bookings/{bookingId}` | Mobile | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `PUT` | `/api/bookings/{bookingId}` | Mobile | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `GET` | `/api/deliveries` | Mobile | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `PATCH` | `/api/deliveries/{bookingId}/status` | Mobile | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `GET` | `/api/returns` | Mobile | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `PATCH` | `/api/returns/{bookingId}/status` | Mobile | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `POST` | `/api/payments/deposit-intent` | **Shared (mobile+web)** | `ROLE_USER`, `ROLE_ADMIN` (booking owner or admin — enforced in `PaymentService`, not `SecurityConfig`) | 🧪 Branch `hr-27-payment-checkout` (local) | [`SPEC-stripe.md`](./SPEC-stripe.md) §6.1 |
| `POST` | `/api/payments/webhook` | Mobile | Public (Stripe-Signature verified) | 🧪 Branch `hr-27-payment-checkout` (local) | [`SPEC-stripe.md`](./SPEC-stripe.md) §6.2 |

All ten routes above now have a dedicated feature spec: the seven `develop`-merged ones via [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) (written per the standalone-spec criterion in `SPEC-project-environment.md` §9.1), `POST /api/bookings` via §2.2.1 immediately below, and the two payments routes via [`SPEC-stripe.md`](./SPEC-stripe.md) (previously undocumented — see the correction below). `SPEC-entity-repository.md` still documents the underlying `Booking`/`Payment`/`DeliveryRecord`/`ReturnRecord` *entities* (§3.2/§10.7 of that file), but not their REST layer.

**Payments status corrected 2026-08-11:** this table previously listed a single `POST /api/payments/create-payment-intent` route as "✅ Merged → `develop` (predates this branch — `HR-60`)" with no owning spec. That was accurate as of when it was written, but is no longer the current contract: `hr-27-payment-checkout` was rebased onto `develop` locally and, as part of that rebase, `PaymentController` was replaced wholesale with the two routes now shown above — `create-payment-intent` no longer exists anywhere in this codebase (confirmed: no remaining references to it in any `.java` file). This was a deliberate choice, not an accident of the merge: the old endpoint trusted a client-supplied payment amount with no server-side validation against the booking's real price, and nothing else in the codebase called it. The replacement (`deposit-intent`) computes the amount server-side from `Booking.depositAmount` instead. Full contract, and the gaps introduced by reconciling this branch's payment code with `develop`'s booking model, are in [`SPEC-stripe.md`](./SPEC-stripe.md), which previously had no entry in this index at all.

**Booking creation gap closed 2026-08-11.** `POST /api/bookings` now exists on this branch, resolving what was previously the single biggest blocker to a real end-to-end flow (browse → book → pay deposit).

#### 2.2.1 `POST /api/bookings`

Request (`CreateBookingRequest`): `{ items: [{ assetId }], startDate, endDate, rentalPlanId?, siteAddress, deliveryNotes }`. One or more `assetId`s, a shared date window across all items (matches `SPEC-ui-heavy-machinery-portal.md`'s multi-item-per-booking model), an optional `rentalPlanId`, plus free-text `siteAddress`/`deliveryNotes`. No `sitePostalCode` field — it's a computed `@Formula` column on `Booking`, extracted from `siteAddress`, not client-settable.

**`siteAddress` validation (HR-116).** `BookingController.createBooking` is now `@Valid`-annotated; `CreateBookingRequest.siteAddress` carries `@NotBlank` plus `@Pattern(regexp = "^.*\\d{6}$")` — must end with a 6-digit postal code (e.g. `"20 Jurong Port Road, 619094"`), stripped of leading/trailing whitespace in a compact canonical constructor. A violation returns `400 {"error":"validation_failed", ...}` (new `RestExceptionHandler.handleValidation(MethodArgumentNotValidException)` handler) before any of the server-side steps below run — no availability check, no `Booking`/`BookingItem` row is created. The identical constraint was added in the same change to `BookingUpdateRequest` (`PUT /api/bookings/{id}`, §2.2 above) — see `SPEC-booking-delivery-return-api.md` §9 1.2.0 — and to `RentalPlanCreateRequest` on `POST /api/rentalPlans`, per `SPEC-rental-plan-quote.md` §9 1.3.0. That last route has no row of its own in §2.3 below — see the pre-existing gap noted there — so this pointer goes straight to its owning spec instead. New Maven dependency: `spring-boot-starter-validation`.

Response: the existing flat `BookingResponse` (§2.2's `GET`/`PUT` shape), now extended with `totalAmount`/`depositAmount`/`remainingBalance` — additive fields, non-breaking for existing `GET`/`PUT` consumers.

Server-side behavior, in order:
1. Resolves the caller to a `User` via `CurrentUserService` (same JWT-subject-to-email lookup `PaymentService` already uses) — that user becomes the booking's customer. No "book on behalf of another customer" support.
2. Validates: at least one item, both dates present, `endDate` after `startDate`.
3. Resolves every `assetId` — `404` if any doesn't exist.
4. **Availability check** — queries `BookingItemRepository.findAssetIdsWithOverlappingBooking` (the same query and the same `Booking.ACTIVE_STATUSES` list `AssetService`'s `available` flag on `GET /api/equipment` already uses, now promoted from a private constant on `AssetService` to a shared `public static final` on `Booking` itself so the two can't drift apart) — `409 Conflict` naming the specific asset id(s) already booked over the requested window, rather than silently double-booking.
5. Computes `totalAmount` as the sum of each asset's `baseDailyRate × days` (days = `ChronoUnit.DAYS.between(startDate, endDate)`, minimum 1), and `depositAmount`/`remainingBalance` as a 30%/70% split — the same `DEPOSIT_RATE` constant and rounding (`HALF_UP`, 2dp) `PaymentService`/`SPEC-stripe.md` already assumed lived here (§4.3 of that spec: *"the deposit rate... lives in `BookingService` at booking-creation time"* — this endpoint is that promise fulfilled).
6. Sets the new booking's `status` to `PENDING_DEPOSIT` (`Booking.ACTIVE_STATUSES`' first value — correctly makes the booked asset unavailable for other bookings immediately, before any payment happens).
7. Persists the `Booking` and its `BookingItem` rows in one transaction.

**Verified against a running instance** (not just compiled): a real booking create → the resulting real `bookingId` fed into `POST /api/payments/deposit-intent` → reached Stripe and failed only on the placeholder API key (`YOUR_STRIPE_SECRET_KEY_HERE`), i.e. every layer up to the actual external Stripe call is proven wired correctly end-to-end. Also verified: re-booking the same asset over overlapping dates → `409` with the conflicting asset id; missing items → `400`; nonexistent asset → `404`.

**A pre-existing, unrelated bug was found and fixed while verifying this**: `data.sql`'s seed data uses explicit primary keys (`INSERT INTO bookings (id, ...) VALUES (1, ...), (2, ...)...`) for every table except `users`, and PostgreSQL identity sequences aren't advanced by explicit-value inserts — so the very first runtime `INSERT` via `IDENTITY` generation on any of those tables collided with an already-seeded row (`duplicate key value violates unique constraint`). `users` already had a `SELECT setval(...)` fix for this; the same one-line fix was added for the other 12 seeded tables (`asset_categories`, `assets`, `asset_images`, `rental_plan`, `rental_plan_records`, `bookings`, `booking_items`, `payments`, `delivery_records`, `return_records`, `ai_recommendations`, `recommendation_items`). This wasn't only blocking `POST /api/bookings` — `POST /api/equipment` (§2.3, already documented as "✅ Merged → `develop`") was equally broken by the same root cause and is fixed by the same change; confirmed by reproducing the failure on both endpoints before the fix and re-testing both after.

### 2.3 Web — equipment browse, depots, rental plans

Per branch author: every route in this section is for the **web** client. `EquipmentController`, `DepotController`, and `RentalPlanController` originated on `origin/HR-72-add-browse-equipment-to-rest-api`, which diverged from `develop` at the same commit `HR-80` did (`584346f`, "HR-66 Populating Data") — but per §3.2, `HR-72` merged into `develop` via `692ece6` (PR #12) before `HR-80` landed, so all three controllers are live in the current working tree today. `RentalPlanController` in particular is no longer the stub this section originally described — see its full route set below and [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md).

| Method | Path | Roles allowed | Status | Contract |
|---|---|---|---|---|
| `GET` | `/api/equipment` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) §7.1 |
| `GET` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.2 |
| `POST` | `/api/equipment` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.3 |
| `PUT` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.4 |
| `PATCH` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.4 |
| `DELETE` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.5 |
| `GET` | `/api/depots` | `ROLE_USER`, `ROLE_ADMIN` | 🧱 Stub, merged → `develop` | None — always returns `[]`; no `Depot` entity exists (delivery site fields live on `Booking`/`RentalPlan` directly). Comment in `DepotController.java` notes this exists specifically so the React portal's `CustomerPortal` (which errors its whole equipment page if either `/api/equipment` or `/api/depots` fails) doesn't break |
| `POST` | `/api/rentalPlans` | `ROLE_USER`, `ROLE_ADMIN` (caller becomes the plan's customer) | ✅ Merged → `develop` | [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md) |
| `GET` | `/api/rentalPlans` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | Same |
| `GET` | `/api/rentalPlans/{id}` | `ROLE_USER`, `ROLE_ADMIN`, owner | ✅ Merged → `develop` | Same |
| `POST` | `/api/rentalPlans/{id}/items` | `ROLE_USER`, `ROLE_ADMIN`, owner | ✅ Merged → `develop` | Same |
| `DELETE` | `/api/rentalPlans/{id}/items/{itemId}` | `ROLE_USER`, `ROLE_ADMIN`, owner | ✅ Merged → `develop` | Same |
| `POST` | `/api/rentalPlans/{id}/quote` | `ROLE_USER`, `ROLE_ADMIN`, owner | ✅ Merged → `develop` | Same |
<<<<<<< HEAD
=======

**Gap noticed while updating this doc for HR-116, not fixed here (out of scope for that change):** the row above documents a kebab-case `GET /api/rental-plans` stub, and this section's intro paragraph still says `RentalPlanController` "lives only on `origin/HR-72-...`, not merged." Both are stale — `hr-19-request-quote` (`SPEC-rental-plan-quote.md`) replaced that stub outright with a real, fully-implemented `RentalPlanController` at camelCase `/api/rentalPlans` (`POST`/`GET`/`GET {id}`/`POST {id}/items`/`DELETE {id}/items/{itemId}`/`POST {id}/quote`), and it's merged to `develop` today (confirmed: `git log develop -- .../RentalPlanController.java` → `d12546d`, "Hr 19 request quote (#17)"). This table has never been updated to reflect that merge, so it undercounts the real route surface — including the `siteAddress`-validation route this section's own §2.2.1 note above now points at. Recommended fix (not applied): replace this row with the six real `/api/rentalPlans` routes, contract → `SPEC-rental-plan-quote.md`.
>>>>>>> develop

No per-route restriction distinguishes admin-only write access on the equipment routes — any authenticated `ROLE_USER` or `ROLE_ADMIN` can create/edit/delete equipment, not just admins. Same blanket-rule caveat as everywhere else in this index (see §4).

**`POST /api/rentalPlans/{id}/quote` does not call Haystack (corrected 2026-08-13).** A web-portal-facing reference document had separately stated this route proxies to Haystack; verified against `RentalPlanService`/`PricingClient`/`DefaultPricingClient` and found incorrect — see [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md) §5.0 for the full correction. Today this route is pure Spring-side arithmetic (`baseDailyRate × days`, summed from line items). A Haystack-backed `PricingClient` is designed but **not built** (same file, §5.1). Every Spring→Haystack proxy point (built or designed) is now tracked in one place: [`SPEC-spring-proxy-endpoints.md`](./SPEC-spring-proxy-endpoints.md).

### 2.4 Local, unpushed — `hr-27-payment-checkout` and `hr-40-equipment-utilization-tracker`

`hr-27-payment-checkout` (see §2.2's payments rows) exists only on the machine it was rebased on as of 2026-08-11 — 8 commits ahead of `origin/hr-27-payment-checkout`, not pushed, not merged. It carries `develop`'s full route set (§2.1–§2.3) unchanged, plus the two payments routes and one fix made directly on this branch (CORS, below), not on `develop`. Two things worth knowing before treating this as equivalent to a `develop` merge:

- **CORS fixed 2026-08-11.** `SecurityConfig` now wires a real `CorsConfigurationSource` bean (`.cors(cors -> cors.configurationSource(corsConfigurationSource()))`, replacing the previous no-op `.cors(Customizer.withDefaults())`), scoped to `/api/**`, allowing `GET/POST/PUT/PATCH/DELETE/OPTIONS` and the `Authorization`/`Content-Type` headers. Allowed origins are configurable via `app.cors.allowed-origins` / `APP_CORS_ALLOWED_ORIGINS` (new `CorsProperties` record, comma-separated), defaulting to `http://localhost:5173,http://localhost:4173` (Vite dev/preview) for local development. **Deliberately no wildcard origin default** — deployment must set `APP_CORS_ALLOWED_ORIGINS` to the real deployed frontend origin(s); nothing here guesses that value. Verified directly against a running instance: a preflight `OPTIONS /api/equipment` from an allowed origin returns `200` with `Access-Control-Allow-Origin` set; from a disallowed origin it returns a flat `403 Invalid CORS request`, rejected by the CORS filter before Spring Security's auth layer even runs.
- `Booking.paidStatus` was deleted from this branch during the rebase (a `develop` commit, `8bdf067`, had already removed it and folded payment state into `BookingStatus` instead — this branch's Stripe code hadn't caught up). Payment endpoints in §2.2 work, but several state transitions they used to perform no longer happen — see [`SPEC-stripe.md`](./SPEC-stripe.md) §10 for the specifics.

`hr-40-equipment-utilization-tracker` is this branch, per the header table. It adds one admin-only route, backing the React portal's admin Overview screen:

| Method | Path | Roles allowed | Status | Contract |
|---|---|---|---|---|
| `GET` | `/api/monthly-utilization` | `ROLE_ADMIN` only | ✅ Committed (`8227447`), local to this branch | None — see [`CHANGES-monthly-utilization.md`](./CHANGES-monthly-utilization.md) |

Returns the trailing 6 calendar months' `{id, month, utilization, revenue}` (`MonthlyUtilizationService.getTrailingSixMonths()`). `revenue` sums successful `Payment`s per month; `utilization` sums per-`BookingItem` day-overlap against active-status bookings (`CONFIRMED`/`MOBILISED`/`COMPLETED`) as a percentage of (asset count × days in month). Gated by `SecurityConfig`'s `.requestMatchers("/api/monthly-utilization").hasAuthority("ROLE_ADMIN")` — the one route in this index that isn't reachable by a plain `ROLE_USER` token. Verified against a running instance (§2 of the linked change log): `401` with no token, `403` for `ROLE_USER`, `200` for `ROLE_ADMIN`, and cross-checked against seed data end to end through the real portal (not the mock server).

### 2.5 Planned, not started

| Item | Status | Notes |
|---|---|---|
| `platform` attribute on `LoginRequest` | ⏳ Not started | Branch `HR-85-implement-platform-attribute-in-login-request-body` exists but has **zero commits beyond `HR-77`** — it's an unstarted placeholder, not a design that's been written down anywhere yet. Likely the intended mechanism for distinguishing web vs mobile at login (see §4) once work begins. |

**Removed 2026-08-13:** this table previously also listed `POST /api/pricing/estimate` and `POST /api/recommendations` as "⏳ Not started." Neither claim held at the time: `/api/pricing/estimate` had never been built and had no matching Haystack endpoint to proxy — removed rather than left as a phantom placeholder. `/api/recommendations` was actually already implemented — under the real path `/api/recommendations/project-spec`, not bare `/api/recommendations` — see §2.6 below, which had already superseded this row without this row being deleted. (`SPEC-spring-proxy-endpoints.md`, the file that originally proposed both, was itself retired the same day — see [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md) §5.1/1.3.0 and [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md), which absorbed everything in it still relevant.) **`/api/pricing/estimate` reinstated below (2.5.2) as new, unrelated work** — a deliberate, Spring-only, non-Haystack pricing endpoint, not a resurrection of the phantom this note describes.

### 2.5.1 Admin UI — write routes with no documented plan (not planned)

**Added 2026-08-13** (web-portal audit): the admin UI needs write access on three resources where the backend currently only exposes reads (or nothing at all). Checked every branch in this repository (`git branch -a`, `git grep` for `@PutMapping`/`@PatchMapping`/`@DeleteMapping` across each) — none of the three below exist on any branch, merged or not, and no ticket/branch name references them. That makes them different from `HR-85` in §2.5 above (an unstarted branch at least *exists*, marking intent): these have **no plan anywhere**, not even a placeholder branch. Documented here as **explicitly not planned**, per the standing convention that a route gap must be recorded as either planned or not, never left silent.

| Route(s) | Client | Status | Notes |
|---|---|---|---|
| `POST`/`PUT`/`PATCH`/`DELETE` `/api/depots` | Web (admin) | 🚫 Not planned | `DepotController` is a read-only stub (§2.3) — there is no `Depot` entity to write to; delivery-site fields live directly on `Booking`/`RentalPlan`. Adding writes here would mean designing a real `Depot` entity first, not just a new route. |
| `PUT`/`PATCH`/`DELETE` `/api/rentalPlans/{id}` | Web (admin) | 🚫 Not planned | `RentalPlanController` (§2.3) has no admin-facing edit/cancel/delete on a plan itself — only customer-facing create/list/get/add-item/remove-item/quote (`SPEC-rental-plan-quote.md`) exist. |
| `PATCH`/`DELETE` `/api/bookings/{id}` | Web (admin) | 🚫 Not planned | `BookingController` (§2.2) has `GET`/`PUT` only. `PUT` is a full-replace (§5, known issue), not a partial admin edit; there is no `DELETE`/cancel at all. |

Recorded here rather than silently left out of this index, matching this index's own stated purpose (§0: "every route... which client it's for"). If/when the admin UI's write requirements for these three are scoped, replace the 🚫 row with a real branch/ticket reference per the §2.5 convention above.

### 2.5.2 `POST /api/pricing/estimate` — new, Spring-only (not the removed phantom)

See [`SPEC-pricing-estimate.md`](./SPEC-pricing-estimate.md) (new, 2026-08-13) for the full design. Distinct from both the phantom this section removed above and from `POST /api/rentalPlans/{id}/quote` (§2.3 — despite an external reference's claim otherwise, that route is *not* Haystack-backed either, see §5.0 correction in `SPEC-rental-plan-quote.md`) — this route is deliberately **pure Spring arithmetic, no Haystack call**, for a caller that wants a price without first creating/owning a `RentalPlan`.

| Method | Path | Client | Status | Contract |
|---|---|---|---|---|
| `POST` | `/api/pricing/estimate` | Web | ⏳ Not started — design only | [`SPEC-pricing-estimate.md`](./SPEC-pricing-estimate.md) |

### 2.6 Web — recommender (S2b as-built)

Phase 2 / **S2b**: resilient Spring client for `haystack-fast-api`, saga, and thin portal REST. **As-built 2026-08-12** (Feasibility v2 Call 1/2/3) — see [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md).

| Method | Path | Client | Roles allowed | Status | Contract |
|---|---|---|---|---|---|
| `POST` | `/api/recommendations/project-spec` | Web | `ROLE_USER`, `ROLE_ADMIN` | ✅ Implemented (S2b) | §5.1 — **Call 1 then Call 2 recommend**; portal body includes `quoteRef` / `items` |
| `POST` | `/api/recommendations/{recommendationId}/knowledge-query` | Web | Owner or `ROLE_ADMIN` | ✅ Implemented (S2b) | §5.2 — **Call 3** chatbot Q&A only |
| `GET` | `/api/recommendations/{recommendationId}` | Web | Owner or `ROLE_ADMIN` | ✅ Implemented (S2b) | §5.3 — DB session only |

**Haystack orchestration on submit:**  
`POST /api/recommendations/project-spec` →  
1. Call 1 `POST /internal/v1/recommendations/submitprojectspecification`  
2. Call 2 `POST /internal/v1/recommendations/project-knowledge/getassetrecommendations`  
then returns session handles + **Call 2 quote**.  
Follow-up chatbot: Call 3 `POST .../project-knowledge/query` via knowledge-query. Also `GET /health` on the client.

### 2.7 Admin — user management (`/api/users`, merged into `develop` 2026-08-13)

Per branch author: this route family is for the **admin operations portal** (Users tab). It's the one place in the whole API gated `ROLE_ADMIN` alone — every other route in this index uses `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")`.

**History:** flagged missing entirely from this index by a 2026-08-13 web-portal audit, at which point `/api/users` existed only on unmerged remote branch `origin/36-link-rest-api-users-to-front-end` (commit `e79742d`) — `SPEC-admin-users-api.md` was copied into this repo's `specification/` folder to close the documentation gap while the code itself remained absent from this working tree. That branch has since **merged into `develop` via PR #37** (commit `13ec76c`, "36 link rest api users to front end") and `develop` merged into this branch — the code (`UserController`, `UserAdminService`, `User*` DTOs, the `SecurityConfig` matcher) is now real and present, not just documented.

| Method | Path | Client | Roles allowed | Status | Contract |
|---|---|---|---|---|---|
| `GET` | `/api/users` | Web (admin) | `ROLE_ADMIN` only | ✅ Merged → `develop` (PR #37) | [`SPEC-admin-users-api.md`](./SPEC-admin-users-api.md) |
| `GET` | `/api/users/{id}` | Web (admin) | `ROLE_ADMIN` only | Same | Same |
| `POST` | `/api/users` | Web (admin) | `ROLE_ADMIN` only | Same | Same |
| `PATCH` | `/api/users/{id}` | Web (admin) | `ROLE_ADMIN` only | Same | Same |
| `DELETE` | `/api/users/{id}` (soft-delete via `enabled=false`) | Web (admin) | `ROLE_ADMIN` only | Same | Same |

No `UserController`/`UserAdminService`/user-management DTOs existed anywhere in this codebase before this branch — confirmed by searching the full git history and every other branch. Full contract, role-string mapping, and live verification results in [`SPEC-admin-users-api.md`](./SPEC-admin-users-api.md) (that file's own header now reflects the merge too). Same `ROLE_ADMIN`-alone precedent as `/api/monthly-utilization` (§2.4).

---

## 3. Correcting assumptions this index was built to check

### 3.1 Web auth is not separately implemented

Before drafting this file, the working assumption was that **web auth was already built separately in `HR-72`**. Checked directly against that branch's diff and its own spec (`SPEC-equipment-browse-api.md` §2.2, §9): **HR-72 makes zero changes to `Authentication.java`, `AuthService`, `SecurityConfig`, or `LoginRequest`.** Its own spec says so explicitly: *"No `SecurityConfig` changes — the existing catch-all `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` rule already covers these new routes."* Web and mobile, as of both branches today, authenticate through the **exact same** `getBearerToken` → `login` → `logout` flow in §2.1. There is no second, web-specific auth implementation anywhere in this repository's history.

### 3.2 `HR-72` and `HR-80` are both merged into `develop` — §2.2/§2.3 previously said otherwise

This index originally (1.0.0–1.2.0) described `HR-80` and `HR-72` as two independent, unmerged sibling branches, each diverging from the same commit (`584346f`) and never reconciled with each other. That was already inaccurate by the time it was written: `HR-72`'s equipment/depot/rental-plan work merged into `develop` via `692ece6` ("include the relevant class to link browse equipment to the web portal", PR #12) *before* `HR-80` (`c081ee1`) landed on top of it — so `develop` today (and everything built on top of it, including `hr-27-payment-checkout` in §2.5) already has both. §2.2 and §2.3's status columns are corrected accordingly as of 2026-08-11. No route was lost or needs reconciling between these two — they never actually competed for the same files.

---

## 4. Web/mobile separation — current state and open question

**Current state:** none. One JWT scheme, one role model (`ROLE_USER`/`ROLE_ADMIN`/`ROLE_DRIVER` from `User.role`), one blanket `SecurityConfig` rule (`anyRequest().hasAnyAuthority("ROLE_USER","ROLE_ADMIN")`) covering every business route regardless of which client it's "for." Nothing today stops a web session token from calling `/api/deliveries/{id}/status`, or a mobile session token from calling `/api/equipment`. The web/mobile split in §2.2/§2.3 is a documentation convention (which team owns which route), not an enforced boundary.

One concrete consequence worth flagging here since it sits exactly on this seam: **`ROLE_DRIVER` cannot call any protected route today**, including the mobile delivery/return endpoints in §2.2 that `DeliveryRecord`/`ReturnRecord` (`SPEC-entity-repository.md` §5.10–5.11) were modeled around. `data.sql` seeds a `DRIVER` user (`Ah Tan`) specifically as the `driver_id` on those tables, but `SecurityConfig`'s blanket rule only grants `ROLE_USER`/`ROLE_ADMIN`. If the mobile client's actual end users include drivers, this blocks them outright — independent of any web/mobile question, this is a bug worth its own fix.

**Open question — should separation be enforced?** Not resolved here; recorded so it isn't lost. Two considerations worth weighing before deciding:

- Enforcing it would mean tagging tokens with a client/audience claim (this is likely what the still-unstarted `HR-85` `platform` field is for) and adding per-route matchers in `SecurityConfig` keyed off it — a real design change, not a doc change.
- The role model (`USER`/`ADMIN`/`DRIVER`) may already be doing most of the job a client-based split would do, if mobile's real audience is drivers and web's is customers/admins — in which case fixing the `ROLE_DRIVER` gap above and authorizing by role (as `SecurityConfig` already does) could make a separate client dimension redundant. Worth confirming who mobile's actual users are before building a second axis of access control alongside role.

Recommendation if/when this gets picked up: don't build the enforcement until that question is answered — it's easy to add a claim + matcher later, harder to unwind if it's added speculatively and turns out to duplicate the role check.

---

## 5. Known issues — mobile endpoints (§2.2)

Moved to [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) §6, which is now the primary write-up for these (role/ownership checks, multi-asset data loss, missing `DeliveryRecord`/`ReturnRecord` persistence, N+1 queries, plus one added since — `PUT /api/bookings/{id}`'s full-replace-not-partial-merge semantics). Originally verified and recorded here from a PR review; relocated once the booking/delivery/return feature got its own spec, since re-explaining the same findings in two files risks them drifting apart the way `SPEC-entity-repository.md`'s controller-wiring claims drifted from the code before this change (§6 below). Kept as a one-line pointer here rather than a full section for that reason.

---

## 6. Companion spec corrections made in this change

`SPEC-entity-repository.md` and `SPEC-seed-data.md` contained claims that predate this branch's controllers (and, in two cases, predate even `develop`'s current state). Both were corrected in this same change set per each file's own "update together" convention — see their change-control tables for specifics. Summary:

- `SPEC-entity-repository.md` §3.2 / §10.7 said *"no entity beyond `User` is wired to a controller"* — false today: `Booking` (this branch) and `Payment` (already on `develop` via `HR-60`, never documented) both have live controllers.
- `SPEC-entity-repository.md` said `ddl-auto=create-drop` (four places) — the project has run `ddl-auto=update` since `SPEC-seed-data.md`'s seeding design was built; the entity-repository doc was never updated to match.
- `SPEC-entity-repository.md` §5.7/§6.2 and `SPEC-seed-data.md` §6.6 still listed `Booking.BookingStatus` as `PENDING, CONFIRMED, MOBILISED, COMPLETED, CANCELLED` and documented a `Booking.PaidStatus` field — both changed by `HR-77` (already merged to `develop`, so this drift predates `HR-80`), which split `PENDING` into `PENDING_DEPOSIT`/`PENDING_CONFIRMED` and removed `PaidStatus` entirely.

**Known git-conflict note:** `HR-72` independently edited the same §3.2 sentence, the same §8 repository-catalog table, and the same §10 note #7 in `SPEC-entity-repository.md` (to add its own `Asset`/`Equipment` correction), and also edited `SPEC-seed-data.md`. Both branches' edits are additive corrections to the same stale claims, for different entities — expect a textual merge conflict when `HR-72` and `HR-80` are reconciled, resolved by keeping both branches' additions rather than picking one side.

---

## 7. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.0.0 | 2026-08-09 | Initial index: consolidates auth (`develop`), bookings/deliveries/returns/payments (`HR-80`, this branch), and equipment/depots/rental-plans (`HR-72`, unmerged sibling branch) into one endpoint table with client ownership, role gates, and branch status. Documents the web/mobile separation as an open, unresolved question rather than deciding it. Corrects the mistaken premise that `HR-72` includes a separate web auth implementation. |
| 1.1.0 | 2026-08-09 | Added §5, a verified known-issues backlog for the mobile endpoints from a PR review: no role/ownership checks on booking/delivery/return routes (5.1), silent multi-asset data loss in `GET /api/deliveries`/`GET /api/returns` via `BookingMapper.primaryAsset()` — reproducible today against seed booking id 1 (5.2), `DeliveryRecord`/`ReturnRecord` never persisted, cross-referenced from `SPEC-entity-repository.md` §3.2 (5.3), and N+1 queries on the three list endpoints (5.4). Documentation only — none of these were fixed in this change; each item records its own recommended fix and deferral status. Renumbered old §5/§6 to §6/§7 accordingly. |
| 1.2.0 | 2026-08-09 | New [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) written (per the standalone-spec criterion added to `SPEC-project-environment.md` §9.1) to be the actual contract for the seven `HR-80` routes in §2.2, which previously had none. §2.2's `Contract` column now points to it instead of showing `—`. §5's detailed known-issues writeup moved there (that file's §6) and replaced here with a one-line pointer, to avoid maintaining the same findings in two places. |
| 1.3.0 | 2026-08-11 | `hr-27-payment-checkout` rebased onto `develop` locally (§2.4, new). Corrected two stale claims found in the process (§3.2, new): `HR-72` and `HR-80` are both already merged into `develop` (§2.2/§2.3 status columns updated from `🔀 Branch HR-80`/`🔀 Branch HR-72` to `✅ Merged → develop` throughout), not the unmerged sibling branches this index previously described. §2.2's payments row replaced: `POST /api/payments/create-payment-intent` (`HR-60`, client-supplied amount, no owning spec) no longer exists anywhere in the codebase as of this rebase — replaced by `POST /api/payments/deposit-intent` and `POST /api/payments/webhook`, now documented in full by [`SPEC-stripe.md`](./SPEC-stripe.md) (added to `Related specs` above; this index previously didn't reference it at all). Added the CORS-not-configured blocker and the booking-creation gap, both cross-cutting concerns affecting the whole route surface rather than one feature, so they belong here rather than only in `SPEC-stripe.md`. |
| 1.4.0 | 2026-08-11 | **CORS blocker (§2.4) fixed on this branch.** Added `CorsProperties` (`config/CorsProperties.java`, `app.cors.allowed-origins`/`APP_CORS_ALLOWED_ORIGINS`, comma-separated, default `http://localhost:5173,http://localhost:4173`) and a real `CorsConfigurationSource` bean in `SecurityConfig`, replacing the previous no-op `.cors(Customizer.withDefaults())`. Scoped to `/api/**`; allows `GET/POST/PUT/PATCH/DELETE/OPTIONS` and `Authorization`/`Content-Type` headers; no wildcard origin. Verified against a running instance, not just compiled: allowed-origin preflight returns `200` with `Access-Control-Allow-Origin`, disallowed-origin preflight returns `403`. §2.4's CORS bullet updated from "not configured" to describe the fix; the paidStatus gap in the same section is unaffected and still open. |
| 1.5.0 | 2026-08-11 | **Booking-creation gap (§2.2) closed.** `POST /api/bookings` implemented — new §2.2.1 documents the full contract (request/response shapes, server-side validation order, availability-conflict check reusing `AssetService`'s own `Booking.ACTIVE_STATUSES` — promoted from a private `AssetService` constant to a shared field on `Booking` so the two can't drift — and the 30%/70% deposit split `SPEC-stripe.md` §4.3 already assumed lived here). `BookingResponse` extended with `totalAmount`/`depositAmount`/`remainingBalance` (additive, non-breaking for existing `GET`/`PUT` consumers). Verified end-to-end against a running instance: created a real booking, fed its real `bookingId` into `POST /api/payments/deposit-intent`, confirmed every layer up to the actual Stripe API call is correctly wired (fails only on the placeholder API key). Also found and fixed, while verifying: `data.sql` seeds 12 of 13 tables with explicit primary keys and no matching `setval(...)` sequence sync (only `users` had one) — the first runtime `IDENTITY`-generated insert on any of those tables collided with an already-seeded row. This silently broke `POST /api/equipment` too (§2.3, already merged to `develop`), confirmed by reproducing the failure on both endpoints before the fix and re-testing both after. |
| 1.6.0 | 2026-08-12 | **S2b recommender routes planned.** New §2.6 lists portal `POST /api/recommendations/project-spec`, `POST .../knowledge-query`, `GET .../{id}` as ⏳ Not started with contract [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md). Related specs header updated. No runtime code in this change. |
| 1.7.0 | 2026-08-12 | **S2b implemented.** §2.6 routes marked ✅ Implemented; contract status As-built. |
| 1.8.0 | 2026-08-12 | §2.6: project-spec runs Call 1 then Call 2 (`getassetrecommendations`); response carries Call 2 answer. |
| 2.0.0 | 2026-08-12 | §2.6 Feasibility v2: Call 2 = recommend quote (`quoteRef`/`items`); knowledge-query = Call 3 `.../query` chatbot. |
<<<<<<< HEAD
| 2.1.0 | 2026-08-12 | New §2.7 — `/api/users` (list/get/create/update/remove), implemented and verified live on branch `36-link-rest-api-users-to-front-end`. New `🔀 Branch (local/unmerged)` status added to §1's legend to describe it precisely (distinct from the `hr-27`-specific 🧪 symbol). Full contract in new [`SPEC-admin-users-api.md`](./SPEC-admin-users-api.md), added to `Related specs` above. Header `Status` field corrected to reflect the branch this index is actually as-built against right now, replacing a stale reference to `hr-40-equipment-utilization-tracker` (already merged to `develop`, no longer a separate local branch) — **note:** while updating this, found that `/api/monthly-utilization` (real, live code — `MonthlyUtilizationController` exists in this branch's `src/`) has no entry anywhere in this index, despite being documented here on an earlier branch (per merge-conflict-resolution history) — that gap predates this change and wasn't introduced by it; flagged to the user, not fixed in this pass. |
| 2.2.0 | 2026-08-13 | **Drift audit against source code.** (Renumbered from a colliding 2.1.0 assigned independently on a different branch — see 2.4.0 below.) §2.3: `RentalPlanController` was still described as a `🧱 Stub` at the old path `/api/rental-plans` returning `[]`; replaced with its real 6-route, camelCase `/api/rentalPlans` surface, cross-linked to [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md) (added to `Related specs`). §2.3's intro sentence, which still claimed none of `EquipmentController`/`DepotController`/`RentalPlanController` existed in the working tree, corrected to match §3.2's own finding that `HR-72` is merged. §2.4 retitled and extended to cover `hr-40-equipment-utilization-tracker` (the branch the header table already claimed lived there) and its `GET /api/monthly-utilization` route, previously undocumented anywhere in this index. §2.5: removed `POST /api/pricing/estimate` (never built, no matching Haystack endpoint) and `POST /api/recommendations` (stale — superseded by the real, already-implemented §2.6 routes). |
| 2.3.0 | 2026-08-13 | **Web-portal API audit — nine items verified against source and documented.** (Renumbered from a colliding 2.2.0 — see 2.4.0 below.) (1) §2.2: `POST /api/bookings`/`POST /api/payments/deposit-intent` reclassified `Shared (mobile+web)`, confirmed the web portal calls both directly — were mislabeled mobile-only. (2) New §2.7 + new [`SPEC-admin-users-api.md`](./SPEC-admin-users-api.md): `/api/users` was absent from this index entirely (this branch hadn't yet picked up 2.1.0 above, written independently on `36-link-rest-api-users-to-front-end`); found fully built and verified on that unmerged remote branch, documented as branch-only, not present in this working tree at the time — **superseded by 2.4.0 below**, where the real merge landed. (3) New §2.5.1: `/api/depots` writes, `/api/rentalPlans/{id}` `PUT`/`PATCH`/`DELETE`, `/api/bookings/{id}` `PATCH`/`DELETE` — checked every branch in the repo, none exist anywhere; recorded as explicitly **not planned** rather than left silent. (4) §2.1: added an inline logout-behavior summary (denylist-based `jti` invalidation, confirmed against `TokenDenylist`/`AuthService`) so it's visible without following the link. (5)/(6) [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md) new §16: recorded that §5 of that file is the authoritative full portal contract (a separate portal-side doc only had a one-line gloss), and that no rental-date-range input exists in the Call 2 request path (`HR-111`, no branch yet). (7) [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) 1.6.0: cross-checked `startDate`/`endDate` behavior against `EquipmentController`/`AssetService` directly — confirmed accurate, no divergence. (8) **Correction:** `POST /api/rentalPlans/{id}/quote` does **not** call Haystack — a portal-facing reference had stated otherwise; verified false against `RentalPlanService`/`PricingClient`, corrected here and in [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md) §5.0. (9) New §2.5.2 + new [`SPEC-pricing-estimate.md`](./SPEC-pricing-estimate.md) (design only, no code): a new, deliberate, Spring-only, non-Haystack `POST /api/pricing/estimate`, distinct from the phantom this index removed in 2.2.0 above; its availability-check-or-not question recorded as open, not resolved. |
| 2.4.0 | 2026-08-13 | **Merged `develop` (bringing in 2.1.0's `36-link-rest-api-users-to-front-end` work, now real via PR #37/commit `13ec76c`) into this branch; resolved the resulting merge conflict in this file.** Both branches had independently written a `§2.7`/`/api/users` section and both had assigned colliding version numbers (`2.1.0` twice) — renumbered 2.2.0/2.3.0 above to restore chronological order rather than picking one side. §2.7 rewritten one more time: status upgraded from "🔀 branch, unmerged" (2.1.0) / "🌿 branch, not in working tree" (2.3.0 item 2) to **`✅ Merged → develop`** — the code (`UserController`, `UserAdminService`, `User*` DTOs, `SecurityConfig` matcher) is confirmed real and present as of this merge, not just documented. Header `Status`/`Related specs` reconciled to a superset of both sides' additions. `SPEC-admin-users-api.md`'s own provenance note updated to match (see that file's history). |
=======
| 2.1.0 | 2026-08-12 | New §2.7 — `/api/users` (list/get/create/update/remove)... |
| 2.2.0 | 2026-08-13 | **Drift audit against source code.**... |
| 2.3.0 | 2026-08-13 | **Web-portal API audit — nine items verified against source and documented.**... |
| 2.4.0 | 2026-08-13 | **Merged `develop`**... resolved the resulting merge conflict in this file... |
| 2.5.0 | 2026-08-13 | **HR-116: `siteAddress` postal-code validation.** (Renumbered from a colliding `2.1.0` assigned independently on this branch, resolved the same way `2.4.0` above resolved its own collision.) §2.2.1 (`POST /api/bookings`) documents the new `@NotBlank`/`@Pattern` constraint on `CreateBookingRequest.siteAddress`... [rest of your original text] |
>>>>>>> develop
