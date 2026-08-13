# Spring Boot Proxy Endpoints (React → Spring → FastAPI)

| Field | Value |
|-------|--------|
| **Document type** | Cross-cutting: every point where a Spring route does — or deliberately does not — proxy to `haystack-fast-api`. Web-facing routes with no Haystack dimension (bookings, payments, users, equipment) are out of scope; see [`SPEC-api-index.md`](./SPEC-api-index.md) for those. |
| **Status** | **Restored 2026-08-13.** This file existed, was deleted the same day (commit `b636f25`, "redundant with `SPEC-api-index.md` + `SPEC-rental-plan-quote.md`"), and is restored here — see §0. |
| **Reconciled against** | [`Feasibility_Study_Spring/temporary/spring-boot-api-contract.md`](../Feasibility_Study_Spring/temporary/spring-boot-api-contract.md) (haystack-fast-api-authored, dropped into this repo 2026-08-13, commit `193b4fd`) |
| **Related specs** | [`SPEC-api-index.md`](./SPEC-api-index.md), [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md) §5.0/§5.1, [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md), [`SPEC-pricing-estimate.md`](./SPEC-pricing-estimate.md) |

FastAPI is never called directly from the browser. Any price or recommendation shown in React that involves Haystack goes browser → Spring (authenticated) → FastAPI (internal) → back. This file is the single place that says, per Spring route, whether that hop exists today, is designed but unbuilt, or deliberately does not exist.

---

## 0. Why this file exists again

The retirement note (now superseded) argued this file's only non-duplicate content — the FastAPI-backed `PricingClient` design — had been folded into `SPEC-rental-plan-quote.md` §5.1, making a separate file redundant. That reasoning held for *design* content, but missed a *framing* problem: folding "Spring will proxy to Haystack here" into a spec about the plan/quote feature made it easy to read as already true. That's exactly what happened — a separate, portal-facing reference document stated `POST /api/rentalPlans/{id}/quote` proxies to Haystack, sourced from a conversational clarification rather than any backend documentation. Verified 2026-08-13 against `RentalPlanService`/`PricingClient`/`DefaultPricingClient`: **false as of current code** (full correction: `SPEC-rental-plan-quote.md` §5.0). A dedicated file whose entire job is "what does Spring actually send to FastAPI, today, right now" is worth keeping distinct for exactly this reason — it has no other content competing for the reader's attention, so "not built yet" can't get lost.

This restoration also reconciles the fresh Haystack-side contract drop at [`Feasibility_Study_Spring/temporary/spring-boot-api-contract.md`](../Feasibility_Study_Spring/temporary/spring-boot-api-contract.md), following the same drop-in → reconcile → retire-the-drop convention this file used on 2026-08-12 (see §4 change log). That file confirms `POST /internal/v1/pricing/quote` is **live** on the Haystack side today — Spring just doesn't call it yet (§1).

---

## 1. Quote — Spring-only today; FastAPI rewire is designed, not built

