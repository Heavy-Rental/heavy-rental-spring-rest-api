# Specification: Haystack Recommender Client (S2b)

| Field | Value |
|-------|--------|
| **Feature** | Resilient Spring → haystack-fast-api client, saga, portal REST (Call 1 ingest + Call 2 recommend + Call 3 Q&A) |
| **Status** | **As-built** (S2b runtime + WireMock/unit tests; Call 2 = recommend quote; Call 3 = chatbot) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Stage** | Phase 2 / **S2b** (Resilience C1 — Spring half) |
| **Endpoints (portal)** | `POST /api/recommendations/project-spec`, `POST /api/recommendations/{recommendationId}/knowledge-query`, `GET /api/recommendations/{recommendationId}` |
| **Upstream (haystack)** | `GET /health`, Call 1 `.../submitprojectspecification`, Call 2 `.../getassetrecommendations`, Call 3 `.../project-knowledge/query` |
| **Depends on** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) · auth JWT · `AIRecommendation` · haystack **S2a** for production ingest retry |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) · [`SPEC-api-index.md`](./SPEC-api-index.md) §2.6 |
| **Related code** | `client.haystack.*` (`HaystackRecommenderClient`, `HaystackProperties`, `HaystackClientConfig`), `RecommenderSagaService`, `RecommendationController`, extended `AIRecommendation`, `RestExceptionHandler` (recommender_* codes) |
| **Standards pack** | OpenSpec [`openspec/changes/s2b-resilient-haystack-client/`](../openspec/changes/s2b-resilient-haystack-client/) · Spec-Kit [`features/s2b-haystack-recommender-client/`](./features/s2b-haystack-recommender-client/) · SPDD [`spdd/prompt/S2b-resilient-haystack-recommender-client.md`](../spdd/prompt/S2b-resilient-haystack-recommender-client.md) |
| **Feasibility** | [`Feasibility_Study_Spring/phase2-s2b-spring-implementation-plan.md`](../Feasibility_Study_Spring/phase2-s2b-spring-implementation-plan.md) · wire · Call 1 consumer · S2a dependency |

This document is the **living Spring feature contract** for S2b. Behavioural requirements also live in OpenSpec; this file is the repo-native SDD contract (same role as `SPEC-stripe.md`).

---

## 1. Outcomes

When this feature is correct:

1. An authenticated user can submit project text and receive a stored recommendation session plus **Call 2 recommend quote** (`quoteRef`, `items`, …).
2. The same user can run **Call 3 chatbot Q&A** against that session without triggering a second ingest if Q&A fails.
3. Outbound haystack calls use per-operation timeouts (health / recommend / qa / ingest), circuit breaker, bulkheads, and (when enabled) limited retries that **reuse** the same `Idempotency-Key` on ingest.
4. Every haystack call carries `X-Correlation-Id`.
5. When the recommender is unavailable (CB open, bulkhead, timeout, upstream errors after policy), the API fails with clear `recommender_*` error codes and **never invents** equipment or prices.
6. Default CI proves the above with WireMock — no live FastAPI required.

---

## 2. Process flows

### 2.1 Portal submit project-spec (Call 1 **then** Call 2 recommend)

React submits once; Spring orchestrates **both** haystack calls before responding. The body returned to React is primarily the **Call 2 quote**.

```text
Client                         API                              haystack-fast-api
  │  POST /api/recommendations/project-spec
  │  Bearer access JWT
  │  { projectText, startDate?, endDate?, userName?, query?, topK? }
  │─────────────────────────────►│
  │                               │  resolve User from JWT
  │                               │  mint Idempotency-Key + Correlation-Id
  │                               │
  │                               │  1) POST .../submitprojectspecification
  │                               │     headers: Idempotency-Key, X-Correlation-Id ─►│
  │                               │◄── 200 lean FR-IX-023 (ingest_id, summary, …) ──│
  │                               │  persist AIRecommendation (ingest_id, …)
  │                               │
  │                               │  2) POST .../project-knowledge/getassetrecommendations
  │                               │     body: user_id, ingest_id, query? ────────────►│
  │                               │◄── 200 quoteRef, items[], rates, … ─────────────│
  │◄── { recommendationId, ingestId, summary…, quoteRef, items, … } ─│
  │                               │  on Call 2 5xx: DO NOT re-ingest; session kept
```

