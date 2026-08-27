# Contract: Test class inventory

| Field | Value |
|-------|--------|
| **Capability** | testing |
## Auth / context (Postgres)

| Class | Asserts |
|-------|---------|
| `RestApiApplicationTests` | Context loads |
| `AuthenticationIntegrationTest` | Interim → login → access → logout; role gates; denylist |
| `AuthServiceTest` | Login/logout/Google provisioning |

## S2b haystack (WireMock / unit — no live FastAPI)

| Class | Asserts |
|-------|---------|
| `HaystackRecommenderClientTest` | Call 1/2/3 mapping; headers; 4xx/5xx; FR-S2B-011 collapsed `quantity: 3` from realistic Call 2 JSON |
| `HaystackRetryIdempotencyTest` | Same `Idempotency-Key` on 5xx retry |
| `HaystackTimeoutRetryTest` | Timeout → retry same key; `recommender_timeout` |
| `HaystackCircuitBreakerTest` | Open → fail-fast |
| `HaystackBulkheadTest` | Concurrency limit |
| `RecommenderSagaServiceTest` | Dual-hop quote; nested equipment; catalog `img` by numeric id; no re-ingest; Call 3 only on knowledge-query; FR-S2B-011 quantities 1/1/3/1 |
| `RecommenderSagaWireMockTest` | Real client dual-hop paths + correlation + quote |
| `RecommendationControllerIntegrationTest` | MockMvc + JWT + WireMock submit/session/query; omit-null `platformHeight`; catalog `img` data URI; collapsed `quantity: 3` |

## Dynamic pricing / OneMap / plans (WireMock / unit — no live FastAPI or OneMap)

| Class | Asserts |
|-------|---------|
| `HaystackPricingClientTest` | `/internal/v1/pricing/quote` happy path, per-item error, circuit open, 4xx |
| `DynamicPricingServiceTest` | Flag off unchanged; flag on + success; whole-batch and per-item fallback; `distance_km` from `DistanceService` |
| `DistanceServiceTest` | Haversine; kill-switch; missing postal; OneMap empty/exception → default km |
| `OneMapClientTest` / `OneMapCircuitBreakerTest` / `OneMapAuthServiceTest` | Geocode mapping, cache, CB, token refresh |
| `PostalCodeUtilTest` | Well-formed / trailing-6 extract |
| `PostalCodeControllerIntegrationTest` | Unauthenticated 401; VALID/INVALID 200; malformed 400; OneMap-down 503 |
| `RentalPlanServiceTest` / `RentalPlanControllerIntegrationTest` | Create (optional address), items, quote, cancel, PATCH site address, ownership 404 |

## Bookings / payments / assets / ops

| Class | Asserts |
|-------|---------|
| `BookingServiceTest` | Direct create + plan-backed checkout; overlap 409; inclusive days |
| `BookingOpsAccessIntegrationTest` | USER own-only; ADMIN/DRIVER see all; deliveries/returns ADMIN/DRIVER |
| `PaymentServiceTest` / `PaymentWebhookServiceTest` / `BalanceChargeSchedulerServiceTest` | Deposit, full-payment GST, webhook, scheduler |
| `AssetAdminIntegrationTest` | Admin-only writes, serialno/timestamp, photo upload, duplicate name 409 |
| `MonthlyUtilizationAccuracyTest` | Inclusive overlap day counts |
| `ReturnServiceTest` / `BookingMapperTest` / `CurrentUserServiceTest` | Ops mapping / ownership helpers |

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