`POST /api/rentalPlans/{id}/quote` (PR #17) is the browser-facing, authenticated, ownership-scoped route for a rental plan's price. **It does not call FastAPI today.** Confirmed 2026-08-13 directly against source: `RentalPlanService.requestQuote` sums `RentalPlanRecord.subtotal` values computed at item-add time by the injected `PricingClient`; the only registered implementation, `DefaultPricingClient`, is pure arithmetic (`dailyRate × days`) with no HTTP client and no reference to Haystack anywhere in the class. Full correction and verification detail: `SPEC-rental-plan-quote.md` §5.0.

A second, FastAPI-backed `PricingClient` implementation is **designed but not built** (`SPEC-rental-plan-quote.md` §5.1) — this is the "rewire" this section originally (and still) describes:

- Internally, would call FastAPI's `POST /internal/v1/pricing/quote` with the plan's items as one batch request, not a per-item loop. Confirmed **live** on the Haystack side (`Feasibility_Study_Spring/temporary/spring-boot-api-contract.md`, endpoint inventory, status "Live"): `items[].asset_id` is the real `Asset.id` (integer), not a string code; the response is always `200` even on partial per-item failure, with per-item `error` (`"asset_not_found"` / `"unrecognized_category: ..."`) and every other pricing field `null` on that item on failure — Spring must treat a per-item `error` as a failure, not just rely on non-2xx status.
- Would send `Idempotency-Key` (UUID) and forward/mint `X-Correlation-Id`, per Haystack's conventions — same headers `SPEC-haystack-recommender-client.md` §9 already requires for the recommender client.
- Would only write item rates/`totalAmount`/status→`QUOTED` after a fully successful FastAPI response (no per-item `error`), inside the existing `@Transactional` method (rollback for free).
- The existing `@Version` optimistic-locking double-submit guard (`SPEC-rental-plan-quote.md` §5) covers this path unchanged, regardless of which `PricingClient` implementation is active.

**Open item — deposit rate source conflict (unresolved, unchanged from before):** Haystack's contract returns `deposit_rate` (currently a fixed `0.30`) on every `/internal/v1/pricing/quote` response and says to read it from there rather than hardcoding a copy. `RentalPlanService`/`BookingService` currently compute the 30%/70% split from their own `DEPOSIT_RATE` constant, independent of any FastAPI call. Both happen to agree today (`0.30`), but nothing keeps them in sync if either changes. Needs a decision before the FastAPI `PricingClient` is built: keep Spring's constant as the source of truth, or switch to Haystack's `deposit_rate` field. Not resolved here — tracked identically in `SPEC-rental-plan-quote.md` §5.1.

---

## 2. Recommendations — implemented, as-built

Real route is `POST /api/recommendations/project-spec` (not bare `/api/recommendations`), orchestrating Call 1 ingest then Call 2 recommend in one request, plus a follow-up `POST /api/recommendations/{recommendationId}/knowledge-query` (Call 3 chatbot Q&A) and `GET /api/recommendations/{recommendationId}` (stored session). Spring authenticates the caller and injects the real `user_id`/`haystack_user_id` itself — Haystack's own `user_id` field is unvalidated. Adding a recommended asset to the cart is still just the existing `POST /api/rentalPlans/{id}/items` call — no separate endpoint for that.

Full request/response contract, error mapping, and resilience behavior: [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md) §5. Tracked in [`SPEC-api-index.md`](./SPEC-api-index.md) §2.6.

**Reconciled against the fresh drop (2026-08-13):** `spring-boot-api-contract.md`'s own change log records that its authors had the Call 2/Call 3 response shapes swapped as recently as this same day, then self-corrected — the version reconciled here already reflects the fix (Call 2 = recommend/quote envelope; Call 3 = chatbot Q&A), matching what `SPEC-haystack-recommender-client.md` has documented since v2.0.0 (2026-08-12). No change needed on the Spring side from this reconciliation pass.

---

## 3. Estimate — new, Spring-only, deliberately **not** a Haystack proxy

`POST /api/pricing/estimate` is being (re)designed as new work — see [`SPEC-pricing-estimate.md`](./SPEC-pricing-estimate.md) (design only, no code yet, per a 2026-08-13 decision to document before building). This is a genuinely different requirement from what this section previously described (a proposed, never-built proxy to a standalone Haystack estimate endpoint that doesn't exist) — it is **not** a resurrection of that idea.

The important distinction from every other row in this file: this route is **deliberately not a proxy**. It does not call `POST /internal/v1/pricing/quote` or any other Haystack endpoint, live or otherwise — it's designed to reuse the same Spring-only arithmetic §1 describes (`baseDailyRate × days`), for a caller that wants a price without first creating/owning a `RentalPlan`. Listed here explicitly, in the one file whose whole purpose is "what proxies to Haystack," so a future implementer doesn't assume this route needs a FastAPI hop by default.

---

## Summary table

| Route | Direction | Status | Notes |
|---|---|---|---|
| `POST /api/rentalPlans/{id}/quote` | React → Spring (Spring-only today) | As-built: Spring-only. FastAPI hop: **designed, not built** | §1; correction detail in `SPEC-rental-plan-quote.md` §5.0 |
| `POST /api/recommendations/project-spec` + `.../knowledge-query` + `GET .../{id}` | React → Spring → FastAPI | Implemented (S2b) | §2; full contract `SPEC-haystack-recommender-client.md` §5 |
| `POST /api/pricing/estimate` | React → Spring (never reaches FastAPI, by design) | Design only, not built | §3; full contract `SPEC-pricing-estimate.md` |

---

## 4. Change control

| Date | Note |
|------|------|
| 2026-08-11 | Initial version. §1 quote (rewire planned), §2 recommendations (pre-redesign shape, aspirational), §3 estimate (proposed, not backed by any real Haystack endpoint). |
| 2026-08-12 | Reconciled §1 and §3 against Haystack's own integration contract (`specification/temporary/spring-boot-api-contract.md`, an `haystack-fast-api`-authored document, deleted once this reconciliation landed). §3's single-call `from-project-spec` recommend flow doesn't exist — replaced with the real two-call ingest + Q&A flow, and the old response shape marked aspirational pending a redesign. §1 gained the per-item-`error`-on-200 handling requirement, the `Idempotency-Key`/`X-Correlation-Id` conventions, and an open item on `deposit_rate` source (Spring's own constant vs Haystack's field) conflicting with `SPEC-rental-plan-quote.md` §5/§7. §2 (estimate) unchanged — no matching Haystack endpoint exists yet either way. |
| 2026-08-13 | §2 (recommendations) and §3 (estimate) rewritten. Old §3 (recommendations) was still describing the pre-redesign shape as "aspirational" a day after the redesign actually shipped — replaced with a pointer to the as-built contract, renumbered to §2. Old §2 (`POST /api/pricing/estimate`) removed outright: never implemented, no Haystack endpoint to back it. |
| 2026-08-13 (later) | **File deleted** (commit `b636f25`) as redundant with `SPEC-api-index.md` + `SPEC-rental-plan-quote.md` §5.1. |
| 2026-08-13 (later still) | **File restored**, per explicit request, following a web-portal API audit that surfaced exactly the ambiguity this file's absence enabled: a portal-facing reference document took §1's "already the proxy... internals change" framing (accurate at the time — the FastAPI hop was believed imminent) as meaning the hop already existed, which it doesn't. §1 rewritten to lead with current (Spring-only) behavior before describing the planned rewire, to prevent the same misreading. §0 (new) explains the restoration. Reconciled against a fresh Haystack-side contract drop (`Feasibility_Study_Spring/temporary/spring-boot-api-contract.md`, commit `193b4fd`), which confirms `POST /internal/v1/pricing/quote` is live — no Spring-side change resulted, since Spring doesn't call it yet either way (§1). New §3 written for the new, explicitly non-Haystack `/api/pricing/estimate` requirement (see `SPEC-pricing-estimate.md`), replacing the old §3's "removed" note. |
