# Proposal: S2b Resilient Haystack Recommender Client

| Field | Value |
|-------|--------|
| **Change id** | `s2b-resilient-haystack-client` |
| **Stage** | Phase 2 / S2b (Resilience C1 — Spring half) |
| **Status** | **As-built** (aligned with Feasibility_Study_Spring v2 Call 1/2/3) |
| **Date** | 2026-08-12 |
| **Depends on** | Haystack S2a as-built for **production** ingest retry ([`Feasibility_Study_Spring/s2a-haystack-dependency.md`](../../../Feasibility_Study_Spring/s2a-haystack-dependency.md)) |

## Intent

Spring Boot invokes haystack-fast-api multiple times per recommender journey:

```text
Call 1 ingest → Call 2 recommend/quote → (optional) Call 3 chatbot Q&A
```

Work on FastAPI can be long-running. Without client resilience and an explicit saga, the portal risks hung requests, double-indexing on timeout retry, re-ingest when only recommend/Q&A failed, and invented equipment when the recommender is down.

This change makes Spring a **robust orchestrating client** with timeouts, Resilience4j, idempotent ingest retries, correlation, saga persistence of `ingest_id`, and thin portal REST.

## Scope

### In scope

- RestClient-based `HaystackRecommenderClient` (health, Call 1, Call 2 recommend, Call 3 Q&A)
- Per-operation timeouts; Resilience4j circuit breaker, bulkheads (ingest / recommend / qa), limited retry
- `Idempotency-Key` + `X-Correlation-Id` (+ optional `traceparent`)
- `RecommenderSagaService`: ingest → persist → **recommend quote**; never re-ingest on Call 2/3 fail
- Extend `AIRecommendation` for haystack handles
- Portal REST: submit project-spec (returns quote), knowledge-query (Call 3 answer), GET session
- WireMock test suite
- Configuration + ops notes (sticky session, max upload alignment, S2a gate)

### Out of scope

- Implementing S2a on FastAPI (already shipped)
- C2 (202 + poll/SSE), C3 (gRPC/queues)
- C/W/D multi-agent roles in Spring
- Multipart project-file upload (JSON text only in this as-built)
- Persisting `recommendation_items` from Call 2 (map to portal JSON only)
- Q&A answer history table
- Flyway (stay on `ddl-auto=update`)

## Approach

1. Agree behavior via OpenSpec delta + Spec-Kit pack + SPDD REASONS + living `SPEC-haystack-recommender-client.md`.
2. Implement against Feasibility wire: Call 2 = `getassetrecommendations` (quote); Call 3 = `.../query` (answer).
3. Default `haystack.retry.ingest-enabled=false` until S2a is live on the target haystack environment.
4. Archive this change into `openspec/specs/haystack-recommender/` when complete (SoT already mirrors as-built).

## Success criteria

- Exit criteria in feasibility plan §9 plus portal dual-hop WireMock green (quote body).
- Call 3 knowledge-query path green without re-ingest.
- Spec-Kit checklist and SPDD canvas aligned with runtime.

## Related artifacts

| Artifact | Path |
|----------|------|
| Delta specs | [`specs/haystack-recommender/spec.md`](./specs/haystack-recommender/spec.md) |
| Design | [`design.md`](./design.md) |
| Tasks | [`tasks.md`](./tasks.md) |
| Living SPEC | [`specification/SPEC-haystack-recommender-client.md`](../../../specification/SPEC-haystack-recommender-client.md) |
| Spec-Kit | [`specification/features/s2b-haystack-recommender-client/`](../../../specification/features/s2b-haystack-recommender-client/) |
| SPDD | [`spdd/prompt/S2b-resilient-haystack-recommender-client.md`](../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md) |
| Feasibility | [`Feasibility_Study_Spring/`](../../../Feasibility_Study_Spring/) |
