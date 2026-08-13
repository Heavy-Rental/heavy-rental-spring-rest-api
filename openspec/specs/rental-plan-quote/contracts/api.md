# Contract: `/api/rentalPlans`

| Field | Value |
|-------|--------|
| **Capability** | rental-plan-quote |
| **Status** | As-built |

## Routes

| Method | Path | Notes |
|--------|------|--------|
| `POST` | `/api/rentalPlans` | Create `{ startDate, endDate, siteAddress }` → `201` DRAFT |
| `GET` | `/api/rentalPlans` | Caller's plans only |
| `GET` | `/api/rentalPlans/{id}` | Owner or `404` |
| `POST` | `/api/rentalPlans/{id}/items` | `{ assetId }` |
| `DELETE` | `/api/rentalPlans/{id}/items/{itemId}` | |
| `POST` | `/api/rentalPlans/{id}/quote` | Spring-only sum; locks plan |

Day count: inclusive `ChronoUnit.DAYS.between(start, end) + 1` (as-built convention).

## Pricing note

Today: `DefaultPricingClient` — `baseDailyRate × days`.  
Future: haystack `POST /internal/v1/pricing/quote` behind `PricingClient` — **not built**; open deposit_rate SoT conflict with booking `DEPOSIT_RATE`.

## Related

- [`../../spring-proxy-endpoints/spec.md`](../../spring-proxy-endpoints/spec.md)  
- [`../../equipment-browse/`](../../equipment-browse/)
