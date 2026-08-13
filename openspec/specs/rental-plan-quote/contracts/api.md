# Contract: `/api/rentalPlans`

| Field | Value |
|-------|--------|
| **Capability** | rental-plan-quote |
| **Status** | As-built |

## Routes

| Method | Path | Notes |
|--------|------|--------|
| `POST` | `/api/rentalPlans` | Create `{ startDate, endDate, siteAddress }` → `201` DRAFT. `siteAddress` MUST end with a 6-digit postal code or `400 validation_failed` |
| `GET` | `/api/rentalPlans` | Caller's plans only |
| `GET` | `/api/rentalPlans/{id}` | Owner or `404` |
| `POST` | `/api/rentalPlans/{id}/items` | `{ assetId }`. On `QUOTED`, succeeds and reverts to `DRAFT` (clears `totalAmount`) |
| `DELETE` | `/api/rentalPlans/{id}/items/{itemId}` | Same revert-to-`DRAFT` on `QUOTED` |
| `POST` | `/api/rentalPlans/{id}/quote` | Spring-only sum; sets `QUOTED` and refreshes `updatedAt`. Re-quote of `QUOTED` allowed; `CONVERTED` → `409` |
| `POST` | `/api/rentalPlans/{id}/cancel` | Sets `CANCELLED`, clears `totalAmount`, refreshes `updatedAt`. Allowed from `DRAFT`/`SAVED`/`QUOTED`. `CONVERTED` → `409 already_converted`; already `CANCELLED` → `409 already_cancelled` |

Day count: inclusive `ChronoUnit.DAYS.between(start, end) + 1` (as-built convention).

## Pricing note

Today: `DefaultPricingClient` — `baseDailyRate × days`.  
Future: haystack `POST /internal/v1/pricing/quote` behind `PricingClient` — **not built**; open deposit_rate SoT conflict with booking `DEPOSIT_RATE`.

Responses include `createdAt` / `updatedAt` (ISO-8601 local date-time). `updatedAt` is last-quoted-at when status is `QUOTED`.

Plan-backed checkout: [`checkout.md`](./checkout.md) and [`../../booking-delivery-return/`](../../booking-delivery-return/).

## Related

- [`../../spring-proxy-endpoints/spec.md`](../../spring-proxy-endpoints/spec.md)  
- [`../../equipment-browse/`](../../equipment-browse/)
