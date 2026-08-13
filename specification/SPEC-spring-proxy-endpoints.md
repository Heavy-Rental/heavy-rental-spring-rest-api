# Spring Boot Proxy Endpoints (React → Spring → FastAPI)

FastAPI is never called directly from the browser. Every price or recommendation shown in React goes browser → Spring (authenticated) → FastAPI (internal) → back. Two proxy points, both now exist.

## 1. Quote — already exists, only the internals change

`POST /api/rentalPlans/{id}/quote` (PR #17) is already the browser-facing, authenticated, ownership-scoped proxy for the authoritative price. No new route needed. What changes:

- Internally, call FastAPI's `POST /internal/v1/pricing/quote` with the plan's items as one batch request (not a per-item loop). Confirmed against Haystack's own contract: `items[].asset_id` is the real `Asset.id` (integer), not a string code; the response is always `200` even on partial failure, with per-item `error` (`"asset_not_found"` / `"unrecognized_category: ..."`) and every other pricing field `null` on that item — Spring must treat a per-item `error` as a failure, not just a non-2xx status.
- Send `Idempotency-Key` (UUID) and forward/mint `X-Correlation-Id`, per Haystack's conventions.
- Only write item rates / `totalAmount` / status → `QUOTED` after a fully successful FastAPI response (no per-item `error`), inside the existing `@Transactional` method (gives rollback for free — see team-action-items.md).
- Add the double-submit guard (`@Version` / row lock) here.

**Open item — deposit rate source conflict:** Haystack's contract returns `deposit_rate` (currently a fixed `0.30`) on every quote response and says to read it from there rather than hardcoding a copy. `SPEC-rental-plan-quote.md` §5/§7 currently has `RentalPlanService`/`BookingService` computing the 30%/70% split from their own `DEPOSIT_RATE` constant, independent of any FastAPI call. Both happen to agree today (0.30), but nothing keeps them in sync if either changes. Needs a decision before this proxy is rewired: keep Spring's constant as the source of truth, or switch to Haystack's `deposit_rate` field. Not resolved here.

## 2. Recommendations — implemented, as-built

**Superseded 2026-08-13:** this section used to describe a single, undesigned `POST /api/recommendations` call and treat its request/response shape as "aspirational, not backed by any real Haystack endpoint," pending a redesign. That redesign happened and shipped — full as-built contract now lives in [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md) §5, tracked in [`SPEC-api-index.md`](./SPEC-api-index.md) §2.6. Summary, not a restatement:

- Real route is `POST /api/recommendations/project-spec` (not bare `/api/recommendations`), orchestrating Call 1 ingest then Call 2 recommend in one request.
- Call 2 (`.../project-knowledge/getassetrecommendations`) turned out to be the **recommend/quote** call, not Q&A — it returns `quoteRef`/`items[]`. Q&A is a separate **Call 3** (`.../project-knowledge/query`), exposed as its own follow-up route `POST /api/recommendations/{recommendationId}/knowledge-query`.
- Spring authenticates the caller and injects the real `user_id`/`haystack_user_id` itself, as this section originally required — Haystack's own `user_id` field is unvalidated.
- Adding a recommended asset to the cart is still just the existing `POST /api/rentalPlans/{id}/items` call — no new endpoint needed there.

See `SPEC-haystack-recommender-client.md` for the full request/response contract, error mapping, and resilience behavior.

## 3. Estimate — removed, no matching Haystack endpoint

**Removed 2026-08-13:** this section used to propose a new `POST /api/pricing/estimate` proxy backing a pre-cart "estimated price" preview. No Haystack endpoint for a standalone price estimate exists — the only Haystack pricing call is `POST /internal/v1/pricing/quote` (§1 above), which is plan/cart-scoped, not a pre-cart preview. Removed rather than left as a permanent "planned" placeholder with nothing to back it; re-add if/when a real Haystack estimate endpoint exists to proxy.

## Summary

| Proxy | Status | Auth | Notes |
|---|---|---|---|
| `POST /api/rentalPlans/{id}/quote` | Exists — rewire internals | Required, ownership-scoped | Batch call to FastAPI; must handle per-item `error` on an otherwise-`200` response; deposit-rate source (Spring constant vs Haystack field) unresolved |
| `POST /api/recommendations/project-spec` + `POST /api/recommendations/{id}/knowledge-query` | Implemented (S2b) | Required — Spring injects real customer ID | Full contract: [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md) §5 |

---

## Change control

| Date | Note |
|------|------|
| 2026-08-12 | Reconciled §1 and §3 against Haystack's own integration contract (`specification/temporary/spring-boot-api-contract.md`, an `haystack-fast-api`-authored document, deleted once this reconciliation landed). §3's single-call `from-project-spec` recommend flow doesn't exist — replaced with the real two-call ingest + Q&A flow, and the old response shape marked aspirational pending a redesign. §1 gained the per-item-`error`-on-200 handling requirement, the `Idempotency-Key`/`X-Correlation-Id` conventions, and an open item on `deposit_rate` source (Spring's own constant vs Haystack's field) conflicting with `SPEC-rental-plan-quote.md` §5/§7. §2 (estimate) unchanged — no matching Haystack endpoint exists yet either way. |
| 2026-08-13 | **§2 (recommendations) and §3 (estimate) rewritten.** Old §3 (recommendations) was still describing the pre-redesign shape as "aspirational" a day after the redesign actually shipped (see `SPEC-haystack-recommender-client.md` v2.0.0/§5, `SPEC-api-index.md` §2.6) — replaced with a pointer to the as-built contract instead of restating it, and renumbered to §2. Old §2 (`POST /api/pricing/estimate`) removed outright: it was never implemented, and no Haystack endpoint for a pre-cart price estimate exists to back it (only the plan/cart-scoped `POST /internal/v1/pricing/quote` used by §1) — kept it around as a "planned" section risked it being read as a real near-term commitment. Also removed from `SPEC-api-index.md` §2.5 in the same pass. |
