# Contract: `/api/rentalPlans`

| Field | Value |
|-------|--------|
| **Capability** | rental-plan-quote |
| **Status** | As-built |

## Routes

| Method | Path | Notes |
|--------|------|--------|
| `POST` | `/api/rentalPlans` | Create `{ startDate, endDate, siteAddress? }` → `201` DRAFT. `siteAddress` is optional; WHEN PROVIDED MUST end with a 6-digit postal code or `400 bad_request` |
| `GET` | `/api/rentalPlans` | Caller's plans only |
| `GET` | `/api/rentalPlans/{id}` | Owner or `404` |
| `PATCH` | `/api/rentalPlans/{id}` | `{ siteAddress }` — set/correct address (FR-RP-011). On `QUOTED`, reverts to `DRAFT` and clears `totalAmount`. `CONVERTED` → `409 already_converted`; `CANCELLED` → `409 already_cancelled` |
| `POST` | `/api/rentalPlans/{id}/items` | `{ assetId }` → `201`. On `QUOTED`, succeeds and reverts to `DRAFT` (clears `totalAmount`) |
| `DELETE` | `/api/rentalPlans/{id}/items/{itemId}` | Same revert-to-`DRAFT` on `QUOTED` |
| `POST` | `/api/rentalPlans/{id}/quote` | Freezes totals; sets `QUOTED` and refreshes `updatedAt`. Re-quote of `QUOTED` allowed; `CONVERTED` → `409`. Optional inbound `X-Correlation-Id` is propagated to haystack when dynamic pricing is on (not a CORS-allowed browser header) |
| `POST` | `/api/rentalPlans/{id}/cancel` | Sets `CANCELLED`, clears `totalAmount`, refreshes `updatedAt`. Allowed from `DRAFT`/`SAVED`/`QUOTED`. `CONVERTED` → `409 already_converted`; already `CANCELLED` → `409 already_cancelled` |

Day count: inclusive `ChronoUnit.DAYS.between(start, end) + 1` (as-built convention; bookings use the same inclusive count).

## Pricing note

- **Add item:** `DefaultPricingClient` — `baseDailyRate × inclusive days` (always).
- **Quote, `pricing.dynamic-enabled=true` (module default):** `HaystackPricingClient` → haystack `POST /internal/v1/pricing/quote`, with per-item fallback to `DefaultPricingClient`. `distance_km` from `DistanceService` (OneMap haversine; fallback `pricing.default-distance-km`).
- **Quote, flag off:** sum of snapshotted line subtotals; no haystack hop.

Haystack `deposit_rate` on the pricing response is **not** applied to bookings; booking deposit remains `Booking.DEPOSIT_RATE = 0.30`.

Responses include `createdAt` / `updatedAt` (ISO-8601 local date-time). `updatedAt` is last-quoted-at when status is `QUOTED`.

Plan-backed checkout: [`checkout.md`](./checkout.md) and [`../../booking-delivery-return/`](../../booking-delivery-return/).

## Related

- [`../../spring-proxy-endpoints/spec.md`](../../spring-proxy-endpoints/spec.md)  
- [`../../postal-code-validation/`](../../postal-code-validation/)  
- [`../../equipment-browse/`](../../equipment-browse/)  
- As-built packs: [`../../../changes/dynamic-plan-quote-pricing/`](../../../changes/dynamic-plan-quote-pricing/) · [`../../../changes/pricing-postal-distance/`](../../../changes/pricing-postal-distance/)
