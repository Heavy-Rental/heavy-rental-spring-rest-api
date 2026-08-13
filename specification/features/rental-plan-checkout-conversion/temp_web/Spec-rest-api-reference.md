# Specification: REST API Reference (Real Backend)

**Feature Area**: heavy-rental-react-web-portal
**Created**: 2026-08-13
**Status**: Reference / Living document
**Input**: A cross-cutting REST index for `heavy-rental-spring-rest-api` (the real Spring Boot backend) was shared for comparison against this portal's actual and planned API usage. No file from that source is linked here — it was a working document from the backend team, not part of this repo's own spec set.

## Overview

Until this document, no spec in this portal catalogued the **real backend's** REST surface — only the mock server (`Spec-mock-api-server.md`) and scattered per-feature slices (`Spec-frontend-api-integration.md`, `Spec-stripe-payment-checkout.md`). This is the single place to see every real-backend route this portal uses today, is documented to use, or plans to use — and, separately, which routes this portal's code calls that the real backend does not yet support.

Scope is **this web portal's perspective only**: routes owned by the mobile/driver app with no web-portal feature (today or planned) are deliberately excluded — see §4. This document does not restate request/response shapes already owned by another spec (`Spec-frontend-authentication.md` for the login/session flow, `Spec-stripe-payment-checkout.md` for booking-creation/deposit-intent); it points to them.

**When a route this portal depends on is added, removed, or changed on the real backend, update this index in the same change set** — the same discipline this portal's other specs already commit to for their own content.

## Clarifications

### Session 2026-08-13

- Q: The backend's index filed `POST /api/bookings` and `POST /api/payments/deposit-intent` under a "mobile-only" section, but this portal calls both directly (`Spec-stripe-payment-checkout.md`) — how should this reference handle that? → A: The frontend's usage is correct; the backend-side client label is what's wrong, and correcting it is a backend-side documentation fix (out of scope for this repo). This reference lists both routes under §2.4 (Bookings) / §2.5 (Payments) as web-used, and separately still excludes the genuinely mobile/driver-only routes (deliveries, returns, payment webhook) — see §4 — rather than importing the backend's client labels wholesale.
- Q: Should this reference document the routes the frontend *calls but the real backend doesn't implement* (`/api/users`, depot/rental-plan/booking write routes beyond what's built) as something the frontend should stop calling, or as backend work still owed? → A: Backend work owed. The frontend's assumed contract (full CRUD on users, depots, rental plans; PATCH/DELETE on bookings) is treated as correct/intended here; §5 tracks each as a backend implementation gap, not a frontend bug.
- Q: `POST /api/auth/logout` is a real, merged backend route, but this portal's `handleLogout` never calls it (purely local session clear, unchanged since `Spec-frontend-authentication.md`) — fix the frontend to call it, or drop the backend route? → A: Left open — recorded in §6 as an undecided item rather than resolved either way, since deciding requires knowing whether the backend route does anything server-side (session/refresh-token invalidation, audit logging) beyond what a stateless JWT scheme needs.
- Q: The backend already implemented the S2b recommender routes (`/api/recommendations/*`), but `CustomerOnboarding.tsx`/`Chatbot.tsx` still simulate recommendations entirely client-side — how should that gap be tracked? → A: As frontend work still owed (§7) — this portal intends to wire these endpoints eventually, replacing the client-simulated flow.

### Session 2026-08-13 (continued)

- Q: Does `POST /api/rentalPlans/{id}/quote` reach Haystack, or is it Spring-only arithmetic? → A: It's intended to reach Haystack's quote endpoint for AI-informed pricing — this corrects an earlier speculative note in this document (§2.4) that guessed it was likely Spring-only because its contract lives in a separate spec from the Haystack client. See §8.1.
- Q: The backend's index recorded a `POST /api/pricing/estimate` route as removed ("never built, no matching Haystack endpoint to proxy") — does this portal still need an endpoint like that? → A: Yes, as a deliberate new proposal, not a revival of that removed phantom. It's meant to be the Spring-only counterpart to the Haystack-backed quote in §8.1 — a fast price calculation that never reaches Haystack. See §8.2.

