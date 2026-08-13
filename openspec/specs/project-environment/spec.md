# Project Environment — Source of Truth

## Purpose

Binding environment constraints for the Spring REST API module: stack, Postgres, JWT, layering, and forbidden drift. Narrative detail also lives in [`../../project.md`](../../project.md).

**Status:** **As-built**  
## Requirements

### Requirement: FR-ENV-001 PostgreSQL only

The application and default tests MUST use the existing PostgreSQL service (hostname `POSTGRES_HOSTNAME` defaulting to `db` / workspace `db-primary`). The system MUST NOT introduce H2/Derby/embedded DB as the default app or default test database, and MUST NOT reintroduce Docker Compose as the primary Postgres provisioning path for this workspace.

#### Scenario: Datasource points at Postgres
- GIVEN default `application.properties`
- WHEN the app starts
- THEN JDBC URL targets PostgreSQL via env-overridable host/port/db

### Requirement: FR-ENV-002 Stateless JWT security

The API MUST run STATELESS sessions, disable CSRF for the API, store passwords with BCrypt, and validate Bearer JWTs via OAuth2 Resource Server (HS256). Shared error JSON MUST be `{ "error", "message" }`.

#### Scenario: Protected route without token
- GIVEN a business API path
- WHEN called without a valid access JWT
- THEN the response is unauthorized/forbidden per security config

### Requirement: FR-ENV-003 Config via properties and env

Runtime settings MUST be overridable by environment variables for datasource and `app.jwt.*` (secret ≥ 32 chars, issuer, expiration minutes). Production MUST supply strong secrets.

#### Scenario: JWT secret from env
- GIVEN `APP_JWT_SECRET` set
- WHEN tokens are signed/verified
- THEN that secret is used

### Requirement: FR-ENV-004 Layering

Controllers MUST stay thin; services own business rules; external HTTP clients MUST NOT be called directly from controllers.

#### Scenario: Haystack only via client package
- GIVEN a portal recommendation request
- WHEN the controller handles it
- THEN orchestration goes service → `client.haystack`, not controller → RestClient

### Requirement: FR-ENV-005 Schema via Hibernate update

Schema management MUST use `spring.jpa.hibernate.ddl-auto=update` unless an explicit change introduces migrations and updates this capability + `project.md`.

#### Scenario: No Flyway required for boot
- GIVEN current constitution
- WHEN the module boots
- THEN Hibernate updates schema without Flyway

### Requirement: FR-ENV-006 OpenSpec-primary process

New feature contracts MUST be added under `openspec/specs/` or `openspec/changes/` only. OpenSPDD canvases remain under `spdd/prompt/` for generation only.

#### Scenario: New capability
- GIVEN a new product capability
- WHEN documentation is authored
- THEN an OpenSpec domain or change folder is created

## Stack (normative summary)

| Layer | Choice |
|-------|--------|
| Language | Java 21 |
| Framework | Spring Boot 4.1 |
| Packaging | WAR |
| Security | Spring Security + OAuth2 Resource Server JWT |
| Persistence | Spring Data JPA + PostgreSQL |
| HTTP client (S2b) | RestClient + Resilience4j |
| Port | 8080 |

## Related

- [`../../project.md`](../../project.md)  
- Auth: [`../auth-interim-token/`](../auth-interim-token/), [`../auth-login-logout/`](../auth-login-logout/)  
- Seed: [`../seed-data/`](../seed-data/)  
- Testing: [`../testing/`](../testing/)
