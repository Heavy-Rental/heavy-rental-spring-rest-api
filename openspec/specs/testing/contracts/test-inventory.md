# Contract: Test class inventory

| Field | Value |
|-------|--------|
| **Capability** | testing |
## Auth / context (Postgres)

| Class | Asserts |
|-------|---------|
| `RestApiApplicationTests` | Context loads |
| `AuthenticationIntegrationTest` | Interim → login → access → logout; role gates; denylist |

## S2b haystack (WireMock / unit — no live FastAPI)

| Class | Asserts |
|-------|---------|
| `HaystackRecommenderClientTest` | Call 1/2/3 mapping; headers; 4xx/5xx |
| `HaystackRetryIdempotencyTest` | Same `Idempotency-Key` on 5xx retry |
| `HaystackTimeoutRetryTest` | Timeout → retry same key; `recommender_timeout` |
| `HaystackCircuitBreakerTest` | Open → fail-fast |
| `HaystackBulkheadTest` | Concurrency limit |
| `RecommenderSagaServiceTest` | Dual-hop quote; no re-ingest; Call 3 only on knowledge-query |
| `RecommenderSagaWireMockTest` | Real client dual-hop paths + correlation + quote |
| `RecommendationControllerIntegrationTest` | MockMvc + JWT + WireMock submit/session/query |

## Commands

```bash
cd heavy-rental-spring-rest-api
./mvnw test -Dtest=AuthenticationIntegrationTest
./mvnw -Dtest=HaystackRecommenderClientTest,HaystackRetryIdempotencyTest,HaystackTimeoutRetryTest,HaystackCircuitBreakerTest,HaystackBulkheadTest,RecommenderSagaServiceTest,RecommenderSagaWireMockTest,RecommendationControllerIntegrationTest test
./mvnw test
```

## Related SoT

- Auth: [`../../auth-interim-token/`](../../auth-interim-token/), [`../../auth-login-logout/`](../../auth-login-logout/)  
- Recommender: [`../../haystack-recommender/`](../../haystack-recommender/)
