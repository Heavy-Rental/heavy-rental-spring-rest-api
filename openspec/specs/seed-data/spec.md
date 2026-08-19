# Seed Data (`data.sql`) — Source of Truth

## Purpose

Define as-built local/dev seeding via `src/main/resources/data.sql`: order, idempotency, consistency rules, and representative volume for demos and utilization-style queries.

**Status:** **As-built**  
**Summary tables:** [`contracts/seed-summary.md`](./contracts/seed-summary.md)  
**Normative file:** `src/main/resources/data.sql`

## Requirements

### Requirement: FR-SEED-001 SQL init after schema

The system MUST load `data.sql` after Hibernate schema update on the default profile using `spring.jpa.defer-datasource-initialization=true` and `spring.sql.init.mode=always`. Seeding MUST NOT depend on a Java `ApplicationRunner` for the fleet catalog. Production MUST keep `spring.sql.init.mode=never`. Production MAY run `data.sql` after Flyway when Academy overlay `APP_SEED_DATA_SQL=true` (`app.seed.data-sql`); the default is `false`.

#### Scenario: Boot seeds tables
- GIVEN a reachable Postgres and empty or existing schema
- WHEN the application starts in the default profile
- THEN Hibernate updates schema (Flyway is disabled)
- AND `data.sql` runs after DDL and inserts/updates seed rows

#### Scenario: Academy deploy opts into seed
- GIVEN profile `prod` and `APP_SEED_DATA_SQL=true`
- WHEN the application starts
- THEN Flyway migrates first
- AND `data.sql` runs next (idempotent `ON CONFLICT`)

### Requirement: FR-SEED-002 FK dependency order

Seed inserts MUST follow FK-safe order: `users` → categories → assets → images → rental plans/records → bookings/items → payments → delivery/return → AI recommendations/items.

#### Scenario: Order prevents FK failures
- GIVEN a fresh schema
- WHEN `data.sql` executes
- THEN parent rows exist before child FKs reference them

### Requirement: FR-SEED-003 Idempotent re-run

Non-user tables MUST use `ON CONFLICT (id) DO NOTHING` (or equivalent) so re-running against the same Postgres instance does not fail. `users` MUST use `ON CONFLICT (id) DO UPDATE` so password hashes self-heal on boot.

#### Scenario: Second boot does not explode
- GIVEN seed already applied
- WHEN the app boots again and re-runs `data.sql`
- THEN inserts converge without unique-constraint failures

### Requirement: FR-SEED-004 Internal consistency

Seeded money and lifecycle data MUST be internally consistent: line subtotals align with rate × duration; booking totals match items; deposits follow the project deposit ratio; delivery/return rows exist for statuses that imply them.

#### Scenario: Booking totals reconcile
- GIVEN a seeded booking with items
- WHEN totals are inspected
- THEN booking total equals the sum of item subtotals (within seed design)

### Requirement: FR-SEED-005 Fleet scale for utilization

The seeded fleet MUST include multiple assets per category with real capacity/height bands and mixed conditions so utilization-style queries can return non-degenerate fractions (as-built: 27 assets, 91 bookings scale).

#### Scenario: Category has multi-asset depth
- GIVEN seeded assets for a category
- WHEN a date-window utilization query is considered
- THEN at least one spec-band has multiple assets (not only 0/1 triviality)

### Requirement: FR-SEED-006 Dev credentials documented

Seeded user plaintext passwords MAY be documented for local/dev only and MUST be stored as BCrypt hashes that verify through normal login. Production MUST NOT rely on these passwords.

#### Scenario: Seeded admin can login
- GIVEN interim token and seeded `admin@localhost` / documented password
- WHEN login is called
- THEN access token is issued (after `data.sql` has run)

## Out of scope

- Moving seed rows into Flyway repeatable migrations (schema only; `data.sql` remains the seed SoT)
