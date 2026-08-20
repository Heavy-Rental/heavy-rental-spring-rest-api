# OpenSpec — Heavy Rental Spring REST API

| Field | Value |
|-------|--------|
| **Module** | `heavy-rental-spring-rest-api` |
| **Base package** | `com.heavy_rental.rest_api` |
| **Stack** | Java 21 · Spring Boot 4.1 · PostgreSQL · OAuth2 Resource Server JWT |
| **Process** | **OpenSpec primary** — migration complete for living contracts |
| **OpenSPDD** | [`spdd/`](../spdd/) — REASONS canvases only |
| **Haystack notes** | [`Feasibility_Study_Spring/`](../Feasibility_Study_Spring/) |
| **Upstream (read-only)** | [Heavy-Rental/haystack-fast-api](https://github.com/Heavy-Rental/haystack-fast-api) |
| **Contracts** | `openspec/specs/` only — former `specification/` tree removed after migration |

## Purpose

Behavior SoT: `specs/<capability>/spec.md`.  
Proposed work: `changes/<id>/` with ADDED/MODIFIED/REMOVED deltas.  
HTTP tables: `specs/<capability>/contracts/`.

## Constitution

[`specs/project-environment/spec.md`](./specs/project-environment/spec.md)

| Topic | Rule |
|-------|------|
| DB | PostgreSQL only; no H2 default |
| Auth | JWT resource server; `{ "error", "message" }` |
| Layering | Thin controllers; no external HTTP from controllers |
| Schema | default: Hibernate `ddl-auto=update`; prod: Flyway + `validate` |
| Seed | `data.sql` after Hibernate DDL (dev only) |
| Process | New work → OpenSpec only |

## Living domains

| Domain | Capability |
|--------|------------|
| [`project-environment`](./specs/project-environment/spec.md) | Stack / process |
| [`api-index`](./specs/api-index/spec.md) | Route discovery |
| [`auth-interim-token`](./specs/auth-interim-token/spec.md) | Interim mint |
| [`auth-login-logout`](./specs/auth-login-logout/spec.md) | Login / logout |
| [`entity-repository`](./specs/entity-repository/spec.md) | JPA model |
| [`seed-data`](./specs/seed-data/spec.md) | Seed data |
| [`testing`](./specs/testing/spec.md) | Tests |
| [`equipment-browse`](./specs/equipment-browse/spec.md) | Equipment API |
| [`booking-delivery-return`](./specs/booking-delivery-return/spec.md) | Bookings / ops |
| [`payments-stripe`](./specs/payments-stripe/spec.md) | Stripe payments |
| [`rental-plan-quote`](./specs/rental-plan-quote/spec.md) | Rental plans + checkout |
| [`admin-users`](./specs/admin-users/spec.md) | Admin users |
| [`monthly-utilization`](./specs/monthly-utilization/spec.md) | Admin utilization |
| [`haystack-recommender`](./specs/haystack-recommender/spec.md) | S2b recommender |
| [`spring-proxy-endpoints`](./specs/spring-proxy-endpoints/spec.md) | Haystack hop map |

## Active changes

| Change | Status |
|--------|--------|
| [`changes/pricing-estimate/`](./changes/pricing-estimate/) | **Design only** — open availability decision; not implemented |
| [`changes/2026-08-20-call2-quote-quantity-passthrough/`](./changes/2026-08-20-call2-quote-quantity-passthrough/) | **As-built** — FR-S2B-011 Call 2 quantity pass-through (Haystack FR-P-013) |

## Archives

| Archive | Contents |
|---------|----------|
| [`changes/archive/2026-08-12-s2b-resilient-haystack-client/`](./changes/archive/2026-08-12-s2b-resilient-haystack-client/) | Completed S2b change |
| [`changes/archive/2026-08-13-rental-plan-checkout-conversion/`](./changes/archive/2026-08-13-rental-plan-checkout-conversion/) | Completed plan → booking checkout (now living SoT) |
| [`changes/archive/2026-08-docs-changelog/`](./changes/archive/2026-08-docs-changelog/) | Historical CHANGES-*.md |

## Conventions

1. RFC 2119 MUST/SHALL/SHOULD/MAY  
2. GIVEN / WHEN / THEN  
3. Observable behavior over class names  
4. Keep auth interim vs login/logout split  
5. OpenSPDD for generation only (`spdd/`)  
6. Never invent equipment/rates on recommender failure  

## Reading order

[`AGENTS.md`](./AGENTS.md)
