# Spring Proxy Endpoints — Source of Truth

## Purpose

Document every Spring route that **does**, **will**, or **deliberately does not** call `haystack-fast-api`. Web-facing routes with no Haystack dimension (bookings, payments, users, equipment) are out of scope for this capability.

**Status:** **As-built**.

FastAPI is never called from the browser. Any price or recommendation that involves Haystack goes browser → Spring (authenticated) → FastAPI (internal) → back.

## Requirements

### Requirement: FR-PROXY-001 Rental plan quote is Spring-only today

The system MUST implement `POST /api/rentalPlans/{id}/quote` using Spring-side pricing arithmetic (`PricingClient` / `DefaultPricingClient`). The system MUST NOT claim that this route currently proxies to haystack `POST /internal/v1/pricing/quote` until a FastAPI-backed `PricingClient` is implemented and verified.

#### Scenario: Quote does not call haystack
- GIVEN a rental plan with line items and computed subtotals
- WHEN the owner requests a quote
- THEN Spring computes totals from stored rates without an HTTP call to haystack
- AND the response is ownership-scoped and authenticated

#### Scenario: FastAPI pricing client remains design-only
- GIVEN haystack exposes `POST /internal/v1/pricing/quote` (live upstream)
- WHEN no FastAPI-backed `PricingClient` bean is registered
- THEN Spring continues to use local arithmetic only

### Requirement: FR-PROXY-002 Recommendations proxy to haystack (S2b)

The system MUST orchestrate haystack Call 1 then Call 2 on `POST /api/recommendations/project-spec`, Call 3 on knowledge-query, and session read on GET by id, per the haystack-recommender capability. The system MUST derive haystack `user_id` server-side from the JWT principal.

#### Scenario: Project-spec dual-hop
- GIVEN an authenticated portal submit
- WHEN `POST /api/recommendations/project-spec` succeeds
- THEN Spring called haystack ingest then recommend
- AND the portal response includes Call 2 quote fields (not Call 3 `answer`)

#### Scenario: Knowledge-query is Call 3 only
- GIVEN a stored recommendation session with `ingest_id`
- WHEN the owner posts a knowledge query
- THEN Spring calls haystack `.../project-knowledge/query` only
- AND does not re-ingest

### Requirement: FR-PROXY-003 Pricing estimate is not a haystack proxy

If/when `POST /api/pricing/estimate` is implemented (see active change `openspec/changes/pricing-estimate/`), the system MUST treat it as Spring-only arithmetic by design (not a proxy to haystack pricing), unless a future OpenSpec change explicitly redesigns it.

#### Scenario: Estimate design remains non-proxy
- GIVEN the pricing-estimate change is design-only or implemented as Spring-only
- WHEN a client requests an estimate
- THEN no haystack HTTP call is required for that route

### Requirement: FR-PROXY-004 Document proxy status in one place

The system documentation MUST keep a single capability (this spec) that states, per route, whether the haystack hop exists today, is designed but unbuilt, or deliberately does not exist — so portal docs cannot invent a proxy that code does not perform.

#### Scenario: Summary table is authoritative for hop existence
- GIVEN engineers need to know if a Spring route calls FastAPI
- WHEN they consult this capability
- THEN they can determine hop status without reading feature design as if it were as-built

## Route summary

| Route | Direction | Status |
|-------|-----------|--------|
| `POST /api/rentalPlans/{id}/quote` | React → Spring only | As-built Spring-only; FastAPI hop **designed, not built** |
| `POST /api/recommendations/project-spec` (+ knowledge-query, GET) | React → Spring → FastAPI | **As-built** S2b — see haystack-recommender |
| `POST /api/pricing/estimate` | React → Spring only (by design) | Design only — [`../../changes/pricing-estimate/`](../../changes/pricing-estimate/) |

## Related

- Portal API contract: [`../haystack-recommender/contracts/portal-api.md`](../haystack-recommender/contracts/portal-api.md)
- Behavior SoT: [`../haystack-recommender/spec.md`](../haystack-recommender/spec.md)
- Rental plan quote details: [`../rental-plan-quote/`](../rental-plan-quote/)
