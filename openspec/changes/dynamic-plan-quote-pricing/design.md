# Design: dynamic plan quote pricing (draft)

## Wire contract (upstream, `haystack-fast-api`)

`POST /internal/v1/pricing/quote`. Verified against the real Pydantic models in
`haystack-fast-api` `app/schemas/pricing.py` (`QuoteItemRequest`, `PricingQuoteRequest`,
`QuoteItemResult`, `PricingQuoteResponse`) — **not** just the design-doc prose example, which
showed illustrative string asset ids and cost us a real type mismatch during implementation
(see "Gotcha" below).

```json
// Request
{
  "rental_plan_id": "55",
  "start_date": "2026-09-01",
  "end_date": "2026-09-05",
  "distance_km": 20.0,
  "items": [{ "item_id": "101", "asset_id": 4 }]
}

// Response
{
  "rental_plan_id": "55",
  "currency": "SGD",
  "deposit_rate": 0.30,
  "degraded": false,
  "results": [
    {
      "item_id": "101",
      "asset_id": 4,
      "daily_rate": 182.40,
      "total_price": 2189.60,
      "was_clamped": true,
      "min_daily_rate": 120.00,
      "max_daily_rate": 260.00,
      "model_version": "prod-2026-08-01",
      "degraded": false
    }
  ],
  "warnings": []
}
```

**Gotcha (fixed):** `item_id` and `rental_plan_id` are `str` in haystack's schema (`min_length=1`),
even though Spring's own `RentalPlanRecord.id`/`RentalPlan.id` are numeric `Long` PKs. `asset_id`
is the real numeric `int` PK. Spring sends `String.valueOf(id)` for the former and the raw `Long`
for the latter; `DynamicPricingService` looks up `results[]` by the string item id.

Per-item resolution failures come back as a per-item `error: str | None` field on the matching
`results[]` entry (`QuoteItemResult.error`; confirmed via `tests/test_internal_pricing_api.py` —
e.g. `results[1]['error'] == 'asset_not_found'` with all pricing fields `None`), not a batch-level
error.

## Approach

1. `HaystackPricingClient` (new, `client/haystack/`) — same shape as `HaystackRecommenderClient`: own `RestClient` (own read timeout), own `CircuitBreaker`/`Bulkhead`/`Retry` beans registered in `HaystackClientConfig`, `X-Correlation-Id` header, reuses `HaystackException`/`mapException`. One method: `quote(PricingQuoteRequest, correlationId)`.
2. `DynamicPricingService` (new, `service/`):
   - `priceItems(RentalPlan plan, List<RentalPlanRecord> items)` → `List<PricingClient.ItemPrice>` (one per item, same order).
   - Builds `PricingQuoteRequest` from `plan.getId()`, `plan.getStartDate()`/`getEndDate()`, `pricing.default-distance-km`, and `{itemId, assetId}` pairs.
   - On `HaystackException` (circuit open / timeout / upstream / transport) for the whole call, or a non-null `error`/missing result for a specific item, falls back to `DefaultPricingClient.priceItem(asset, start, end)` for that item only — never throws out of this method.
3. `RentalPlanService.requestQuote()`: when `pricing.dynamic-enabled=true`, calls `DynamicPricingService.priceItems(...)`, writes the returned `dailyRate`/`subtotal` back onto each `RentalPlanRecord` (`rentalPlanRecordRepository.save(...)`), then sums into `totalAmount` exactly as today. When the flag is `false`, `requestQuote()` is byte-for-byte unchanged.
4. No change to `addItem()` / `DefaultPricingClient` — cart-building stays `baseDailyRate` per FR-RP-002.

## Fallback semantics (must decide, locked here)

Never let checkout fail because the ML service is unavailable. If `HaystackPricingClient.quote(...)` throws, or a specific item comes back `degraded`/`error`, that item's price falls back to `DefaultPricingClient` arithmetic silently to the customer — logged at `WARN` with the plan id and item id for ops visibility. This mirrors the precedent already set for the recommender client (`spring-proxy-endpoints` FR-S2B-008 "fail fast... does not invent equipment or prices" — for pricing specifically, "fail fast" would block checkout entirely, which is worse than falling back to the existing, already-trusted base-rate math).

## distance_km

See proposal.md "Open decision." `pricing.default-distance-km` (default `20.0`) until a real postal-code/geocoding heuristic exists.

## Correlation id

`RentalPlanController.requestQuote` reads the optional inbound `X-Correlation-Id` header
(`@RequestHeader(required = false)`) and threads it through
`RentalPlanService.requestQuote(planId, customerEmail, correlationId)` →
`DynamicPricingService.priceItems(plan, items, correlationId)` → `HaystackPricingClient.quote(...)`,
matching the exact convention already used by `RecommendationController` /
`RecommenderSagaService`: propagate the caller's id when present and non-blank, otherwise
generate a fresh `UUID` so the outbound call is still traceable end to end.

## Rollout

`pricing.dynamic-enabled` (env `DYNAMIC_PRICING_ENABLED`, default `false`) gates the new code path in `requestQuote()`. Same pattern as the existing `haystack.retry.ingest-enabled` flag used to gate a risky call path before production confidence is established.