## 1. Status legend

| Status | Meaning |
|---|---|
| ✅ Backend live, frontend wired | Real backend route exists and this portal calls it (in `npm run dev:api` / `MODE === "api"`) |
| 🧱 Backend stub | Route exists, returns success, but has no real backing data (e.g. always `[]`) |
| ⚠️ Frontend calls it, backend doesn't have it | This portal's code (unconditionally, not mode-gated) targets a path/method the real backend does not implement — see §5 |
| ⏳ Backend live, frontend not wired | Real backend route is implemented but this portal has no code calling it yet — see §7 |
| — | Not applicable to this portal (mobile/driver-only) — see §4 |

## 2. Endpoint index — routes this portal uses or depends on

### 2.1 Auth — shared with mobile

| Method | Path | Status | Notes |
|---|---|---|---|
| `GET` | `/api/auth/getBearerToken` | ✅ Backend live, frontend wired | `src/app/api.ts` `login()`, API mode only. See `Spec-frontend-authentication.md`. |
| `POST` | `/api/auth/login` | ✅ Backend live, frontend wired | Same. |
| `POST` | `/api/auth/logout` | ⚠️ / open question | Backend route exists; frontend `handleLogout` (`src/App.tsx`) never calls it, in any mode. See §6 — not resolved here. |

There is one login flow, no web-vs-mobile distinction at the backend level (no `platform`/`audience` claim exists today).

### 2.2 Equipment

| Method | Path | Status | Notes |
|---|---|---|---|
| `GET` | `/api/equipment` | ✅ Backend live, frontend wired | `equipmentApi.list()`, also accepts `startDate`/`endDate` query params from the frontend's call shape ([api.ts:82-88](../src/app/api.ts#L82-L88)) — availability-aware listing is a portal assumption, not independently re-verified against the real backend's `SPEC-equipment-browse-api.md` contract in this pass. |
| `GET` | `/api/equipment/{id}` | ✅ Backend live, frontend wired | |
| `POST` | `/api/equipment` | ✅ Backend live, frontend wired | Admin `AssetsTab`. |
| `PUT` | `/api/equipment/{id}` | ✅ Backend live, frontend wired | |
| `PATCH` | `/api/equipment/{id}` | ✅ Backend live, frontend wired | Admin `PricingTab` rate updates. |
| `DELETE` | `/api/equipment/{id}` | ✅ Backend live, frontend wired | |

### 2.3 Depots

| Method | Path | Status | Notes |
|---|---|---|---|
| `GET` | `/api/depots` | 🧱 Backend stub, frontend wired | Real backend always returns `[]` — no `Depot` entity exists server-side. `depotApi.list()` is called (`App.tsx`, admin context) and tolerates the empty result. |
| `POST` / `PUT` / `PATCH` / `DELETE` | `/api/depots...` | ⚠️ Frontend calls it, backend doesn't have it | `depotApi` is a generic full-CRUD resource client-side; no write route exists on the real backend. See §5. |

### 2.4 Rental Plans

| Method | Path | Status | Notes |
|---|---|---|---|
| `POST` | `/api/rentalPlans` | ✅ Backend live, frontend wired | `rentalPlanApi.create()`, checkout flow. |
| `GET` | `/api/rentalPlans` | ✅ Backend live, frontend wired | |
| `GET` | `/api/rentalPlans/{id}` | ✅ Backend live, frontend wired | |
| `POST` | `/api/rentalPlans/{id}/items` | ⏳ Backend live, frontend not wired | Real backend supports item-level add; portal's `rentalPlanApi` client doesn't expose a matching call today. |
| `DELETE` | `/api/rentalPlans/{id}/items/{itemId}` | ⏳ Backend live, frontend not wired | Same. |
| `POST` | `/api/rentalPlans/{id}/quote` | ⏳ Backend live, frontend not wired | Reaches Haystack for AI-informed pricing, not plain arithmetic — see §8.1. |
| `PUT` / `PATCH` / `DELETE` | `/api/rentalPlans/{id}` | ⚠️ Frontend calls it, backend doesn't have it | `rentalPlanApi.replace/update/remove` are part of the generic resource client; the real backend has no generic update/delete on a plan, only the item- and quote-scoped routes above. See §5. |

