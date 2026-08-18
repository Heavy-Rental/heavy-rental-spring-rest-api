# Delta: spring-proxy-endpoints (dynamic pricing)

## MODIFIED Requirements

### Requirement: FR-PROXY-001 Rental plan quote pricing source is flagged

The system MUST implement `POST /api/rentalPlans/{id}/quote` using `DynamicPricingService`, which calls a FastAPI-backed `PricingClient` (`HaystackPricingClient` → haystack `POST /internal/v1/pricing/quote`) when `pricing.dynamic-enabled=true`, and falls back to Spring-side `DefaultPricingClient` (`Asset.baseDailyRate`) arithmetic per-item on any pricing-service failure or when the flag is `false`. The system MUST NOT claim this route unconditionally proxies to haystack — the proxy hop is flag-gated and has a documented per-item fallback, not an unconditional dependency.

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
- GIVEN `pricing.dynamic-enabled=false` (default)
- WHEN the owner requests a quote
- THEN Spring computes totals from stored rates without an HTTP call to haystack, exactly as before this change

## ADDED Requirements

### Requirement: FR-PROXY-005 Pricing quote is a dedicated client, not the recommender saga

`HaystackPricingClient` (`POST /internal/v1/pricing/quote`) MUST be a client independent of `HaystackRecommenderClient` (Call 1/2/3 project-spec ingest/recommend/Q&A). Rental-plan quote pricing MUST NOT trigger project-spec ingest or the recommend saga.

#### Scenario: Quote does not touch the recommender saga
- GIVEN a rental plan quote request with `pricing.dynamic-enabled=true`
- WHEN Spring prices the plan's items via haystack
- THEN no Call 1 (ingest) or Call 2 (recommend) request is made
- AND only `POST /internal/v1/pricing/quote` is called

## Route summary

| Route | Direction | Status |
|-------|-----------|--------|
| `POST /api/rentalPlans/{id}/quote` | React → Spring → FastAPI (flagged) | **Flag-gated** — `pricing.dynamic-enabled`; Spring-only fallback per item |
| `POST /api/recommendations/project-spec` (+ knowledge-query, GET) | React → Spring → FastAPI | **As-built** S2b — see haystack-recommender |
| `POST /api/pricing/estimate` | React → Spring only (by design) | Design only — [`../../pricing-estimate/`](../../pricing-estimate/) |
