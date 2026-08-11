# Specification: REST API Index

| Field | Value |
|-------|--------|
| **Document type** | Cross-cutting index — not a feature contract itself |
| **Status** | As-built across `develop` (which now includes both former `HR-72` and `HR-80` work — see §3.1) + one locally-rebased branch (`hr-27-payment-checkout`, not yet pushed — see §2.4) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related specs** | [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md), [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md), [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md), [`SPEC-entity-repository.md`](./SPEC-entity-repository.md), [`SPEC-stripe.md`](./SPEC-stripe.md) |
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

---

## 2. Endpoint index

### 2.1 Auth — shared by both clients

| Method | Path | Client | Roles allowed | Status | Contract |
|---|---|---|---|---|---|
| `GET` | `/api/auth/getBearerToken` | Shared | Public | ✅ Merged → `develop` | [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md) |
| `POST` | `/api/auth/login` | Shared | `ROLE_INTERIM` | ✅ Merged → `develop` | [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) |
| `POST` | `/api/auth/logout` | Shared | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) |

There is currently **one** login flow. No `platform`/`audience` field or web-vs-mobile distinction exists anywhere in the request, the JWT claims, or `SecurityConfig` — see §4.

### 2.2 Mobile — bookings, deliveries, returns, payments

Per branch author: every route in this section is for the **mobile** client.

| Method | Path | Roles allowed | Status | Contract |
|---|---|---|---|---|
| `POST` | `/api/bookings` | `ROLE_USER`, `ROLE_ADMIN` (caller becomes the booking's customer) | 🧪 Branch `hr-27-payment-checkout` (local) | §2.2.1 below |
| `GET` | `/api/bookings` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) §5.2 |
| `GET` | `/api/bookings/{bookingId}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `PUT` | `/api/bookings/{bookingId}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `GET` | `/api/deliveries` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `PATCH` | `/api/deliveries/{bookingId}/status` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `GET` | `/api/returns` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `PATCH` | `/api/returns/{bookingId}/status` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §5.2 |
| `POST` | `/api/payments/deposit-intent` | `ROLE_USER`, `ROLE_ADMIN` (booking owner or admin — enforced in `PaymentService`, not `SecurityConfig`) | 🧪 Branch `hr-27-payment-checkout` (local) | [`SPEC-stripe.md`](./SPEC-stripe.md) §6.1 |
| `POST` | `/api/payments/webhook` | Public (Stripe-Signature verified) | 🧪 Branch `hr-27-payment-checkout` (local) | [`SPEC-stripe.md`](./SPEC-stripe.md) §6.2 |

All ten routes above now have a dedicated feature spec: the seven `develop`-merged ones via [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) (written per the standalone-spec criterion in `SPEC-project-environment.md` §9.1), `POST /api/bookings` via §2.2.1 immediately below, and the two payments routes via [`SPEC-stripe.md`](./SPEC-stripe.md) (previously undocumented — see the correction below). `SPEC-entity-repository.md` still documents the underlying `Booking`/`Payment`/`DeliveryRecord`/`ReturnRecord` *entities* (§3.2/§10.7 of that file), but not their REST layer.

**Payments status corrected 2026-08-11:** this table previously listed a single `POST /api/payments/create-payment-intent` route as "✅ Merged → `develop` (predates this branch — `HR-60`)" with no owning spec. That was accurate as of when it was written, but is no longer the current contract: `hr-27-payment-checkout` was rebased onto `develop` locally and, as part of that rebase, `PaymentController` was replaced wholesale with the two routes now shown above — `create-payment-intent` no longer exists anywhere in this codebase (confirmed: no remaining references to it in any `.java` file). This was a deliberate choice, not an accident of the merge: the old endpoint trusted a client-supplied payment amount with no server-side validation against the booking's real price, and nothing else in the codebase called it. The replacement (`deposit-intent`) computes the amount server-side from `Booking.depositAmount` instead. Full contract, and the gaps introduced by reconciling this branch's payment code with `develop`'s booking model, are in [`SPEC-stripe.md`](./SPEC-stripe.md), which previously had no entry in this index at all.

**Booking creation gap closed 2026-08-11.** `POST /api/bookings` now exists on this branch, resolving what was previously the single biggest blocker to a real end-to-end flow (browse → book → pay deposit).

#### 2.2.1 `POST /api/bookings`