### 2.4.1 Field-level requirements for the cart/checkout workflow (`Spec-rental-plan-cart-checkout.md` PR 1-3)

The table above only tracks route-level wiring status. None of these routes ever had a field-level contract reach this repo (`SPEC-rental-plan-quote.md`, like the Haystack recommender spec, was never shared here) — so "backend live" above means the route exists, not that its request/response shape is confirmed to carry what the new workflow needs. This breaks that down per route, marking what's **confirmed**, **unconfirmed** (needs a backend check, not necessarily a change), and **🔧 change required** (new behavior this workflow needs that almost certainly doesn't exist yet).

- **`POST /api/rentalPlans`** (PR 1) — needs `id`, `status` back in the response, to confirm the new plan starts at `draft`. **Unconfirmed**: whether the client must pass `status` in the request or the backend always defaults a new plan to `draft`.
- **`GET /api/rentalPlans`** (PR 1, PR 2) — each plan needs `id`, `status`, and `updatedAt` in the array (PR 2 needs `updatedAt` on every read, not just right after quoting — e.g. reopening the app later and re-checking quote validity). **Unconfirmed**: whether it supports filtering to "the caller's one active (non-`converted`) plan" server-side, or PR 1 must fetch all of a user's plans and filter client-side (this is B9 — not necessarily a change, but worth confirming before it becomes a real cost as plan history grows).
- **`GET /api/rentalPlans/{id}`** (PR 2) — same `status`/`updatedAt` requirement as above, for recomputing checkout-eligibility when the customer returns to a plan without having just quoted it. **Unconfirmed** whether these fields are already in the response (this is B8).
- **`POST /api/rentalPlans/{id}/items`** (PR 1) — request needs `assetId` and, most likely, per-item `startDate`/`endDate` (the cart currently lets each item carry its own range before checkout normalizes them, per `Spec-ui-heavy-machinery-portal.md` §4.3). **Unconfirmed and potentially a real design question, not just a doc gap**: does `RentalPlanItem` actually support distinct per-item dates, or does a plan require one shared date range decided before any item can be added? This changes PR 1's UX if the latter. **🔧 Change required (B10)**: if the plan is currently `quoted`, adding an item must revert `status` to `draft` and refresh `updatedAt` — new behavior, almost certainly not implemented today.
- **`DELETE /api/rentalPlans/{id}/items/{itemId}`** (PR 1) — **🔧 Change required (B10)**, same as above: must revert `quoted` → `draft` and refresh `updatedAt` if the plan was quoted. Response should ideally reflect the new `status`/`updatedAt` so the frontend doesn't need a follow-up `GET`.
- **`POST /api/rentalPlans/{id}/quote`** (PR 2) — **🔧 Change required (B7)**: this call must itself set `status = quoted` and refresh `updatedAt` as part of succeeding — this is new required behavior, not existing behavior we're just undocumented on. Response needs an authoritative price (recommend matching `Booking`'s existing `totalAmount`/`depositAmount`/`remainingBalance` naming for consistency) plus `status` and `updatedAt`, so the frontend can update UI immediately without a follow-up call. **Unconfirmed** whether any of this is already in the response — no field-level contract for this route has ever reached this repo.
- **`PUT`/`PATCH`/`DELETE /api/rentalPlans/{id}`** — unaffected by this workflow; still the removal candidates from §5.

