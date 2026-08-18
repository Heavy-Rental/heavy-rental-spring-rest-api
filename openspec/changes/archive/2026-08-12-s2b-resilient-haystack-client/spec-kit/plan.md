# Plan: S2b Haystack Recommender Client (Spring)

| Field | Value |
|-------|--------|
| **Document type** | Spec-Kit plan artifact (HOW) |
| **Status** | **As-built** |
| **Date** | 2026-08-12 |
| **Detail design** | [`../design.md`](../design.md) |

## 1. Technical approach

Use **Spring RestClient** against haystack-fast-api with **Resilience4j** (circuit breaker, bulkhead, retry). Orchestrate multi-call flow in **`RecommenderSagaService`**. Persist Call 1 identity on **`AIRecommendation`**. Expose thin **portal REST** for submit (Call 1+2) + knowledge-query (Call 3).

Constitution constraints (from environment SPEC): Java 21, Boot 4.1, Postgres on `db`, Bearer JWT, thin controllers, shared error JSON, no H2 default tests.

## 2. Component map

| Component | Responsibility |
|-----------|----------------|
| `HaystackProperties` | base URL, timeouts (health/qa/recommend/ingest), retry flags, bulkhead limits |
| `HaystackClientConfig` | RestClient beans + Resilience4j beans |
| `HaystackRecommenderClient` | health / ingest / **recommend** / **query** HTTP + header injection |
| Resilience wrappers | CB + bulkheads (ingest/recommend/qa) + retry around client ops |
| `RecommenderSagaService` | keys, orchestration, persistence, no re-ingest rule |
| `RecommendationController` | portal HTTP |
| `AIRecommendation` (+ repo) | session SoT for `ingest_id` |

## 3. Data flow

### Portal submit (Call 1 then Call 2 recommend)

1. Controller validates request; resolves `User` from JWT.
2. Saga mints `idempotencyKey` + `correlationId` (or uses inbound correlation).
3. Client POSTs Call 1 JSON to `submitprojectspecification` with headers.
4. On 200 lean body: map FR-IX-023 fields; save `AIRecommendation`.
5. Client POSTs Call 2 to `.../getassetrecommendations` with stored `ingest_id` + optional focus query.
6. Return portal response with `recommendationId`, summary, **`quoteRef`**, **`items`**, rates.

### Follow-up Call 3 (`/knowledge-query`)

1. Load recommendation by id; enforce ownership.
2. Client POSTs `{ user_id, ingest_id, query, top_k? }` to **`.../project-knowledge/query`** — **no second ingest**.
3. Return `answer` / `sourcesUsed` subset to portal.

## 4. Wire paths (normative)

- `GET /health`
- `POST /internal/v1/recommendations/submitprojectspecification` — Call 1
- `POST /internal/v1/recommendations/project-knowledge/getassetrecommendations` — Call 2
- `POST /internal/v1/recommendations/project-knowledge/query` — Call 3

## 5. Portal paths

- `POST /api/recommendations/project-spec`
- `POST /api/recommendations/{recommendationId}/knowledge-query`
- `GET /api/recommendations/{recommendationId}`

## 6. Configuration (sketch)

See OpenSpec design and living SPEC. Defaults:

- ingest read ~180s, recommend ~90s, Q&A ~45s, health ~5s, connect ~5s
- `haystack.retry.ingest-enabled=false`

## 7. Test plan

WireMock-first: client (Call 2 quote + Call 3 answer), retry key, CB, bulkhead, saga dual-hop quote + no re-ingest.

## 8. PR slices

| Slice | Content |
|-------|---------|
| S2b-1 | Client + properties + timeouts + happy WireMock |
| S2b-2 | Resilience4j + retry-with-key |
| S2b-3 | Correlation (may fold into S2b-1) |
| S2b-4 | Saga + entity + controller |
| S2b-5 | Runbook polish + Feasibility v2 Call 2/3 realign |

## 9. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Double-index on retry | S2a gate; same Idempotency-Key; default retry off |
| Call 2/3 confusion | Distinct paths + DTOs; portal submit never returns Call 3 answer |
| Gateway kills long POST | Long ingest timeout + C2 trigger in runbook |
| Empty fleet invented | Map empty items + warnings; never fabricate on error |