Optional Call 2 focus `query` priority: portal `query` → Call 1 `user_requirement_summary` → fixed default  
(`"Summarize equipment needs and recommend suitable assets for this project specification."`).

### 2.2 Follow-up Call 3 — chatbot Q&A

```text
Client                         API                              haystack
  │  POST /api/recommendations/{id}/knowledge-query
  │  { query, topK? }
  │─────────────────────────────►│
  │                               │  load AIRecommendation; ownership check
  │                               │  POST .../project-knowledge/query
  │                               │  body: user_id, ingest_id, query ───────────►│
  │                               │◄── 200 answer + sources ─────────────────────│
  │◄── { answer, sourcesUsed, … } ─│
  │                               │  on 5xx: surface error; DO NOT re-ingest
```

### 2.3 Circuit open / bulkhead

```text
  │  POST project-spec
  │─────────────────────────────►│  CB open or bulkhead full
  │◄── 503 { error: recommender_unavailable, message: … } ─│
  │     (no fabricated equipment)
```

---

## 3. Scope

### 3.1 In scope

- RestClient haystack client: health, Call 1 (JSON), Call 2 recommend, Call 3 Q&A
- Resilience4j circuit breaker, bulkheads (ingest / recommend / qa), limited retry
- Headers: `Idempotency-Key`, `X-Correlation-Id`, optional `traceparent`
- Saga: ingest → persist → recommend; no re-ingest on Call 2/3 failure
- Extend `AIRecommendation` for haystack handles
- Portal REST (three routes above)
- WireMock test suite
- Config + ops runbook notes

### 3.2 Out of scope

- FastAPI S2a implementation (as-built elsewhere)
- 202 + poll/SSE (C2), gRPC/queues (C3)
- C/W/D multi-agent roles in Spring
- Multipart project-file upload (JSON text only)
- Writing `recommendation_items` from Call 2
- Persisting Q&A answer history
- Flyway migrations

---

## 4. Requirements (summary)

Full scenarios: OpenSpec [`openspec/specs/haystack-recommender/spec.md`](../openspec/specs/haystack-recommender/spec.md).

| ID | Summary |
|----|---------|
| FR-S2B-001 | RestClient + per-op timeouts (health / recommend / qa / ingest) |
| FR-S2B-002 | Circuit breaker + bulkheads |
| FR-S2B-003 | Idempotent ingest retries (same key); prod retry default off |
| FR-S2B-004 | Correlation headers on all haystack calls |
| FR-S2B-005 | Saga Call 1→2; no re-ingest on Call 2 fail |
| FR-S2B-006 | Map FastAPI `{error,message}` |
| FR-S2B-007 | Portal REST; submit → quote; knowledge-query → Call 3 answer |
| FR-S2B-008 | Fail-safe; never invent equipment |
| FR-S2B-009 | WireMock suite for CI |

### 4.1 BDD sketches (normative examples)

```text
Scenario: Saga does not re-ingest on Call 2 recommend failure
  Given ingest succeeded and ingest_id was persisted
  When  Call 2 recommend returns HTTP 500
  Then  the saga surfaces a retryable recommend error
  And   WireMock records exactly one ingest request

Scenario: Portal project-spec dual-hop returns Call 2 quote
  Given React posts to POST /api/recommendations/project-spec
  When  the saga completes successfully
  Then  WireMock records one submitprojectspecification and one getassetrecommendations
  And   the HTTP response to React includes quoteRef / items
  And   Call 2 request includes user_id and ingest_id from Call 1

Scenario: Ingest retry reuses Idempotency-Key
  Given ingest times out once and ingest retry is enabled
  When  the client retries
  Then  both attempts send the same Idempotency-Key header
```

---

## 5. Portal API contract

Auth: access JWT with `ROLE_USER` or `ROLE_ADMIN` (existing blanket security). Ownership: session `user` must match JWT principal unless admin.

### 5.1 `POST /api/recommendations/project-spec`

Orchestrates **Call 1 then Call 2 recommend**. Success response includes Call 2 quote fields after a successful ingest + recommend.

**Request (JSON)**

| Field | Type | Required | Notes |
|-------|------|----------|--------|
| `projectText` | string | yes | Non-empty project specification text |
| `startDate` / `endDate` | date | no | `YYYY-MM-DD` |
| `userName` | string | no | Display/audit only |
| `query` | string | no | Optional Call 2 focus; else summary then default |
| `topK` | int | no | Passed to Call 2 |

