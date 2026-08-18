# Design: S2b Resilient Haystack Recommender Client

| Field | Value |
|-------|--------|
| **Change** | `s2b-resilient-haystack-client` |
| **Date** | 2026-08-12 |
| **Status** | As-built |
| **HTTP client** | Spring **RestClient** |
| **Resilience** | Resilience4j (circuit breaker, bulkhead, retry) — programmatic |
| **Wire contract** | [`Feasibility_Study_Spring/wire-contract-call1-call2.md`](../../../Feasibility_Study_Spring/wire-contract-call1-call2.md) v2 |

## 1. Architecture

```text
Portal (Bearer JWT)
    │
    ▼
RecommendationController
    │
    ▼
RecommenderSagaService
    │  correlationId · idempotencyKey (per logical submit)
    │
    ├─ on POST /project-spec:
    │     1) ingest(...)                 // Call 1 submitprojectspecification
    │        → persist AIRecommendation (ingest_id, …)
    │     2) recommend(...)              // Call 2 getassetrecommendations
    │        → portal response includes quoteRef + items
    │        NEVER re-ingest if (2) fails
    │
    ├─ on POST /{id}/knowledge-query:
    │     queryProjectKnowledge(...)     // Call 3 project-knowledge/query
    │        → answer + sourcesUsed
    │
    └─ GET /{id}: DB session only
              │
              ▼
        haystack-fast-api
```

**Layering:** Controllers do not call haystack directly. Saga/service owns orchestration. Client owns HTTP + resilience decoration.

## 2. Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| HTTP client | RestClient | Blocking unary REST is correct for C1; starter already on classpath |
| Call 2 | Recommend / quote | Feasibility v2; primary portal submit body |
| Call 3 | Chatbot Q&A | Follow-up only; not required for submit |
| Persist aggregate | Extend `AIRecommendation` | Existing entity matches “AI session for a user” |
| Call 2 items | Portal JSON only | No `recommendation_items` write in S2b MVP |
| Call 3 answers | Response-only | No Q&A history table |
| Portal user identity | JWT → `CurrentUserService` | Never trust client-supplied haystack user_id |
| Prod ingest retry | Default **off** | Requires haystack S2a on target env |
| Schema | `ddl-auto=update` | Matches project practice |
| Multipart | Deferred | JSON text submit only in as-built |

## 3. Package layout

```text
com.heavy_rental.rest_api
├── client/haystack/
│   ├── HaystackProperties.java
│   ├── HaystackClientConfig.java
│   ├── HaystackRecommenderClient.java
│   ├── HaystackException.java
│   └── dto/  (ingest, GetAssetRecommendations*, ProjectKnowledgeQuery*, error)
├── controller/RecommendationController.java
├── service/RecommenderSagaService.java
└── dto/  (portal-facing records: Submit*, RecommendItem*, ProjectKnowledge*)
```

## 4. Haystack endpoints (normative)

| Op | Method | Path |
|----|--------|------|
| Health | GET | `/health` |
| Call 1 Ingest | POST | `/internal/v1/recommendations/submitprojectspecification` |
| Call 2 Recommend | POST | `/internal/v1/recommendations/project-knowledge/getassetrecommendations` |
| Call 3 Q&A | POST | `/internal/v1/recommendations/project-knowledge/query` |

**Do not use** legacy `/api/v1/recommendations/...` paths.

### Headers

| Header | When |
|--------|------|
| `Idempotency-Key` | Every Call 1; same UUID for retries of that logical submit |
| `X-Correlation-Id` | All calls |
| `traceparent` | Optional |

### Error body

```json
{"error": "<code>", "message": "<text>"}
```

## 5. Portal API

| Method | Path | Auth | Haystack |
|--------|------|------|----------|
| `POST` | `/api/recommendations/project-spec` | ROLE_USER, ROLE_ADMIN | Call 1 + Call 2 |
| `POST` | `/api/recommendations/{recommendationId}/knowledge-query` | Owner or admin | Call 3 |
| `GET` | `/api/recommendations/{recommendationId}` | Owner or admin | none |

### Error codes (portal)

| Condition | HTTP | `error` |
|-----------|------|---------|
| Validation | 400 | `bad_request` |
| Not found | 404 | `not_found` |
| Not owner | 403 | `forbidden` |
| CB open / bulkhead | 503 | `recommender_unavailable` |
| Timeout | 504 | `recommender_timeout` |
| Upstream 5xx after policy | 502/503 | `recommender_upstream_error` |

## 6. Persistence extensions (`ai_recommendations`)

| Field | Column | Notes |
|-------|--------|--------|
| `ingestId` | `ingest_id` | **Required** for Call 2 / Call 3 |
| `haystackUserId` | `haystack_user_id` | String identity sent to FastAPI |
| `idempotencyKey` | `idempotency_key` | Audit |
| `correlationId` | `correlation_id` | Audit |
| `tentativeStartDate` / `tentativeEndDate` | dates | Optional |
| budget / warnings | columns | Optional; never invent |
| `confidenceScore` | existing | May store Call 2 confidence when present |

## 7. Config sketch

```properties
haystack.timeouts.health-read=5s
haystack.timeouts.qa-read=45s
haystack.timeouts.recommend-read=90s
haystack.timeouts.ingest-read=180s
haystack.retry.ingest-enabled=false
```

Bulkheads: `haystackIngest`, `haystackRecommend`, `haystackQa`. Circuit breaker: `haystack`.
