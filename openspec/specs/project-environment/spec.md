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

### Requirement: FR-ENV-005 Schema via Flyway in production only

The default profile MUST disable Flyway (`spring.flyway.enabled=false`) and use Hibernate `spring.jpa.hibernate.ddl-auto=update`. The `prod` profile (Release image `SPRING_PROFILES_ACTIVE=prod`) MUST enable Flyway against `src/main/resources/db/migration` and set Hibernate `ddl-auto=validate`. Existing production databases that already have the JPA schema MAY be baselined (`spring.flyway.baseline-on-migrate=true`) so V1 is not re-applied.

#### Scenario: Local boot does not run Flyway
- GIVEN the default profile
- WHEN the application context starts
- THEN Hibernate updates schema without Flyway

#### Scenario: Prod empty Postgres is created by Flyway
- GIVEN `SPRING_PROFILES_ACTIVE=prod` and reachable PostgreSQL with no application tables
- WHEN the application context starts
- THEN Flyway applies `V1__baseline_jpa_schema.sql` (and later versions)
- AND Hibernate validates the schema against entity mappings

### Requirement: FR-ENV-006 OpenSpec-primary process

New feature contracts MUST be added under `openspec/specs/` or `openspec/changes/` only. OpenSPDD canvases remain under `spdd/prompt/` for generation only. Living contracts MUST NOT be authored under `specification/`.

#### Scenario: New capability
- GIVEN a new product capability
- WHEN documentation is authored
- THEN an OpenSpec domain or change folder is created

### Requirement: FR-ENV-007 Request-body Bean Validation

Write DTOs that carry format rules (for example `siteAddress` postal code) MUST use Jakarta Bean Validation (`@NotBlank`, `@Pattern`) on the request record and `@Valid` on the controller parameter. `MethodArgumentNotValidException` MUST map to HTTP `400` with `error` = `validation_failed` and the shared `{ "error", "message" }` body. Entity columns MUST NOT be the enforcement point for those format rules.

## Stack (normative summary)

| Layer | Choice |
|-------|--------|
| Language | Java 21 |
| Framework | Spring Boot 4.1 |
| Packaging | WAR |
| Security | Spring Security + OAuth2 Resource Server JWT |
| Persistence | Spring Data JPA + PostgreSQL + Flyway |
| HTTP client (S2b) | RestClient + Resilience4j |
| Port | 8080 |

## Related

- [`../../project.md`](../../project.md)  
- Auth: [`../auth-interim-token/`](../auth-interim-token/), [`../auth-login-logout/`](../auth-login-logout/)  
- Seed: [`../seed-data/`](../seed-data/)  
- Testing: [`../testing/`](../testing/)