PR 3's needs (`POST /api/bookings` accepting `rentalPlanId`, deriving items/pricing from the plan per B1, and the `409` expired-quote response per B3) live in §2.5, not here — that section needs the same field-level pass, not done in this update.

### 2.5 Bookings & Payments

| Method | Path | Status | Notes |
|---|---|---|---|
| `POST` | `/api/bookings` | ✅ Backend live, frontend wired | `createDepositBooking()` — real booking-creation contract, API mode only. See `Spec-stripe-payment-checkout.md`. Used by **this web portal directly**, despite being filed under the backend's "mobile" section (§ Clarifications above). |
| `POST` | `/api/payments/deposit-intent` | ✅ Backend live, frontend wired | `paymentApi.createDepositIntent()`. Same web-usage note. |
| `GET` | `/api/bookings` | ⏳ Backend live, frontend not wired | Real route exists; this portal's "My Rental Plans" / admin bookings views still read from the mock server only in API mode's current scope (`Spec-stripe-payment-checkout.md` Out of Scope). Relevant if API-mode parity is extended later. |
| `GET` | `/api/bookings/{id}` | ⏳ Backend live, frontend not wired | Same. |
| `PUT` | `/api/bookings/{id}` | ⏳ Backend live, frontend not wired | Same — note this is a full-replace endpoint on the real backend, not a partial merge. |
| `PATCH` / `DELETE` | `/api/bookings/{id}` | ⚠️ Frontend calls it, backend doesn't have it | Admin `BookingsTab` calls `bookingApi.update()` (PATCH) for status changes; `bookingApi.remove()` (DELETE) also has no backend route. See §5. |

### 2.6 Analytics

| Method | Path | Status | Notes |
|---|---|---|---|
| `GET` | `/api/monthly-utilization` | ✅ Backend live, frontend wired | `ROLE_ADMIN` only server-side; admin Overview tab. |
| `GET` | `/api/status-distribution` | ⚠️ Frontend calls it, backend doesn't have it | `statusDistributionApi` — mock-server-only endpoint (`Spec-mock-api-server.md` FR-009); no equivalent exists anywhere on the real backend. See §5. |

### 2.7 Users

| Method | Path | Status | Notes |
|---|---|---|---|
| `GET` / `POST` / `PATCH` / `DELETE` | `/api/users...` | ⚠️ Frontend calls it, backend doesn't have it | `userApi` — called unconditionally on every login (`App.tsx` `handleLogin`) to resolve a numeric `userId`, and by the admin Users tab. No `/api/users` route exists anywhere on the real backend. See §5. |

### 2.8 Recommendations (S2b)

| Method | Path | Status | Notes |
|---|---|---|---|
| `POST` | `/api/recommendations/project-spec` | ⏳ Backend live, frontend not wired | Implemented backend-side (Call 1 → Call 2 orchestration). `CustomerOnboarding.tsx` currently simulates this client-side. See §7. |
| `POST` | `/api/recommendations/{id}/knowledge-query` | ⏳ Backend live, frontend not wired | Backs the project chatbot (Call 3). `Chatbot.tsx` currently simulates replies client-side. See §7. |
| `GET` | `/api/recommendations/{id}` | ⏳ Backend live, frontend not wired | Session read-back; no frontend caller yet. |

## 3. Env/proxy context

This portal reaches the real backend only under `npm run dev:api` (`MODE === "api"`), via the Vite dev-server proxy's `VITE_API_TARGET` (`http://heavy-rental-rest-api:8080`, a container-network hostname) — see `Spec-project-environment.md` FR-011 and `Spec-mock-api-server.md`'s Appendix. Under the default `npm run dev`/`dev:mock`, every route in this document is instead served by the mock server per `Spec-mock-api-server.md`, which has no auth, no Stripe, and different write semantics (e.g. `POST` responses wrapped in a single-element array — see `unwrapCreateResponse()` in `api.ts`).

## 4. Excluded — mobile/driver-only, no web-portal feature today or planned

