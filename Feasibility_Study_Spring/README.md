# Feasibility studies — Spring Boot (export package)

| Field | Value |
|-------|--------|
| **Document type** | Handoff package for the Spring Boot REST API team |
| **Scope** | Phase 2 / **S2b** — Resilience C1 (client half) |
| **Audience** | Spring Boot engineers integrating with `haystack-fast-api` |
| **Status** | Docs only — not Spring runtime code |
| **Version** | **1.0.0** |
| **Date** | 2026-08-12 |
| **Source repo** | Published from `haystack-fast-api`; copy this entire folder into the Spring Boot project |

**Behaviour source of truth** for HTTP shapes remains in the haystack repo OpenSpec (`openspec/specs/indexing/`, `openspec/specs/knowledge-graph/`). This package is the **Spring-facing** feasibility + implementation pack for C1.

---

## Architecture principles (Spring seat)

| Principle | Detail |
|-----------|--------|
| **Spring owns** | Portal/domain SoT, auth, booking/session persistence, **multi-call saga** orchestration |
| **FastAPI owns** | Indexing, KG-1, project-knowledge Q&A, (later) multi-agent recommend / C/W/D roles |
| **Protocol** | **REST** multipart/JSON for Call 1; JSON for Call 2; **not** SSE for file upload |
| **Resilience** | Timeouts, circuit breaker, bulkhead, correlation, **idempotent ingest retries** live mainly on **Spring** |
| **S2a (haystack)** | **As-built:** process-local `Idempotency-Key` store + `X-Correlation-Id` echo — **required before production ingest retry** |
| **C2 jobs** | 202 + poll/SSE is **out of scope** for S2b (Phase 9 if gateway kills long POSTs) |

```text
Portal / user
    │
    ▼
Spring Boot REST API     ← THIS PACKAGE
    │  Call 1: ingest (+ Idempotency-Key, X-Correlation-Id)
    │  Call 2: Q&A 0..N (reuse ingest_id; never re-ingest on Q&A fail)
    │  Call 3: recommend (later; stub OK)
    ▼
haystack-fast-api
```

---

## Reading order

1. [`wire-contract-call1-call2.md`](./wire-contract-call1-call2.md) — live paths, headers, errors  
2. [`call1-ingest-response-for-spring.md`](./call1-ingest-response-for-spring.md) — what to persist from Call 1  
3. [`s2a-haystack-dependency.md`](./s2a-haystack-dependency.md) — FastAPI S2a as-built + join gate  
4. [`spring-boot-fastapi-integration-resilience.md`](./spring-boot-fastapi-integration-resilience.md) — study / verdicts  
5. [`phase2-s2b-spring-implementation-plan.md`](./phase2-s2b-spring-implementation-plan.md) — implement + **test runbook**  
6. [`HANDOFF.md`](./HANDOFF.md) — copy into Spring repo and open tickets  

---

## Documents

| Document | Topic | Version |
|----------|--------|---------|
| [`wire-contract-call1-call2.md`](./wire-contract-call1-call2.md) | Normative HTTP: health, Call 1, Call 2, headers, errors | **1.0.0** |
| [`call1-ingest-response-for-spring.md`](./call1-ingest-response-for-spring.md) | Lean FR-IX-023 body Spring must handle | **1.0.0** |
| [`s2a-haystack-dependency.md`](./s2a-haystack-dependency.md) | FastAPI S2a (FR-IX-024/025); retry join gate | **1.0.0** |
| [`spring-boot-fastapi-integration-resilience.md`](./spring-boot-fastapi-integration-resilience.md) | Integration feasibility (Spring-primary) | **2.0.0** |
| [`phase2-s2b-spring-implementation-plan.md`](./phase2-s2b-spring-implementation-plan.md) | **S2b** implementer plan + WireMock test runbook | **1.1.0** |
| [`HANDOFF.md`](./HANDOFF.md) | Copy checklist into Spring Boot project | **1.0.0** |

---

## How to copy into the Spring Boot repo

```bash
# From haystack-fast-api (or a clone that has this package):
cp -R Feasibility_Study_Spring /path/to/spring-boot-project/docs/Feasibility_Study
# or:
cp -R Feasibility_Study_Spring /path/to/spring-boot-project/Feasibility_Study
```

Then open PRs for **S2b-1…S2b-5** per the implementation plan. See [`HANDOFF.md`](./HANDOFF.md).

---

## Related (optional; lives only in haystack repo)

Not required to implement S2b. Useful context if you have a clone of `haystack-fast-api`:

| Topic | Path in haystack |
|-------|------------------|
| Full stage catalog | `Feasibility_Study/implementation-plan.md` |
| S2a haystack plan + pytest runbook | `Feasibility_Study/phase2-s2a-haystack-implementation-plan.md` |
| Dual-plane / fleet sync | `Feasibility_Study/postgres-haystack-neo4j-realtime-sync.md` |
| C/W/D multi-agent (FastAPI-internal) | `Feasibility_Study/multi-agent-coordinator-worker-delegator.md` |
| OpenSpec ingest contract | `openspec/specs/indexing/contracts/ingest-from-project-spec.md` |