Request (`CreateBookingRequest`): `{ items: [{ assetId }], startDate, endDate, rentalPlanId?, siteAddress, deliveryNotes }`. One or more `assetId`s, a shared date window across all items (matches `SPEC-ui-heavy-machinery-portal.md`'s multi-item-per-booking model), an optional `rentalPlanId`, plus free-text `siteAddress`/`deliveryNotes`. No `sitePostalCode` field — it's a computed `@Formula` column on `Booking`, extracted from `siteAddress`, not client-settable.

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

Per branch author: every route in this section is for the **web** client. **Corrected 2026-08-11:** this section previously said none of these routes existed in the working tree and that they lived only on `origin/HR-72-add-browse-equipment-to-rest-api`, unmerged. That was already stale when written — `HR-72`'s work merged into `develop` via commit `692ece6` ("include the relevant class to link browse equipment to the web portal", PR #12), *before* `HR-80` (`c081ee1`) landed on top of it. `EquipmentController`, `DepotController`, and `RentalPlanController` are all live on `develop` today, confirmed directly against the tree.

| Method | Path | Roles allowed | Status | Contract |
|---|---|---|---|---|
| `GET` | `/api/equipment` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) §7.1 |
| `GET` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.2 |
| `POST` | `/api/equipment` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.3 |
| `PUT` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.4 |
| `PATCH` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.4 |
| `DELETE` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | §7.5 |
| `GET` | `/api/depots` | `ROLE_USER`, `ROLE_ADMIN` | 🧱 Stub, merged → `develop` | None — always returns `[]`; no `Depot` entity exists (delivery site fields live on `Booking`/`RentalPlan` directly). Comment in `DepotController.java` notes this exists specifically so the React portal's `CustomerPortal` (which errors its whole equipment page if either `/api/equipment` or `/api/depots` fails) doesn't break |
| `GET` | `/api/rental-plans` | `ROLE_USER`, `ROLE_ADMIN` | 🧱 Stub, merged → `develop` | None — always returns `[]`; `RentalPlan`/`RentalPlanRepository` exist but nothing is wired to them yet. The React portal already degrades this to an empty list gracefully |

No per-route restriction distinguishes admin-only write access on the equipment routes — any authenticated `ROLE_USER` or `ROLE_ADMIN` can create/edit/delete equipment, not just admins. Same blanket-rule caveat as everywhere else in this index (see §4).

### 2.4 Local, unpushed — `hr-27-payment-checkout`

This branch (see §2.2's payments rows) exists only on the machine it was rebased on as of 2026-08-11 — 8 commits ahead of `origin/hr-27-payment-checkout`, not pushed, not merged. It carries `develop`'s full route set (§2.1–§2.3) unchanged, plus the two payments routes and one fix made directly on this branch (CORS, below), not on `develop`. Two things worth knowing before treating this as equivalent to a `develop` merge:

- **CORS fixed 2026-08-11.** `SecurityConfig` now wires a real `CorsConfigurationSource` bean (`.cors(cors -> cors.configurationSource(corsConfigurationSource()))`, replacing the previous no-op `.cors(Customizer.withDefaults())`), scoped to `/api/**`, allowing `GET/POST/PUT/PATCH/DELETE/OPTIONS` and the `Authorization`/`Content-Type` headers. Allowed origins are configurable via `app.cors.allowed-origins` / `APP_CORS_ALLOWED_ORIGINS` (new `CorsProperties` record, comma-separated), defaulting to `http://localhost:5173,http://localhost:4173` (Vite dev/preview) for local development. **Deliberately no wildcard origin default** — deployment must set `APP_CORS_ALLOWED_ORIGINS` to the real deployed frontend origin(s); nothing here guesses that value. Verified directly against a running instance: a preflight `OPTIONS /api/equipment` from an allowed origin returns `200` with `Access-Control-Allow-Origin` set; from a disallowed origin it returns a flat `403 Invalid CORS request`, rejected by the CORS filter before Spring Security's auth layer even runs.
- `Booking.paidStatus` was deleted from this branch during the rebase (a `develop` commit, `8bdf067`, had already removed it and folded payment state into `BookingStatus` instead — this branch's Stripe code hadn't caught up). Payment endpoints in §2.2 work, but several state transitions they used to perform no longer happen — see [`SPEC-stripe.md`](./SPEC-stripe.md) §10 for the specifics.

### 2.5 Planned, not started

| Item | Status | Notes |
|---|---|---|
| `platform` attribute on `LoginRequest` | ⏳ Not started | Branch `HR-85-implement-platform-attribute-in-login-request-body` exists but has **zero commits beyond `HR-77`** — it's an unstarted placeholder, not a design that's been written down anywhere yet. Likely the intended mechanism for distinguishing web vs mobile at login (see §4) once work begins. |

---

## 3. Correcting assumptions this index was built to check

### 3.1 Web auth is not separately implemented

Before drafting this file, the working assumption was that **web auth was already built separately in `HR-72`**. Checked directly against that branch's diff and its own spec (`SPEC-equipment-browse-api.md` §2.2, §9): **HR-72 makes zero changes to `Authentication.java`, `AuthService`, `SecurityConfig`, or `LoginRequest`.** Its own spec says so explicitly: *"No `SecurityConfig` changes — the existing catch-all `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` rule already covers these new routes."* Web and mobile, as of both branches today, authenticate through the **exact same** `getBearerToken` → `login` → `logout` flow in §2.1. There is no second, web-specific auth implementation anywhere in this repository's history.

### 3.2 `HR-72` and `HR-80` are both merged into `develop` — §2.2/§2.3 previously said otherwise

This index originally (1.0.0–1.2.0) described `HR-80` and `HR-72` as two independent, unmerged sibling branches, each diverging from the same commit (`584346f`) and never reconciled with each other. That was already inaccurate by the time it was written: `HR-72`'s equipment/depot/rental-plan work merged into `develop` via `692ece6` ("include the relevant class to link browse equipment to the web portal", PR #12) *before* `HR-80` (`c081ee1`) landed on top of it — so `develop` today (and everything built on top of it, including `hr-27-payment-checkout` in §2.4) already has both. §2.2 and §2.3's status columns are corrected accordingly as of 2026-08-11. No route was lost or needs reconciling between these two — they never actually competed for the same files.

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