These exist on the real backend but have no corresponding screen or planned screen in this portal (no delivery/return record management exists anywhere in `src/features/admin`), so they're intentionally left out of §2 rather than tracked as gaps:

| Method | Path |
|---|---|
| `GET` | `/api/deliveries` |
| `PATCH` | `/api/deliveries/{bookingId}/status` |
| `GET` | `/api/returns` |
| `PATCH` | `/api/returns/{bookingId}/status` |
| `POST` | `/api/payments/webhook` |

The last one is a Stripe-to-backend server callback, not a route any frontend (web or mobile) ever calls directly — excluded on that basis regardless of client ownership.

## 5. Backend implementation gaps (routes this portal's code expects, not yet real)

These are treated as backend work still owed, not frontend bugs to fix — the frontend's assumed contract is the intended one:

- **`/api/users`** — no route exists at all. Needed for: resolving a login's numeric `userId` (`handleLogin`, silently falls back to `id: null` today via try/catch), and the admin Users tab's full CRUD.
- **`/api/depots` write routes** (`POST`/`PUT`/`PATCH`/`DELETE`) — real backend has GET-only stub, no `Depot` entity. Needed if depot management is ever exercised in API mode (currently only read via the stub, so no visible breakage yet — but any write path would fail).
- **`/api/rentalPlans/{id}`** generic `PUT`/`PATCH`/`DELETE` — real backend only has item- and quote-scoped mutations (§2.4).
- **`/api/bookings/{id}` `PATCH`/`DELETE`** — real backend only has `PUT` (full replace). Admin `BookingsTab`'s status-change call assumes PATCH.
- **`/api/status-distribution`** — no real-backend equivalent at all; mock-only today.

## 6. Open item — `POST /api/auth/logout`

Not resolved in this document. The route is real and merged on the backend, but this portal's logout has always been a purely local session clear (`Spec-frontend-authentication.md`, predates API mode) and was never updated to call it once API mode existed. Two ways this could go, neither decided here:

- Wire `handleLogout` to call it in API mode, treating it as a real server-side action (session/refresh-token invalidation, audit logging).
- Confirm it's a no-op for this backend's stateless-JWT scheme and deprecate/remove the route.

Whoever owns the backend's auth design should confirm which, since that determines whether the current frontend behavior is a gap or already correct.

## 7. Frontend work owed — recommender wiring

`/api/recommendations/project-spec`, `.../knowledge-query`, and `.../{id}` (§2.8) are implemented and "As-built" on the real backend, but this portal has no `recommendationApi` in `src/app/api.ts` and no call site — `CustomerOnboarding.tsx`'s recommendation cards and `Chatbot.tsx`'s replies are both fully client-simulated. Tracked here as a planned future feature: this portal intends to replace that simulation with real calls to these endpoints.

### 7.1 `POST /api/recommendations/project-spec` — proposed request/response contract

**Status: proposed, not confirmed against the backend.** The authoritative contract is supposed to live in `SPEC-haystack-recommender-client.md` §5.1, which isn't in this repo — only a one-line gloss of it reached this portal via a temporary backend index (since removed). This is this portal's own recommendation for what that contract should look like, derived from: the entity field names the frontend already anticipates (`AIRecommendation.confidence_score`, `RecommendationItem.rank_order`/`match_score` — see comments in `CustomerOnboarding.tsx`), the `quoteRef`/`items` naming already hinted upstream, and reuse of the existing `POST /api/rentalPlans/{id}/quote` pricing engine (`Spec-rental-plan-quote.md`, backend-side) instead of a second pricing path. Must be confirmed against the real backend contract before implementation.

Orchestration this assumes: Web → Spring (this route) → Haystack Call 1 (`submitprojectspecification`) → Haystack Call 2 (`project-knowledge/getassetrecommendations`) → Spring composes the response below. Call 1's output (a Haystack session id) is never exposed to the web client — Spring persists it server-side against the `AIRecommendation` row so Call 3 (`knowledge-query`) can reference it later; only Call 2's asset recommendations feed the response.

