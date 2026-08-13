# Proposal: Spring-only pricing estimate

| Field | Value |
|-------|--------|
| **Change id** | `pricing-estimate` |
| **Status** | **Proposed / design only — not implemented** |
| **Date** | 2026-08-13 |
| **Route** | `POST /api/pricing/estimate` |

## Why

The web portal needs a side-effect-free “what would this cost?” estimate for assets + dates **without** creating a `RentalPlan` or locking quote state. This is **not** a haystack proxy and **not** a resurrection of a phantom estimate-proxy row removed from the old API index.

Distinct from `POST /api/rentalPlans/{id}/quote` (requires owned plan, locks to `QUOTED`).

## What changes

- **ADDED** capability behavior for pure Spring arithmetic estimate (see delta spec).
- Reuse `PricingClient.priceItem` day/rate math (same as rental-plan-quote).
- Deliberately **not** a haystack hop (aligns with spring-proxy-endpoints).

## Open decision (blocks implementation)

Should estimate run the same availability conflict check as `POST /api/bookings` (`409` on overlap), or stay purely arithmetic like rental-plan quote (no availability hold/check)?

Recorded unresolved so it is not decided by omission.

## Out of scope

- Persistence, holds, discounts, haystack dynamic pricing  
- Changing rental-plan quote behavior  

## Related

- Living rental plan pricing: [`../../specs/rental-plan-quote/`](../../specs/rental-plan-quote/)  
- Proxy map: [`../../specs/spring-proxy-endpoints/`](../../specs/spring-proxy-endpoints/)  
- Route index: [`../../specs/api-index/contracts/routes.md`](../../specs/api-index/contracts/routes.md)  
