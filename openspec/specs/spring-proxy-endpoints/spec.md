# Spring Proxy Endpoints — Source of Truth

## Purpose

Document every Spring route that **does**, **will**, or **deliberately does not** call `haystack-fast-api`. Web-facing routes with no Haystack dimension (bookings, payments, users, equipment) are out of scope for this capability.

**Status:** **As-built** (including flag-gated Haystack pricing hop on quote).

FastAPI is never called from the browser. Any price or recommendation that involves Haystack goes browser → Spring (authenticated) → FastAPI (internal) → back.

OneMap (Singapore geocoding) is a separate external hop used for postal-code validation and `distance_km`. It is **not** Haystack; see [`../postal-code-validation/`](../postal-code-validation/) and rental-plan-quote FR-RP-012.

## Requirements

### Requirement: FR-PROXY-001 Rental plan quote pricing source is flagged

The system MUST implement `POST /api/rentalPlans/{id}/quote` using `DynamicPricingService`, which calls a FastAPI-backed `HaystackPricingClient` (`POST /internal/v1/pricing/quote`) when `pricing.dynamic-enabled=true` (as-built module default in `application.properties` is **on**), and falls back to Spring-side `DefaultPricingClient` (`Asset.baseDailyRate`) arithmetic per-item on any pricing-service failure or when the flag is `false`. The system MUST NOT claim this route unconditionally proxies to haystack — the hop is flag-gated and has a documented per-item fallback.

#### Scenario: Quote calls haystack when the flag is on and it is healthy
- GIVEN `pricing.dynamic-enabled=true` and haystack's pricing endpoint is healthy
- WHEN the owner requests a quote
- THEN Spring calls `POST /internal/v1/pricing/quote` for the plan's items
- AND the response is ownership-scoped and authenticated

#### Scenario: Quote falls back to Spring arithmetic when haystack is unavailable
- GIVEN `pricing.dynamic-enabled=true` and haystack's pricing circuit is open
- WHEN the owner requests a quote
- THEN Spring computes that item's total from `Asset.baseDailyRate` without failing the request

#### Scenario: Flag off is unchanged Spring-only behavior
- GIVEN `pricing.dynamic-enabled=false`
- WHEN the owner requests a quote
- THEN Spring computes totals from stored rates without an HTTP call to haystack

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

### Requirement: FR-PROXY-005 Pricing quote is a dedicated client, not the recommender saga

`HaystackPricingClient` (`POST /internal/v1/pricing/quote`) MUST be a client independent of `HaystackRecommenderClient` (Call 1/2/3 project-spec ingest/recommend/Q&A). Rental-plan quote pricing MUST NOT trigger project-spec ingest or the recommend saga. It MUST use its own read timeout, retry, bulkhead, and circuit-breaker settings (`haystack.timeouts.pricing-read`, `haystack.retry.pricing-max-attempts`, `haystack.resilience.bulkhead-pricing-max-concurrent`).

#### Scenario: Quote does not touch the recommender saga
- GIVEN a rental plan quote request with `pricing.dynamic-enabled=true`
- WHEN Spring prices the plan's items via haystack
- THEN no Call 1 (ingest) or Call 2 (recommend) request is made
- AND only `POST /internal/v1/pricing/quote` is called

## Route summary

| Route | Direction | Status |
|-------|-----------|--------|
| `POST /api/rentalPlans/{id}/quote` | React → Spring → FastAPI (flagged) + OneMap for `distance_km` | **As-built, flag-gated** — `pricing.dynamic-enabled` (module default **on**); Spring-only fallback per item; OneMap failure falls back to `pricing.default-distance-km` |
| `POST /api/recommendations/project-spec` (+ knowledge-query, GET) | React → Spring → FastAPI | **As-built** S2b — see haystack-recommender |
| `GET /api/postalCodes/{postalCode}` | React → Spring → OneMap (not Haystack) | **As-built** — [`../postal-code-validation/`](../postal-code-validation/) |
| `POST /api/pricing/estimate` | React → Spring only (by design) | Design only — [`../../changes/pricing-estimate/`](../../changes/pricing-estimate/) |

## Related

- Portal API contract: [`../haystack-recommender/contracts/portal-api.md`](../haystack-recommender/contracts/portal-api.md)
- Behavior SoT: [`../haystack-recommender/spec.md`](../haystack-recommender/spec.md)
- Rental plan quote details: [`../rental-plan-quote/`](../rental-plan-quote/)
- Postal / OneMap: [`../postal-code-validation/`](../postal-code-validation/)
- As-built packs: [`../../changes/dynamic-plan-quote-pricing/`](../../changes/dynamic-plan-quote-pricing/) · [`../../changes/pricing-postal-distance/`](../../changes/pricing-postal-distance/)
