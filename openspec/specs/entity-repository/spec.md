# Entity & Repository Model — Source of Truth

## Purpose

Describe the as-built JPA data model (entities, relationships, enums, repositories) so feature work reuses correct field names and does not invent collection navigation or cascades that do not exist.

**Status:** **As-built**  
**Field catalog:** [`contracts/entity-catalog.md`](./contracts/entity-catalog.md)  
**Not a REST contract** — endpoints live in feature capabilities under `openspec/specs/`.

## Requirements

### Requirement: FR-DATA-001 Shared entity conventions

Every entity MUST use `Long` identity PK, Lombok accessors, unidirectional `@ManyToOne(fetch = LAZY)` only (no `@OneToMany` / cascade / orphanRemoval on entities), `EnumType.STRING` for enums, `BigDecimal(10,2)` for money, and snake_case table names. Application code MUST set timestamps explicitly (no DB defaults assumed).

#### Scenario: Children via repository not graph
- GIVEN an `Asset` row with images
- WHEN code needs those images
- THEN it uses the child repository `findBy…` method
- AND does not assume `asset.getImages()` exists

### Requirement: FR-DATA-002 No cascading deletes in JPA model

The system MUST NOT configure JPA cascade/orphanRemoval on these associations. FK constraints are enforced at the database with default restrictive delete behavior.

#### Scenario: Delete parent without cascade config
- GIVEN a parent entity with child FK rows
- WHEN application deletes the parent without first handling children
- THEN the database enforces referential integrity (not silent JPA cascade)

### Requirement: FR-DATA-003 Schema lifecycle

The default profile MUST use Hibernate `spring.jpa.hibernate.ddl-auto=update` with Flyway disabled. Production (`SPRING_PROFILES_ACTIVE=prod`) MUST apply Flyway versioned SQL in `src/main/resources/db/migration` and then Hibernate `ddl-auto=validate`. Entity mapping changes that need a new production column, table, constraint, or type MUST add a new Flyway version.

#### Scenario: Default profile starts against Postgres
- GIVEN reachable PostgreSQL and the default profile
- WHEN the application context starts
- THEN Hibernate updates tables from entity annotations without Flyway

#### Scenario: Prod starts against empty Postgres
- GIVEN reachable PostgreSQL, no application tables, and profile `prod`
- WHEN the application context starts
- THEN Flyway creates tables from `V1__baseline_jpa_schema.sql`
- AND Hibernate validates those tables against entity annotations

### Requirement: FR-DATA-004 AIRecommendation holds S2b handles

`AIRecommendation` MUST support S2b fields: `ingest_id`, `haystack_user_id`, `idempotency_key`, `correlation_id`, optional budget/date/warnings, and optional Call 2 `confidenceScore`. Call 2 quote items are portal JSON only in S2b — writing `recommendation_items` is not required for submit.

#### Scenario: Saga persists ingest handle
- GIVEN successful Call 1 ingest
- WHEN the saga saves the session
- THEN `ingest_id` (and related handles) are stored on `AIRecommendation`

### Requirement: FR-DATA-005 Condition and status enums

Shared `ConditionType` and entity-nested status enums MUST persist as strings. Booking statuses include `PENDING_DEPOSIT`, `PENDING_CONFIRMED`, `CONFIRMED`, `MOBILISED`, `COMPLETED`, `CANCELLED`.

#### Scenario: Enum stored as name
- GIVEN an entity field with `@Enumerated(STRING)`
- WHEN the row is written
- THEN the database stores the enum constant name, not ordinal

## Out of scope

- Full REST/DTO contracts for each aggregate (feature capabilities)
- Bean Validation annotations on entities (not used as-built)
