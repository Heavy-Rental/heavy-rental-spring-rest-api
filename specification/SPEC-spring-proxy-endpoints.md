# Spring Boot Proxy Endpoints (React → Spring → FastAPI)

FastAPI is never called directly from the browser. Every price or recommendation shown in React goes browser → Spring (authenticated) → FastAPI (internal) → back. Three proxy points, one already exists.

## 1. Quote — already exists, only the internals change

`POST /api/rentalPlans/{id}/quote` (PR #17) is already the browser-facing, authenticated, ownership-scoped proxy for the authoritative price. No new route needed. What changes:

- Internally, call FastAPI's `POST /internal/v1/pricing/quote` with the plan's items as one batch request (not a per-item loop).
- Only write item rates / `totalAmount` / status → `QUOTED` after a fully successful FastAPI response, inside the existing `@Transactional` method (gives rollback for free — see team-action-items.md).
- Add the double-submit guard (`@Version` / row lock) here.

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

`POST /api/recommendations` — proxies FastAPI's `POST /api/v1/recommendations/from-project-spec`.

This one can't just forward the client's body as-is: FastAPI's spec explicitly leaves auth/JWT out of scope and accepts `user_id` as an unvalidated raw field. Spring must authenticate the request and inject the real customer ID itself.

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

**Open decisions:**
- FastAPI's as-built recommend path currently routes to indexing instead of real recommendations — gate this behind a feature flag on the React side, or confirm reattachment timeline before building UI against the full response shape.
- Once a recommended `assetId` is picked, adding it to the cart is just the existing `POST /api/rentalPlans/{id}/items` call — no new endpoint needed there.

## Summary

| Proxy | Status | Auth | Notes |
|---|---|---|---|
| `POST /api/rentalPlans/{id}/quote` | Exists — rewire internals | Required, ownership-scoped | Batch call to FastAPI, all-or-nothing |
| `POST /api/pricing/estimate` | New | Open — likely public | Fallback-to-listed-rate decision needed |
| `POST /api/recommendations` | New | Required — Spring injects real customer ID | FastAPI trusts `user_id` with no validation, so Spring must not pass through client-supplied IDs |