**Also:** `multipart/form-data` with form fields `projectText`, `startDate`, `endDate`, `userName`, `query`, `topK`, and optional part `file`. At least one of `file` or non-blank `projectText` is required. Max size: `haystack.max-in-memory-size` / `spring.servlet.multipart.max-*-size` (default 20MB; align gateway + FastAPI).

**Response `200`**

| Field | Type | Notes |
|-------|------|--------|
| `recommendationId` | long | Spring PK |
| `ingestId` | string | From Call 1; stored for Call 2/3 |
| `userRequirementSummary` | string | From Call 1 |
| `tentativeStartDate` / `tentativeEndDate` | date \| null | |
| `needsSummary` | array | Optional display; not fleet recs |
| `expectedBudget` | object \| null | Never invent client-side |
| `warnings` | string[] | Call 1 + Call 2 soft issues merged |
| `correlationId` | string | Echo for log join |
| `quoteRef` | string | **From Call 2** |
| `confidenceScore` | number \| null | **From Call 2** |
| `days` | int \| null | **From Call 2** |
| `estimatedTotal` | number \| null | **From Call 2** |
| `specSummary` | string \| null | **From Call 2** |
| `rationale` | string \| null | **From Call 2** |
| `items` | array | Ranked quote lines: `rankOrder`, `equipmentId`, rates, … |

**Not on submit response:** Call 3 `answer` / `sourcesUsed` (use knowledge-query).

### 5.2 `POST /api/recommendations/{recommendationId}/knowledge-query`

**Call 3 only.**  
**Request:** `{ "query": string, "topK": int? }`  
**Response `200`:** `{ "answer": string, "sourcesUsed": string[]? }`

### 5.3 `GET /api/recommendations/{recommendationId}`

Returns stored session summary (ids, summary, dates, budget, warnings, status) — not a live haystack call.

### 5.4 Error mapping

| Condition | HTTP | `error` |
|-----------|------|---------|
| Validation | 400 | `bad_request` |
| Not found | 404 | `not_found` |
| Not owner | 403 | `forbidden` |
| Haystack 4xx | 400/422 | map FastAPI `error` when present |
| CB open / bulkhead | 503 | `recommender_unavailable` |
| Timeout | 504 | `recommender_timeout` |
| Upstream 5xx after policy | 502/503 | `recommender_upstream_error` |

Body shape (shared):

```json
{ "error": "<code>", "message": "<human-readable reason>" }
```

---

## 6. Haystack wire (summary)

Normative detail: [`Feasibility_Study_Spring/wire-contract-call1-call2.md`](../Feasibility_Study_Spring/wire-contract-call1-call2.md).

| Op | Method | Path |
|----|--------|------|
| Health | GET | `/health` |
| Call 1 Ingest | POST | `/internal/v1/recommendations/submitprojectspecification` |
| Call 2 Recommend | POST | `/internal/v1/recommendations/project-knowledge/getassetrecommendations` |
| Call 3 Q&A | POST | `/internal/v1/recommendations/project-knowledge/query` |

**Do not use** outdated `/api/v1/recommendations/...` paths.

**Call 1 success body (lean):** see [`call1-ingest-response-for-spring.md`](../Feasibility_Study_Spring/call1-ingest-response-for-spring.md) — must persist `ingest_id` and `user_id`.

**Call 2 behaviour:** recommend / quote (`quoteRef`, `items[]`) — **not** chatbot Q&A.

**Call 3 behaviour:** chatbot markdown `answer` + evidence fields.

---

## 7. Configuration

```properties
haystack.base-url=${HAYSTACK_BASE_URL:http://localhost:8000}
haystack.timeouts.connect=${HAYSTACK_CONNECT_TIMEOUT:5s}
haystack.timeouts.health-read=${HAYSTACK_HEALTH_READ_TIMEOUT:5s}
haystack.timeouts.qa-read=${HAYSTACK_QA_READ_TIMEOUT:45s}
haystack.timeouts.recommend-read=${HAYSTACK_RECOMMEND_READ_TIMEOUT:90s}
haystack.timeouts.ingest-read=${HAYSTACK_INGEST_READ_TIMEOUT:180s}
haystack.max-in-memory-size=${HAYSTACK_MAX_IN_MEMORY_SIZE:20MB}
haystack.retry.ingest-enabled=${HAYSTACK_INGEST_RETRY_ENABLED:false}
haystack.retry.ingest-max-attempts=${HAYSTACK_INGEST_RETRY_MAX:2}
haystack.retry.recommend-max-attempts=${HAYSTACK_RECOMMEND_RETRY_MAX:2}
haystack.retry.qa-max-attempts=${HAYSTACK_QA_RETRY_MAX:2}
```

