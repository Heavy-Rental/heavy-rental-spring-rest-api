# Proposal: Rental plan → booking checkout conversion

| Field | Value |
|-------|--------|
| **Change id** | `rental-plan-checkout-conversion` |
| **Status** | **Proposed / design only — not implemented** |
| **Date** | 2026-08-13 |
| **Routes** | Existing `POST /api/bookings`, `POST/DELETE /api/rentalPlans/{id}/items`, `POST /api/rentalPlans/{id}/quote` |

## Why

Cart → quote → checkout is incomplete on the as-built path:

1. `RentalPlan.PlanStatus.CONVERTED` is never written, so a customer who checks out stays locked by the one-active-plan rule (`DRAFT`/`SAVED`/`QUOTED`).
2. `POST /api/bookings` ignores the plan: it re-prices from the request body instead of the frozen quote.
3. Day count on direct booking (`DAYS.between`) disagrees with `DefaultPricingClient` (`DAYS.between + 1`).
4. No ownership, `QUOTED`, or 24-hour freshness check on `rentalPlanId`.
5. `RentalPlan.createdAt` / `updatedAt` are never stamped, so quote age cannot be computed.

## What changes

- **ADDED** plan-backed checkout: when `rentalPlanId` is present, derive items/dates/`totalAmount` from the owned `QUOTED` plan, re-check availability, persist the booking, set plan `CONVERTED`.
- **MODIFIED** item add/remove on `QUOTED`: succeed and revert to `DRAFT` (clear `totalAmount`) instead of `409` lock.
- **MODIFIED** quote/create: stamp `updatedAt` / `createdAt`; expose both on `RentalPlanResponse`.
- **MODIFIED** no-plan booking create: inclusive day count to match `DefaultPricingClient`.
- Distinct `409` codes `quote_not_ready` and `quote_expired` (not generic `conflict`).

## Out of scope

- `POST /api/pricing/estimate` — separate change [`../pricing-estimate/`](../pricing-estimate/); this pack treats single-item preview as client-side `baseDailyRate × (days + 1)`
- Haystack-backed quoting
- New list-filter for “active plan only”
- Implementation in this documentation change

## Related

- Living quote SoT: [`../../specs/rental-plan-quote/`](../../specs/rental-plan-quote/)
- Living booking SoT: [`../../specs/booking-delivery-return/`](../../specs/booking-delivery-return/)
- Frontend wire: [`contracts/portal-api.md`](./contracts/portal-api.md)
- Route index: [`../../specs/api-index/contracts/routes.md`](../../specs/api-index/contracts/routes.md)
