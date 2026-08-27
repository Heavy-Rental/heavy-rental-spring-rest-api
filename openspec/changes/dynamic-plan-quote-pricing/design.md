# REASONS Canvas: Dynamic (ML) pricing on rental-plan quote

| Field | Value |
|-------|--------|
| **Document type** | OpenSPDD REASONS canvas |
| **Change** | `dynamic-plan-quote-pricing` |
| **Status** | **As-built** |
| **Date** | 2026-08-15 (living specs synced 2026-08-27) |
| **Discipline** | Behavior diverges → update this canvas first, then code. |

**Linked:** OpenSpec FR-RP-004 / FR-RP-006 / FR-PROXY-001 / FR-PROXY-005 · ADR [`adr.md`](./adr.md) · living [`../../specs/rental-plan-quote/`](../../specs/rental-plan-quote/) · [`../../specs/spring-proxy-endpoints/`](../../specs/spring-proxy-endpoints/) · upstream haystack `POST /internal/v1/pricing/quote`

---

## R — Requirements

See delta [`specs/rental-plan-quote/spec.md`](./specs/rental-plan-quote/spec.md) and living FR-RP-004 / FR-RP-006. Quote MUST freeze `totalAmount` from line subtotals. When `pricing.dynamic-enabled=true`, those subtotals MUST be refreshed from haystack `/internal/v1/pricing/quote` immediately before summing. When the flag is off, or an item has no usable dynamic price, MUST fall back to `DefaultPricingClient`. A quote MUST NOT fail solely because the pricing service is unavailable. `degraded=true` with a usable price is used as-is (`WARN` log), not treated as failure.

As-built module default: `application.properties` `pricing.dynamic-enabled=${DYNAMIC_PRICING_ENABLED:true}` (**on**).

### Definition of Done

- Flag off: no haystack hop; totals equal snapshotted add-item subtotals.
- Flag on + healthy haystack: line `dailyRate`/`subtotal` match FastAPI; `totalAmount` is their sum.
- Circuit open / per-item `error`: affected items use `baseDailyRate × inclusive days`; HTTP `200`.
- `HaystackPricingClient` is independent of Call 1/2/3.
- Tests: `HaystackPricingClientTest`, `DynamicPricingServiceTest`, `RentalPlanServiceTest`.

### Scope out

Add-item pricing; frontend field changes; recommender saga; real `distance_km` (follow-up `pricing-postal-distance`); applying haystack `deposit_rate` to bookings.

---

## E — Entities

| Concept | Representation |
|---------|----------------|
| Plan / lines | `RentalPlan`, `RentalPlanRecord.dailyRate` / `subtotal` / `totalAmount` |
| Haystack request | `PricingQuoteRequest` / `PricingQuoteRequestItem` — `rental_plan_id` and `item_id` are **strings** (`String.valueOf` of Long PKs); `asset_id` is numeric |
| Haystack response | `PricingQuoteResponse` / `PricingQuoteResponseItem` — `daily_rate`, `total_price`, `error`, `degraded`, `model_version` |
| Spring price | `PricingClient.ItemPrice(dailyRate, subtotal)` |
| Flag | `PricingProperties.dynamicEnabled` |

Wire (verified against haystack Pydantic, not the prose example):

```json
{
  "rental_plan_id": "55",
  "start_date": "2026-09-01",
  "end_date": "2026-09-05",
  "distance_km": 20.0,
  "items": [{ "item_id": "101", "asset_id": 4 }]
}
```

Per-item failures arrive as `results[].error` with pricing fields `null`, not a batch-level error.

---

## A — Approach

1. `HaystackPricingClient` — same RestClient + CB/bulkhead/retry shape as `HaystackRecommenderClient`; own read timeout (`haystack.timeouts.pricing-read`, 20s); retry max 1; `X-Correlation-Id`.
2. `DynamicPricingService.priceItems` — batch call, map results by string item id, per-item fallback to `DefaultPricingClient`. Never throws for pricing-service failures.
3. `RentalPlanService.requestQuote` — if flag on, price outside the DB transaction (HR-153: do not hold `@Version` across the HTTP call), then reload-and-write keyed by item id.
4. `distance_km` in this change is `pricing.default-distance-km` (20.0). Postal geocoding is the follow-up change.

Rejected alternatives: [`adr.md`](./adr.md).

---

## S — Structure

```text
com.heavy_rental.rest_api
  client.haystack.HaystackPricingClient
  client.haystack.dto.PricingQuoteRequest(Item)
  client.haystack.dto.PricingQuoteResponse(Item)
  config.PricingProperties            // dynamicEnabled
  service.DynamicPricingService
  service.DefaultPricingClient        // fallback + flag-off path
  service.RentalPlanService#requestQuote
  controller.RentalPlanController     // optional X-Correlation-Id
```

No new HTTP route. Portal `RentalPlanResponse` shape unchanged.

---

## O — Operations

```bash
cd heavy-rental-spring-rest-api
./mvnw -Dtest=HaystackPricingClientTest,DynamicPricingServiceTest,RentalPlanServiceTest test
```

1. Config + DTOs matching upstream wire types (`item_id`/`rental_plan_id` as strings).
2. Client + resilience beans.
3. `DynamicPricingService` + wire into `requestQuote` behind the flag.
4. Tests for flag off / success / fallback / degraded.
5. Fold deltas into living `rental-plan-quote` + `spring-proxy-endpoints`.

---

## N — Norms

- RFC 2119 MUST/SHALL in FR-RP-004 / FR-RP-006 / FR-PROXY-*.
- Controllers stay thin; no RestClient from `RentalPlanController`.
- Inclusive day count `ChronoUnit.DAYS.between + 1` on the fallback path.
- Update OpenSpec in the same change as the tests.

---

## S — Safeguards

- MUST NOT fail a quote solely because haystack pricing is unavailable.
- MUST NOT treat `degraded=true` as a reason to discard a usable price.
- MUST NOT call Call 1 ingest or Call 2 recommend from quote.
- MUST NOT apply haystack `deposit_rate` to `Booking.depositAmount`.
- MUST NOT hold the rental-plan `@Version` row lock across the haystack HTTP call.
- MUST NOT change add-item snapshot pricing.