Resilience4j instance names: CB `haystack`; bulkheads `haystackIngest`, `haystackRecommend`, `haystackQa`. Test profile: short timeouts, low CB minimum calls, bulkhead 1–2.

---

## 8. Persistence

Extend `AIRecommendation` / `ai_recommendations` (see also [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) §5.12):

| Field | Column | Spring must |
|-------|--------|-------------|
| `ingestId` | `ingest_id` | **Persist** — Call 2 / Call 3 handle |
| `haystackUserId` | `haystack_user_id` | **Persist** / match Call 2/3 |
| `idempotencyKey` | `idempotency_key` | Audit |
| `correlationId` | `correlation_id` | Audit |
| `tentativeStartDate` / `tentativeEndDate` | dates | Optional |
| budget amount/currency/source | columns | Optional; never invent |
| `warnings` | TEXT/JSON | Optional |
| `confidenceScore` | existing | May store Call 2 score |

Reuse: `user`, `rawProjectPrompt`, `aiReasoningSummary` ← Call 1 `user_requirement_summary`, `status=GENERATED`, `createdAt`.

**Call 2 items:** mapped to portal JSON only in S2b (no required `recommendation_items` write).  
**Call 3 answers:** returned in API response only.

Schema: Hibernate `ddl-auto=update` (no Flyway in this feature).

---

## 9. Resilience rules (hard)

1. One `Idempotency-Key` per logical portal submit; **reuse** on ingest timeout/5xx retry; never rotate mid-retry.
2. **4xx** → do not retry as success path.
3. **Call 2 or Call 3 failure ≠ re-ingest**.
4. CB open / bulkhead → `recommender_unavailable`; never invent assets/prices.
5. Prefer separate bulkheads for ingest vs recommend vs Q&A.
6. Production ingest retry **off** until S2a available ([`s2a-haystack-dependency.md`](../Feasibility_Study_Spring/s2a-haystack-dependency.md)).
7. Sticky session or single FastAPI instance for Call 1→2 until Phase 5 shared session.

---

## 10. Architecture (normative packages)

```text
com.heavy_rental.rest_api.client.haystack.*
com.heavy_rental.rest_api.service.RecommenderSagaService
com.heavy_rental.rest_api.controller.RecommendationController
```

HTTP client: **RestClient** (not WebClient for S2b). Controllers MUST NOT call haystack directly.

Client methods: `health`, `ingest` (Call 1), `recommend` (Call 2), `queryProjectKnowledge` (Call 3).

---

## 11. Verification

### 11.1 Automated (required for CI)

| Class | Covers |
|-------|--------|
| `HaystackRecommenderClientTest` | Headers, Call 2 quote + Call 3 answer DTO mapping, multipart ingest, 4xx/5xx |
| `HaystackRetryIdempotencyTest` | Same key on 5xx retry |
| `HaystackTimeoutRetryTest` | Plan §7 #1: delay &gt; read timeout; retry reuses key; timeout mapping |
| `HaystackCircuitBreakerTest` | Open → fail-fast |
| `HaystackBulkheadTest` | Concurrency limit |
| `RecommenderSagaServiceTest` | Dual-hop quote (Mockito); multipart path; one ingest on Call 2 fail; Call 3 knowledge-query |
| `RecommenderSagaWireMockTest` | Plan §7 #4/#6/#8: real client dual-hop WireMock paths + shared correlation + quote body |
| `RecommendationControllerIntegrationTest` | MockMvc + JWT + WireMock: JSON/multipart submit, session, knowledge-query, 401 |

```bash
cd heavy-rental-spring-rest-api
./mvnw -Dtest=HaystackRecommenderClientTest,HaystackRetryIdempotencyTest,HaystackTimeoutRetryTest,HaystackCircuitBreakerTest,HaystackBulkheadTest,RecommenderSagaServiceTest,RecommenderSagaWireMockTest,RecommendationControllerIntegrationTest test
```

### 11.2 Optional joint (manual)

1. Start haystack; confirm S2a double POST same key → same `ingest_id`.
2. Point `haystack.base-url` at it; run portal submit + knowledge-query.
3. Confirm correlation in logs; one logical ingest on retry; submit body has quote; knowledge-query has answer.

