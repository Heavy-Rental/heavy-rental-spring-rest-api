# Proposal: Dynamic (ML) pricing for the rental plan quote

| Field | Value |
|-------|--------|
| **Change id** | `dynamic-plan-quote-pricing` |
| **Status** | **Proposed — implementation in progress** |
| **Date** | 2026-08-15 |
| **Route** | `POST /api/rentalPlans/{id}/quote` |

## Why

The web portal's booking-summary screen (shown just before "Proceed to Deposit") displays the rental plan's `totalAmount` exactly as it was frozen by the quote step — see `specification/features/Spec-rental-plan-cart-checkout.md` (React repo, "Checkout Summary Screen: ... No new pricing call occurs here"). Today that frozen total is 100% Spring-only `baseDailyRate` arithmetic (`openspec/specs/rental-plan-quote/spec.md` FR-RP-006, `openspec/specs/spring-proxy-endpoints/spec.md` FR-PROXY-001), even though `haystack-fast-api` already ships a finished, tested, internal-only endpoint for exactly this purpose: `POST /internal/v1/pricing/quote` (`haystack-fast-api` `openspec/specs/dynamic-pricing/spec.md`, "Internal pricing quote endpoint (US-4)", implemented 2026-08-11).

Rather than adding a second, independently-fetched ML price at the booking-summary screen (which would risk drifting from the frozen total that actually determines the deposit charge — the ML price is explicitly volatile, driven by live `period_utilization`/`lead_time_days`), this change makes the **quote step itself** call FastAPI, so the number that flows through quote → booking summary → deposit stays single-sourced.

This is deliberately **not** routed through the recommender saga (`HaystackRecommenderClient`, Call 1/2/3 project-spec ingest). That path is for the "describe your project, get suggested equipment" flow. The plain cart/quote/checkout flow (customer already knows dates + equipment) has no project-spec ingest step, and `haystack-fast-api`'s dynamic-pricing capability is explicit that `/internal/v1/pricing/quote` is a separate, dedicated endpoint that does not touch ingest/recommend.

## What changes

- **ADDED** `HaystackPricingClient`: a new outbound client (mirrors `HaystackRecommenderClient`'s RestClient/circuit-breaker/bulkhead/retry pattern) calling `POST /internal/v1/pricing/quote`.
- **ADDED** `DynamicPricingService`: builds the batch request from a plan's line items, calls `HaystackPricingClient`, and falls back to today's `DefaultPricingClient` arithmetic per-item on any failure (unavailable circuit, timeout, or a per-item error from FastAPI) — checkout must never be blocked because the ML service is down.
- **MODIFIED** `RentalPlanService.requestQuote()`: behind a new flag `pricing.dynamic-enabled` (default `false`), re-prices every line item via `DynamicPricingService` before summing `totalAmount`, instead of trusting the subtotals snapshotted at add-item time.
- **UNCHANGED** `POST /rentalPlans/{id}/items` (cart building): still `Asset.baseDailyRate` per FR-RP-002 — only the quote step gains the ML source.
- **UNCHANGED** `RentalPlanResponse` shape and the React booking-summary screen — it already renders `totalAmount` as-is.

## Open decision

`distance_km` — `haystack-fast-api`'s own spec lists real geocoding as an explicit non-goal ("distance_km computed and sent by Spring Boot (postal-code based)"). `RentalPlan.siteLatitude`/`siteLongitude` columns exist but are never populated by any code path today. This change uses a configurable constant (`pricing.default-distance-km`, default `20.0`, matching haystack's own test-gate default) as an interim value. A postal-code-based heuristic is a candidate follow-up change, not part of this one.

## Out of scope

- Populating `RentalPlan.siteLatitude`/`siteLongitude` or any real geocoding
- Changing `POST /rentalPlans/{id}/items` (cart) pricing
- Frontend changes (React already renders `totalAmount` as-is; no new field is required for this change)
- Routing through the recommender saga / project-spec ingest

## Related

- Living quote SoT: [`../../specs/rental-plan-quote/`](../../specs/rental-plan-quote/)
- Proxy map: [`../../specs/spring-proxy-endpoints/`](../../specs/spring-proxy-endpoints/)
- Recommender (separate capability): [`../../specs/haystack-recommender/`](../../specs/haystack-recommender/)
- Upstream contract: `haystack-fast-api` `openspec/specs/dynamic-pricing/spec.md` (US-4) and `design.md` (wire shapes)
