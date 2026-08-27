# OpenSpec — agent / engineer reading order

## Always

1. [`project.md`](./project.md)  
2. [`specs/project-environment/spec.md`](./specs/project-environment/spec.md)  
3. [`specs/api-index/contracts/routes.md`](./specs/api-index/contracts/routes.md)  
4. Owning `specs/<capability>/`  
5. Active work under [`changes/`](./changes/) if present  

## By area

| Area | Capability |
|------|------------|
| Auth | `auth-interim-token` → `auth-login-logout` |
| Equipment / assets | `equipment-browse` (`/api/assets`) |
| Bookings / mobile | `booking-delivery-return` |
| Payments | `payments-stripe` |
| Rental plans | `rental-plan-quote` + `spring-proxy-endpoints` + as-built [`changes/dynamic-plan-quote-pricing/`](./changes/dynamic-plan-quote-pricing/) (OpenSPDD + ADR) |
| Postal / distance | `postal-code-validation` + rental-plan FR-RP-012 + as-built [`changes/pricing-postal-distance/`](./changes/pricing-postal-distance/) (OpenSPDD + ADR) |
| Admin users | `admin-users` |
| Admin asset records | `admin-portal` + `equipment-browse` |
| Admin overview | `monthly-utilization` |
| Recommender | `haystack-recommender` + portal contract + `../Feasibility_Study_Spring/` + FR-S2B-011 [`changes/2026-08-20-call2-quote-quantity-passthrough/`](./changes/2026-08-20-call2-quote-quantity-passthrough/) |
| Data / seed / tests | `entity-repository`, `seed-data`, `testing` |
| Estimate (not built) | `changes/pricing-estimate/` |
| Plan checkout | `rental-plan-quote` + `booking-delivery-return` (as-built); archive `changes/archive/2026-08-13-rental-plan-checkout-conversion/` |

## Haystack upstream (read-only)

https://github.com/Heavy-Rental/haystack-fast-api — Call 1/2/3 contracts under that repo’s `openspec/specs/`.

## OpenSPDD and ADR

[`../spdd/README.md`](../spdd/README.md) — REASONS canvases. Per-change `design.md` is the OpenSPDD canvas; `adr.md` is the locked decision record. Gold-standard pack: [`changes/2026-08-20-call2-quote-quantity-passthrough/`](./changes/2026-08-20-call2-quote-quantity-passthrough/).

## Archives

- S2b change + Spec-Kit pack: [`changes/archive/2026-08-12-s2b-resilient-haystack-client/`](./changes/archive/2026-08-12-s2b-resilient-haystack-client/)
- Plan checkout: [`changes/archive/2026-08-13-rental-plan-checkout-conversion/`](./changes/archive/2026-08-13-rental-plan-checkout-conversion/)
- Doc changelogs: [`changes/archive/2026-08-docs-changelog/`](./changes/archive/2026-08-docs-changelog/)