### 11.3 Exit criteria

- [x] Per-op timeouts configured and tested (including recommend-read vs qa-read; timeout retry `HaystackTimeoutRetryTest`)
- [x] CB opens on forced 5xx and recovers (`HaystackCircuitBreakerTest`)
- [x] Bulkhead limits concurrency (`HaystackBulkheadTest`)
- [x] Ingest always sends `Idempotency-Key`; retries reuse key (`HaystackRetryIdempotencyTest`, `HaystackTimeoutRetryTest`)
- [x] Correlation on every call (`HaystackRecommenderClientTest`, dual-hop `RecommenderSagaWireMockTest`)
- [x] Saga does not re-ingest after Call 2 recommend failure (`RecommenderSagaServiceTest`, `RecommenderSagaWireMockTest`)
- [x] Portal submit returns quote fields; knowledge-query uses Call 3 path
- [x] Portal REST authenticated and ownership-safe (JWT + `CurrentUserService`)
- [x] WireMock suite green (plan §7 scenarios 1–9 covered)
- [x] Prod ingest retry default false (`haystack.retry.ingest-enabled=false`)

**As-built notes (2026-08-12):**

- `haystack_user_id` = `String.valueOf(user.getId())` (or Call 1 echo)
- Portal Call 1 supports **JSON and multipart** (`file` + form fields); max size via `haystack.max-in-memory-size`
- Call 2 → `HaystackRecommenderClient.recommend` / `PATH_RECOMMEND`
- Call 3 → `queryProjectKnowledge` / `PATH_QUERY`
- Resilience4j applied programmatically (CB + bulkheads + exponential-backoff retry), not annotation AOP
- Call 2 `items` mapped to portal JSON only; no `recommendation_items` writes
- Optional Call 2 `confidenceScore` stored on `AIRecommendation` when present

---

## 12. Ops runbook (S2b)

| Topic | Guidance |
|-------|----------|
| Endpoint prefix | Haystack `/internal/v1/recommendations` |
| Timeout matrix | health 2–5s; Q&A 30–60s; recommend 60–120s; ingest 120–300s+ (tune with p95) |
| Max multipart | **Same number** on gateway, Spring, FastAPI (when file path lands) |
| Sticky session | Required for Call 2 until Phase 5 |
| CB open UX | Portal: “recommender unavailable” — no fake fleet |
| S2a gate | Do not enable prod ingest retry without S2a on that environment |
| C2 trigger | If gateway idle timeout kills long POSTs → schedule C2; do not expand S2b |

---

## 13. Implementation tasks

See Spec-Kit [`features/s2b-haystack-recommender-client/tasks.md`](./features/s2b-haystack-recommender-client/tasks.md) and OpenSpec change `tasks.md`.

PR packing: **S2b-0** docs → **S2b-1** client → **S2b-2** resilience → **S2b-4** saga+portal → **S2b-5** polish.

---

## 14. Key decisions

| Decision | Choice |
|----------|--------|
| HTTP client | RestClient |
| Persist target | Extend `AIRecommendation` |
| Call 2 | Recommend quote → portal primary body |
| Call 3 | Chatbot Q&A → knowledge-query only |
| Call 2 items storage | Response-only (S2b) |
| Prod ingest retry default | **false** |
| Spec standards | Hybrid OpenSpec + Spec-Kit + SPDD + living SPEC |

---

## 15. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-12 | Initial planned contract from Phase 2 S2b elaboration. |
| 1.0.0 | 2026-08-12 | As-built S2b (early dual-hop modeled Call 2 as Q&A — superseded). |
| 1.1.0 | 2026-08-12 | Portal submit = Call 1 then path getassetrecommendations (still Q&A-shaped body — superseded). |
| **2.0.0** | 2026-08-12 | **Feasibility v2 alignment.** Call 2 = recommend quote (`quoteRef`/`items`); Call 3 = `.../query` chatbot. Separate recommend timeout/bulkhead. Specs/OpenSpec/SPDD/feasibility as-built notes updated. |
| **2.0.1** | 2026-08-12 | Plan §7 residual tests: timeout+same-key retry, dual-hop WireMock saga (paths + correlation + quote); exponential retry backoff. |
| **2.1.0** | 2026-08-12 | Multipart project-file submit; `RecommendationControllerIntegrationTest` (MockMvc). |