**Request:**

```json
{
  "description": "6-storey building, 8T load, 18m reach, 3 weeks of facade work",
  "attachmentFileNames": ["site-plan.pdf", "scope.docx"],
  "startDate": "2026-09-01",
  "endDate": "2026-09-21"
}
```

- `description` — free-text specs; required if `attachmentFileNames` is empty (mirrors the UI's existing `specsText.length >= 20 || uploaded.length > 0` gate).
- `attachmentFileNames` — filenames only, not file content — matches current frontend behavior, which never reads uploaded file bytes. Sending real file content is a v2 concern (multipart), not proposed here.
- `startDate`/`endDate` — the field `HR-111-include-date-selection-field-for-web-recommender` was created for and never built (branch has zero commits beyond `develop`). Shaped like `POST /api/bookings`'s date pair for consistency, not a `rentalDays` count.
- No `userId` — resolved from the JWT, same as `POST /api/bookings`.

**Response:**

```json
{
  "recommendationId": 123,
  "confidenceScore": 0.87,
  "quoteRef": { "rentalPlanId": 55 },
  "items": [
    {
      "rankOrder": 1,
      "assetId": 4,
      "matchScore": 0.95,
      "reason": "Elevated access or working-at-height requirement identified"
    }
  ]
}
```

- `recommendationId` — the `AIRecommendation` row's id; the one handle the web client keeps, since `GET /api/recommendations/{id}` and `POST /api/recommendations/{id}/knowledge-query` (Call 3 chatbot) both key off it.
- `confidenceScore` — `AIRecommendation.confidence_score`.
- `items[]` — one per `RecommendationItem` (`rankOrder`, `matchScore`, `reason`); `assetId` only, not a full equipment object — the client already has `GET /api/equipment/{id}` for that, avoiding two copies of price/capacity going stale relative to each other.
- `quoteRef.rentalPlanId` — Spring creates a draft `RentalPlan` from the Call 2 asset list server-side and returns its id here, rather than computing pricing inside this endpoint. The web client then calls the existing `POST /api/rentalPlans/{rentalPlanId}/quote` for actual priced numbers — reusing the already-built quote engine instead of a second one.

## 8. Pricing calls — quote (Haystack) vs. estimate (Spring-only)

This portal needs two distinct pricing paths, not one: an AI-informed **quote** that consults Haystack, and a fast **estimate** that never leaves Spring. They are not interchangeable and must not be conflated into a single route.

### 8.1 `POST /api/rentalPlans/{id}/quote` — reaches Haystack

**Corrected 2026-08-13.** An earlier revision of this document speculated (§2.4) that this route was likely Spring-only arithmetic, reasoning from the fact that its contract lives in `SPEC-rental-plan-quote.md`, a spec separate from the Haystack client spec. That speculation was wrong: this route is intended to reach Haystack's quote endpoint for AI-informed pricing on a rental plan's existing items (e.g., bundle- or recommendation-aware pricing), not a plain sum. Status unchanged: `⏳ Backend live, frontend not wired` (§2.4).

### 8.2 `POST /api/pricing/estimate` — proposed, Spring-only, never reaches Haystack

**Status: proposed, new route — does not exist on the backend today.** The backend's temporary index recorded a same-named route as removed: *"`/api/pricing/estimate` was never built and has no matching Haystack endpoint to proxy."* That removal was about a phantom placeholder with no design behind it. This is a fresh, deliberate proposal for the same path, scoped specifically as the **non-Haystack counterpart** to §8.1's quote: a fast, side-effect-free price calculation with no external AI call and no persisted resource — no `Booking`, no `RentalPlan`, no `AIRecommendation` row created.

Purpose: let the web portal show an authoritative price for an ad-hoc set of equipment + dates before the user commits to a rental plan or booking — reusing the same pricing formula `POST /api/bookings` already computes server-side (sum of `baseDailyRate × days` per asset, minimum 1 day, 30%/70% deposit split, `HALF_UP` rounding to 2dp, same `DEPOSIT_RATE` constant) instead of the frontend's own client-side `calcDeposit()` estimate, which existing precedent (`Spec-stripe-payment-checkout.md`) already treats as non-authoritative ("never trust a client-supplied amount").

**Request:**

```json
{
  "items": [{ "assetId": 4 }, { "assetId": 7 }],
  "startDate": "2026-09-01",
  "endDate": "2026-09-21"
}
```

Deliberately the same `items`/`startDate`/`endDate` shape as `POST /api/bookings`'s request, so the same validation and pricing logic can be reused server-side without a second implementation.

**Response:**

```json
{
  "totalAmount": 4200.00,
  "depositAmount": 1260.00,
  "remainingBalance": 2940.00
}
```

Same three fields `POST /api/bookings`'s response already carries — no new shape to learn on the frontend side.

**Open design question, not resolved here:** should this route run the same availability/overlap check `POST /api/bookings` does (`409` on a double-booked asset), or stay purely arithmetic with no availability awareness? Recommendation: no availability check — an estimate should stay fast and side-effect-free, and a conflict on an asset the user hasn't committed to yet isn't actionable at estimate time; conflict detection stays owned by the real booking-creation step. Needs confirmation from the backend team.

## Related specs

- `Spec-mock-api-server.md` — the mock server's own route contract (used under `dev`/`dev:mock`, the default).
- `Spec-frontend-api-integration.md` — the API client layer (`src/app/api.ts`) and its mock-oriented wiring.
- `Spec-frontend-authentication.md` — the login/session/bearer-token flow.
- `Spec-stripe-payment-checkout.md` — the real-backend booking-creation and deposit-payment contract.
- `Spec-project-environment.md` — `VITE_API_TARGET` / `dev:api` proxy configuration.

## Change Log

- 2026-08-13: Initial reference written, consolidating the real backend's REST surface as relevant to this portal (auth, equipment, depots, rental plans, bookings, payments, analytics, users, recommendations), scoped to routes this portal uses, is documented to use, or plans to use. Excludes mobile/driver-only routes with no web-portal feature (§4). Records backend implementation gaps (§5) and two explicitly undecided items — the unused `/api/auth/logout` route (§6) and the unwired recommender endpoints (§7) — as open/owed rather than resolving them.
- 2026-08-13: Added §7.1 — a proposed (unconfirmed) request/response contract for `POST /api/recommendations/project-spec`, since the authoritative contract isn't available in this repo. Covers the `description`/`attachmentFileNames`/`startDate`/`endDate` request shape and a `recommendationId`/`confidenceScore`/`quoteRef`/`items` response that reuses the existing `POST /api/rentalPlans/{id}/quote` engine for pricing rather than duplicating it.
- 2026-08-13: Added §8 — clarified this portal needs two distinct pricing paths: `POST /api/rentalPlans/{id}/quote` (§8.1, corrected to reach Haystack for AI-informed pricing, reversing this document's earlier speculation that it was Spring-only) and a new, proposed `POST /api/pricing/estimate` (§8.2, Spring-only, never reaches Haystack, reuses `POST /api/bookings`'s pricing formula and request/response shape). §2.4's quote row note updated to cross-reference §8.1.
- 2026-08-13: Added §2.4.1 — field-level requirements for `Spec-rental-plan-cart-checkout.md`'s PR 1-3, marking each rental-plan route's needed fields as confirmed/unconfirmed/change-required. Flags two likely-required backend changes not previously called out at field level: item add/remove must revert a `quoted` plan to `draft` and refresh `updatedAt`, and the quote endpoint must itself set `status = quoted`/refresh `updatedAt` rather than that being read passively later. Notes §2.5 needs the same pass for PR 3's booking-conversion fields, not done here.
