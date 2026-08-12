# Spring Boot Proxy Endpoints (React → Spring → FastAPI)

FastAPI is never called directly from the browser. Every price or recommendation shown in React goes browser → Spring (authenticated) → FastAPI (internal) → back. Three proxy points, one already exists.

## 1. Quote — already exists, only the internals change

`POST /api/rentalPlans/{id}/quote` (PR #17) is already the browser-facing, authenticated, ownership-scoped proxy for the authoritative price. No new route needed. What changes:

- Internally, call FastAPI's `POST /internal/v1/pricing/quote` with the plan's items as one batch request (not a per-item loop). Confirmed against Haystack's own contract: `items[].asset_id` is the real `Asset.id` (integer), not a string code; the response is always `200` even on partial failure, with per-item `error` (`"asset_not_found"` / `"unrecognized_category: ..."`) and every other pricing field `null` on that item — Spring must treat a per-item `error` as a failure, not just a non-2xx status.
- Send `Idempotency-Key` (UUID) and forward/mint `X-Correlation-Id`, per Haystack's conventions.
- Only write item rates / `totalAmount` / status → `QUOTED` after a fully successful FastAPI response (no per-item `error`), inside the existing `@Transactional` method (gives rollback for free — see team-action-items.md).
- Add the double-submit guard (`@Version` / row lock) here.

**Open item — deposit rate source conflict:** Haystack's contract returns `deposit_rate` (currently a fixed `0.30`) on every quote response and says to read it from there rather than hardcoding a copy. `SPEC-rental-plan-quote.md` §5/§7 currently has `RentalPlanService`/`BookingService` computing the 30%/70% split from their own `DEPOSIT_RATE` constant, independent of any FastAPI call. Both happen to agree today (0.30), but nothing keeps them in sync if either changes. Needs a decision before this proxy is rewired: keep Spring's constant as the source of truth, or switch to Haystack's `deposit_rate` field. Not resolved here.

## 2. Estimate — new

`POST /api/pricing/estimate` — backs the "estimated price" shown while browsing/selecting dates, before a cart exists.

**Request (React → Spring):**
```json
{
  "items": [
    { "assetId": "AST-EXC-004", "startDate": "2026-09-01", "endDate": "2026-09-12" }
  ]
}
```

**Response (Spring → React, proxied + camelCased from FastAPI):**
```json
{
  "results": [
    { "assetId": "AST-EXC-004", "dailyRate": 182.40, "totalPrice": 2189.60, "currency": "SGD" }
  ],
  "degraded": false
}
```

**Open decisions:**
- Auth: not tied to a customer or cart, so it can reasonably be public/unauthenticated — unlike the quote proxy. Confirm this is intended, not an oversight.
- Fallback behavior if FastAPI is slow/down: for a preview widget, falling back to the equipment's static listed rate (with a subtle "estimate unavailable" indicator) is likely better UX than a hard failure or blank price on the browse page — needs a product call.
- Scope calls to the equipment detail page / selected item, not every card in a browse grid, to avoid one live ML+DB call per card per page load.

## 3. Recommendations — new, needs real auth handling (not a thin passthrough)

**Corrected 2026-08-12 against Haystack's own contract** (`specification/temporary/spring-boot-api-contract.md`, deleted once this reconciliation landed): this section used to describe a single `POST /api/v1/recommendations/from-project-spec` call returning a ranked, priced `resultsByNeed`. That endpoint doesn't exist. The real flow is **two separate FastAPI calls**, and neither returns a ranked asset list:

1. `POST /internal/v1/recommendations/submitprojectspecification` — ingest. Takes `user_id`, `project_text` (and/or an uploaded file), optional dates. Returns an `ingest_id` plus a *display-only* `needs_summary` (structured needs extracted from the text) and `expected_budget` — explicitly **not** ranked fleet recommendations.
2. `POST /internal/v1/recommendations/project-knowledge/getassetrecommendations` — Q&A, scoped to the `ingest_id` from step 1. Takes a natural-language `query` and returns a free-text `answer` plus `research_hits`/`graph_hits` (retrieval evidence) — still not a structured, priced asset list with `assetId`/`rank`/`pricing` fields.

`POST /api/recommendations`'s design needs to be rethought against this shape, not patched — no single Haystack response in this flow maps onto the `resultsByNeed` shape below. Until that redesign happens, treat the request/response shapes previously documented here as **aspirational, not backed by any real Haystack endpoint**.

What still holds regardless of the redesign: Spring must authenticate the caller and inject the real `user_id` itself — Haystack's `user_id` field is unvalidated and trusts whatever the caller sends.

**Previous (aspirational) request/response shape, kept for reference pending redesign:**

**Request (React → Spring, authenticated via Bearer token from `/api/auth/login`):**
```json
{
  "projectText": "Need two scissor lifts for indoor elevated work ~8m",
  "startDate": "2026-09-01",
  "endDate": "2026-09-12",
  "includePricing": true
}
```
No `userId`/`customerId` field in this body — Spring resolves it server-side from the token before calling FastAPI.

**Response (Spring → React, camelCased):**
```json
{
  "recommendationId": "rec_01HZX...",
  "startDate": "2026-09-01",
  "endDate": "2026-09-12",
  "resultsByNeed": [
    {
      "needId": "need_1",
      "item": {
        "equipmentType": "Scissors Lift",
        "assetId": "AST-SL-011",
        "rank": 1,
        "rationale": "...",
        "pricing": { "dailyRate": 150.0, "totalPrice": 1800.0, "currency": "SGD", "depositRate": 0.30 },
        "availability": "available"
      },
      "warnings": []
    }
  ]
}
```

**Open decisions (revised):**
- What does `/api/recommendations` actually return to React given Haystack only offers ingest (structured needs, unranked) + Q&A (free-text answer, no structured asset list)? Likely needs either a UI redesign around free-text answers, or a third Haystack endpoint that doesn't exist yet — flag to the Haystack team before building React against the shape above.
- `ingest_id` is process-local/in-memory on Haystack's side and does not survive a Haystack restart — if `/api/recommendations` needs a "come back later and ask more questions" flow, Spring needs its own persistence for `ingest_id` (Haystack's ingest response doesn't currently expose `kg_artifact_path`, so there's no restart-recovery path to rely on either).
- Once a recommended `assetId` is picked (however that ends up being surfaced to the user), adding it to the cart is still just the existing `POST /api/rentalPlans/{id}/items` call — no new endpoint needed there.

