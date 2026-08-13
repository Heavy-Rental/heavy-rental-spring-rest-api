# Tasks: S2b Resilient Haystack Recommender Client

## 0. Documentation (S2b-0)

- [x] 0.1 OpenSpec proposal, design, delta specs, living SoT seed
- [x] 0.2 Spec-Kit feature pack
- [x] 0.3 SPDD REASONS canvas
- [x] 0.4 Living SPEC + cross-SPEC updates
- [x] 0.5 Implementation authorized 2026-08-12
- [x] 0.6 Realign to Feasibility v2 Call 1/2/3 (recommend vs Q&A)

## 1–4. Implementation

- [x] Client + timeouts + WireMock (Call 1 / Call 2 recommend / Call 3 query)
- [x] Resilience4j CB + bulkheads (ingest/recommend/qa) + retry-with-key + correlation
- [x] Saga + entity + portal REST (submit → quote; knowledge-query → answer)
- [x] Specs marked As-built; `./mvnw test` green
- [x] Plan §7 residual: timeout+same-key retry; dual-hop WireMock saga (paths + correlation + quote)

## Verification

```bash
cd heavy-rental-spring-rest-api
./mvnw test
```

**Key tests:** `HaystackRecommenderClientTest`, `HaystackTimeoutRetryTest`, `HaystackRetryIdempotencyTest`, resilience suite, `RecommenderSagaServiceTest`, `RecommenderSagaWireMockTest`.
