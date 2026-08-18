# Testing — Source of Truth

## Purpose

Document as-built automated test inventory, DB targets, and isolation rules so CI and local runs stay consistent.

**Status:** **As-built**  
**Inventory:** [`contracts/test-inventory.md`](./contracts/test-inventory.md)

## Requirements

### Requirement: FR-TEST-001 Postgres for Spring context tests

`@SpringBootTest` / MockMvc tests that load the full context MUST use the project PostgreSQL (same host defaults as runtime). The default suite MUST NOT require H2.

#### Scenario: Auth integration uses Postgres
- GIVEN reachable Postgres
- WHEN `AuthenticationIntegrationTest` runs
- THEN security filters and user rows operate against Postgres

### Requirement: FR-TEST-002 S2b tests do not require live FastAPI

Haystack client, resilience, and saga tests MUST use WireMock and/or Mockito so default CI does not need a live haystack process.

#### Scenario: WireMock dual-hop
- GIVEN WireMock stubs for Call 1 and Call 2
- WHEN saga/controller tests run
- THEN no real FastAPI connection is required

### Requirement: FR-TEST-003 Transactional isolation for auth IT

`AuthenticationIntegrationTest` MUST use `@Transactional` (or equivalent) so per-test users roll back and do not pollute subsequent tests beyond seed policy.

#### Scenario: Per-test user cleanup
- GIVEN createUser in a test method
- WHEN the test completes
- THEN transactional rollback restores isolation

### Requirement: FR-TEST-004 Auth flow coverage

Auth integration tests MUST cover interim mint claims, login success/failure, role gates, interim single-use, logout denylist, and protected-path 401 without token.

#### Scenario: Full auth path
- GIVEN helpers `mintInterim()` and `loginAndGetAccessToken()`
- WHEN the integration suite runs
- THEN interim → login → access → logout behaviors are asserted

### Requirement: FR-TEST-005 S2b coverage set

The suite MUST include client mapping, retry idempotency, timeout retry, circuit breaker, bulkhead, saga unit, saga WireMock dual-hop, and recommendation controller MockMvc+JWT+WireMock tests (see inventory).

#### Scenario: Full mvn test green without haystack
- GIVEN Postgres available and no FastAPI
- WHEN `./mvnw test` runs
- THEN S2b and auth tests can pass under as-built design

## Out of scope

- Manual joint Spring+live-haystack tests (optional ops)
- Frontend e2e