## Summary

| Proxy | Status | Auth | Notes |
|---|---|---|---|
| `POST /api/rentalPlans/{id}/quote` | Exists — rewire internals | Required, ownership-scoped | Batch call to FastAPI; must handle per-item `error` on an otherwise-`200` response; deposit-rate source (Spring constant vs Haystack field) unresolved |
| `POST /api/pricing/estimate` | New | Open — likely public | No matching Haystack endpoint exists; fallback-to-listed-rate decision still needed |
| `POST /api/recommendations` | Needs redesign | Required — Spring injects real customer ID | Haystack offers ingest (structured needs, unranked) + Q&A (free-text answer) only — no ranked/priced asset list endpoint exists; response shape in §3 is aspirational |

---

## Change control

| Date | Note |
|------|------|
| 2026-08-12 | Reconciled §1 and §3 against Haystack's own integration contract (`specification/temporary/spring-boot-api-contract.md`, an `haystack-fast-api`-authored document, deleted once this reconciliation landed). §3's single-call `from-project-spec` recommend flow doesn't exist — replaced with the real two-call ingest + Q&A flow, and the old response shape marked aspirational pending a redesign. §1 gained the per-item-`error`-on-200 handling requirement, the `Idempotency-Key`/`X-Correlation-Id` conventions, and an open item on `deposit_rate` source (Spring's own constant vs Haystack's field) conflicting with `SPEC-rental-plan-quote.md` §5/§7. §2 (estimate) unchanged — no matching Haystack endpoint exists yet either way. |
