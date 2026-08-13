# Entity & Repository Model — Source of Truth

## Purpose

Describe the as-built JPA data model (entities, relationships, enums, repositories) so feature work reuses correct field names and does not invent collection navigation or cascades that do not exist.

**Status:** **As-built**  
**Field catalog:** [`contracts/entity-catalog.md`](./contracts/entity-catalog.md)  
**Not a REST contract** — endpoints live in feature capabilities / remaining SPECs.

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

Schema management MUST use Hibernate `spring.jpa.hibernate.ddl-auto=update` (as-built in `application.properties`) against PostgreSQL. Introducing Flyway/Liquibase requires an explicit OpenSpec change and constitution update.

#### Scenario: App starts against Postgres
- GIVEN reachable PostgreSQL and `ddl-auto=update`
- WHEN the application context starts
- THEN Hibernate updates tables from entity annotations without requiring Flyway

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
