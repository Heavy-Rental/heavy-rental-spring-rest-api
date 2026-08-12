# Specification: Test Flow

| Field | Value |
|-------|--------|
| **Document type** | SDD test reference (as-built) |
| **Status** | Implemented (auth + S2b WireMock/unit) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related code** | `RestApiApplicationTests`; `AuthenticationIntegrationTest`; S2b: `HaystackRecommenderClientTest`, `HaystackRetryIdempotencyTest`, `HaystackCircuitBreakerTest`, `HaystackBulkheadTest`, `RecommenderSagaServiceTest` |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md), auth SPECs, [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md) |

---

## 1. Test classes

### 1.1 Auth / context (Postgres)

- **`RestApiApplicationTests`** — `@SpringBootTest` smoke test, `contextLoads()` only.
- **`AuthenticationIntegrationTest`** — `@SpringBootTest @AutoConfigureMockMvc`, interim-token → login → access-token → logout via MockMvc against real security filters. `@Transactional` rollback isolation.

### 1.2 S2b haystack recommender (as-built)

Contract: [`SPEC-haystack-recommender-client.md`](./SPEC-haystack-recommender-client.md) §11. **WireMock** / pure unit tests — no live FastAPI required.

| Class | Asserts |
|-------|---------|
| `HaystackRecommenderClientTest` | Happy path ingest / Call 2 recommend quote / Call 3 Q&A / health; headers; 4xx/5xx |
| `HaystackRetryIdempotencyTest` | Same `Idempotency-Key` on 5xx retry when ingest retry enabled |
| `HaystackTimeoutRetryTest` | Delay &gt; read timeout → retry same key; timeout maps to `recommender_timeout` |
| `HaystackCircuitBreakerTest` | N× 500 → open → fail-fast without further HTTP |
| `HaystackBulkheadTest` | Concurrent limit rejects when full |
| `RecommenderSagaServiceTest` | Dual-hop quote body (Mockito); Call 2 fail → **no** re-ingest; Call 3 knowledge-query only |
| `RecommenderSagaWireMockTest` | Real client dual-hop: WireMock Call 1+2 paths, shared `X-Correlation-Id`, `quoteRef`/`items`, no re-ingest |
| `RecommendationControllerIntegrationTest` | MockMvc + JWT + WireMock: JSON/multipart submit, GET session, knowledge-query, 401 |

```bash
./mvnw -Dtest=HaystackRecommenderClientTest,HaystackRetryIdempotencyTest,HaystackCircuitBreakerTest,HaystackBulkheadTest,RecommenderSagaServiceTest test
./mvnw test
```

## 2. Database target

Auth/context tests use the same Postgres as `spring-boot:run` (`POSTGRES_HOSTNAME`). S2b client/resilience/saga tests do **not** require Postgres (WireMock + Mockito).

## 3. Test isolation

`AuthenticationIntegrationTest` uses `@Transactional` so per-test users roll back.

## 4. `AuthenticationIntegrationTest` flow

Per-test setup mints a fresh user via `createUser()`. Coverage: interim JWT, login success/failure, role gates, logout denylist, protected path 401.

Helpers: `mintInterim()`, `loginAndGetAccessToken()`.

---

## 5. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.0.0 | (prior) | As-built: context smoke + `AuthenticationIntegrationTest` |
| 1.1.0 | 2026-08-12 | Planned S2b WireMock classes documented |
| 1.2.0 | 2026-08-12 | **S2b tests as-built** — five classes green; full `./mvnw test` green |
