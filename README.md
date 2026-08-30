# Heavy Rental Spring REST API

Authenticated backend for the Heavy Rental product: fleet catalog, rental plans, bookings, Stripe payments, ops delivery/return, and orchestration of the private Haystack AI recommender.

| Item | Value |
|------|--------|
| **Stack** | Java 21 · Spring Boot 4.1 · PostgreSQL · OAuth2 Resource Server JWT (HS256) |
| **Port** | `8080` |
| **Packaging** | WAR |
| **Behavior SoT** | OpenSpec [`openspec/specs/`](openspec/specs/) |
| **Overview** | [`DOCUMENTATION.md`](DOCUMENTATION.md) |

## Documentation standards

New behavior is specified with **all three**:

| Artifact | Where | Role |
|----------|--------|------|
| **OpenSpec** | `openspec/specs/<capability>/` (living) and `openspec/changes/<id>/` (deltas) | MUST/SHALL requirements and HTTP contracts |
| **OpenSPDD** | `openspec/changes/<id>/design.md` (REASONS canvas); living prompts in [`spdd/`](spdd/) | Generation / realignment prompt |
| **ADR** | `openspec/changes/<id>/adr.md` | Locked trade-off (Accepted/Rejected) |

Start at [`openspec/AGENTS.md`](openspec/AGENTS.md). Route map: [`openspec/specs/api-index/contracts/routes.md`](openspec/specs/api-index/contracts/routes.md).

## Quick start

```bash
export APP_JWT_SECRET='<at least 32 characters>'
export POSTGRES_PASSWORD='<postgres password>'
# optional: STRIPE_*, ONEMAP_*, APP_GOOGLE_WEB_CLIENT_ID, DYNAMIC_PRICING_ENABLED

cd heavy-rental-spring-rest-api
./mvnw spring-boot:run
```

Health: `curl -s http://localhost:8080/actuator/health`

```bash
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@localhost","password":"admin1234"}'
```

Tests: `./mvnw test` (Postgres required; Haystack and OneMap are WireMock'd).

### Stripe webhooks (local)

Testing checkout locally requires Stripe webhook events, or a successful deposit/balance payment will never update `Payment`/`Booking` status (HR-203). Prefer:

```sh
./scripts/dev-with-webhooks.sh
```

instead of `./mvnw spring-boot:run` alone. Isolated forwarder: `./scripts/dev-webhook-listen.sh`.

## Seed users (dev)

Full table: [`openspec/specs/seed-data/contracts/seed-summary.md`](openspec/specs/seed-data/contracts/seed-summary.md). Commonly used:

| Email | Password | Role |
|-------|----------|------|
| `admin@localhost` | `admin1234` | ADMIN |
| `alex.tan@example.sg` | `customer123` | USER (has an active QUOTED plan) |
| `ah.tan@example.sg` | `driver123` | DRIVER |
| `mei.lin@example.sg` | `customer456` | USER (no plans — cart walk) |

Also seeded: `ravi.kumar@example.sg` / `admin123` (ADMIN), `mei.ling@example.sg` / `customer234` (USER), `farid.rahman@example.sg` / `customer345` (USER).

## Related

- Integrator overview: [`DOCUMENTATION.md`](DOCUMENTATION.md)
- Postman: [`postman/`](postman/)
- Haystack wire notes: [`Feasibility_Study_Spring/`](Feasibility_Study_Spring/)
