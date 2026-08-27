# Contract: REST route map

| Field | Value |
|-------|--------|
| **Capability** | api-index |
| **Status** | As-built map (branch notes simplified; verify code for local-only routes) |

Legend: **Shared** = web + mobile; **Web** / **Mobile** / **Admin** = primary client; roles are SecurityConfig intent.

## Auth

| Method | Path | Roles | Contract |
|--------|------|-------|----------|
| `GET` | `/api/auth/getBearerToken` | Public | [auth-interim-token](../../auth-interim-token/) |
| `POST` | `/api/auth/login` | `ROLE_INTERIM` | [auth-login-logout](../../auth-login-logout/) |
| `POST` | `/api/auth/google` | `ROLE_INTERIM` | [auth-login-logout](../../auth-login-logout/) — mobile-only; auto-provisions `ROLE_DRIVER` on first sign-in |
| `POST` | `/api/auth/logout` | `ROLE_USER`, `ROLE_ADMIN`, `ROLE_DRIVER` | [auth-login-logout](../../auth-login-logout/) |

## Bookings / deliveries / returns / payments

| Method | Path | Client | Roles | Contract |
|--------|------|--------|-------|----------|
| `POST` | `/api/bookings` | Shared | USER/ADMIN (caller = customer) | create + plan-backed checkout [booking-delivery-return](../../booking-delivery-return/) · [checkout](../../rental-plan-quote/contracts/checkout.md) |
| `GET` | `/api/bookings` | Mobile+ | USER (own only) / ADMIN / DRIVER | [booking-delivery-return](../../booking-delivery-return/) |
| `GET` | `/api/bookings/{id}` | Mobile+ | USER (own only, else `403`) / ADMIN / DRIVER | same |
| `PUT` | `/api/bookings/{id}` | Mobile+ | USER (own only, else `403`) / ADMIN / DRIVER | same |
| `GET` | `/api/deliveries` | Mobile | `ROLE_ADMIN`/`ROLE_DRIVER` only | same |
| `PATCH` | `/api/deliveries/{id}/status` | Mobile | `ROLE_ADMIN`/`ROLE_DRIVER` only | same |
| `GET` | `/api/returns` | Mobile | `ROLE_ADMIN`/`ROLE_DRIVER` only | same |
| `PATCH` | `/api/returns/{id}/status` | Mobile | `ROLE_ADMIN`/`ROLE_DRIVER` only | same |
| `POST` | `/api/payments/deposit-intent` | Shared | USER/ADMIN (owner/admin) | [payments-stripe](../../payments-stripe/) |
| `POST` | `/api/payments/full-payment-intent` | Shared | USER/ADMIN (owner/admin) | [payments-stripe](../../payments-stripe/) — GST-inclusive one-shot; skips balance scheduler |
| `POST` | `/api/payments/webhook` | Stripe | Public + signature | [payments-stripe](../../payments-stripe/) |

## Equipment / depots / rental plans

| Method | Path | Client | Roles | Contract |
|--------|------|--------|-------|----------|
| `GET` | `/api/assets` | Web | USER/ADMIN | [equipment-browse](../../equipment-browse/) |
| `GET` | `/api/assets/{id}` | Web | USER/ADMIN | same |
| `POST` | `/api/assets` | Web | `ROLE_ADMIN` only | same |
| `PUT`/`PATCH`/`DELETE` | `/api/assets/{id}` | Web | `ROLE_ADMIN` only | same |
| `PUT` | `/api/assets/{id}/image` | Web | `ROLE_ADMIN` only | same — photo upload |
| `GET` | `/api/depots` | Web | USER/ADMIN | Stub `[]` (no Depot entity) |
| `POST/GET` | `/api/rentalPlans` | Web | USER/ADMIN | [rental-plan-quote](../../rental-plan-quote/) |
| `GET` | `/api/rentalPlans/{id}` | Web | owner | same |
| `POST` | `/api/rentalPlans/{id}/items` | Web | owner | same |
| `DELETE` | `/api/rentalPlans/{id}/items/{itemId}` | Web | owner | same |
| `PATCH` | `/api/rentalPlans/{id}` | Web | owner | same — set/correct `siteAddress` (FR-RP-011) |
| `POST` | `/api/rentalPlans/{id}/quote` | Web | owner | same — **flag-gated** haystack `POST /internal/v1/pricing/quote` (`pricing.dynamic-enabled`; module default **on**); Spring fallback per item |
| `POST` | `/api/rentalPlans/{id}/cancel` | Web | owner | same |
| `GET` | `/api/postalCodes/{postalCode}` | Web | USER/ADMIN | [postal-code-validation](../../postal-code-validation/) — OneMap, not Haystack |

## Recommender (S2b)

| Method | Path | Client | Roles | Contract |
|--------|------|--------|-------|----------|
| `POST` | `/api/recommendations/project-spec` | Web | USER/ADMIN | [haystack-recommender](../../haystack-recommender/) · [portal-api](../../haystack-recommender/contracts/portal-api.md) |
| `POST` | `/api/recommendations/{id}/knowledge-query` | Web | owner/admin | same |
| `GET` | `/api/recommendations/{id}` | Web | owner/admin | same |

## Admin

| Method | Path | Roles | Contract |
|--------|------|-------|----------|
| `GET/POST` | `/api/users` | `ROLE_ADMIN` only | [admin-users](../../admin-users/) |
| `GET/PATCH/DELETE` | `/api/users/{id}` | `ROLE_ADMIN` only | same |
| `GET` | `/api/monthly-utilization` | `ROLE_ADMIN` only | [monthly-utilization](../../monthly-utilization/) |

## Design-only / not built

| Method | Path | Notes |
|--------|------|--------|
| `POST` | `/api/pricing/estimate` | Design only — open availability decision; [`../../../changes/pricing-estimate/`](../../../changes/pricing-estimate/) |

## Haystack proxy map

[spring-proxy-endpoints](../../spring-proxy-endpoints/spec.md)
