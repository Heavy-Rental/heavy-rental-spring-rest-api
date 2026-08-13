# Feasibility studies — Spring Boot (export package)

| Field | Value |
|-------|--------|
| **Version** | **2.1.0** |
| **Date** | 2026-08-12 |
| **Audience** | Spring Boot engineers integrating with `haystack-fast-api` |
| **S2b Spring status** | **As-built** in this repo (client, saga, portal REST, WireMock) |

**Behaviour SoT:** haystack OpenSpec + this package for Spring orchestration; living Spring contract: `specification/SPEC-haystack-recommender-client.md`.

### Aligned with haystack `Feasibility_Study/` (Call numbering)

| Call | Path | Role |
|------|------|------|
| **1** | `.../submitprojectspecification` | Ingest |
| **2** | `.../project-knowledge/getassetrecommendations` | **Recommend / quote** |
| **3** | `.../project-knowledge/query` | **Chatbot Q&A** |

| Topic | Haystack | This package |
|-------|----------|--------------|
| Portal saga | `implementation-plan.md` §1.2.0 (v3.5+) | [`portal-to-haystack-mapping.md`](./portal-to-haystack-mapping.md) **2.0** |
| Call 2 recommend | OpenSpec recommend contract | [`wire-contract-call1-call2.md`](./wire-contract-call1-call2.md) **2.0** |
| Call 3 Q&A | KG contract `project-knowledge-query.md` | Same wire doc § Call 3 |
| S2a | `phase2-s2a-*.md` v1.1.2 | [`s2a-haystack-dependency.md`](./s2a-haystack-dependency.md) |
| S2b | pointer | [`phase2-s2b-spring-implementation-plan.md`](./phase2-s2b-spring-implementation-plan.md) **2.1** |

```text
React  POST /api/recommendations/project-spec
  → Call 1 ingest → Call 2 recommend quote → React
  → optional Call 3 chatbot Q&A  (POST .../knowledge-query)
```

## Reading order

1. [`portal-to-haystack-mapping.md`](./portal-to-haystack-mapping.md)  
2. [`wire-contract-call1-call2.md`](./wire-contract-call1-call2.md)  
3. [`call1-ingest-response-for-spring.md`](./call1-ingest-response-for-spring.md)  
4. [`s2a-haystack-dependency.md`](./s2a-haystack-dependency.md)  
5. [`phase2-s2b-spring-implementation-plan.md`](./phase2-s2b-spring-implementation-plan.md)  
6. [`HANDOFF.md`](./HANDOFF.md)  

## Documents

| Document | Version |
|----------|---------|
| portal-to-haystack-mapping.md | **2.0.0** |
| wire-contract-call1-call2.md | **2.0.0** |
| call1-ingest-response-for-spring.md | **2.0.0** |
| phase2-s2b-spring-implementation-plan.md | **2.1.0** |
| spring-boot-fastapi-integration-resilience.md | **2.2.2** |
| s2a-haystack-dependency.md | **1.2.0** |
| HANDOFF.md | **2.1.0** |

## Spring as-built map (this repo)

| Feasibility step | Spring artifact |
|------------------|-----------------|
| B1–B2 client + timeouts | `client.haystack.HaystackRecommenderClient`, `HaystackProperties` |
| B3 Resilience4j | CB `haystack`; bulkheads ingest/recommend/qa; retry |
| B4 headers | `Idempotency-Key` (Call 1), `X-Correlation-Id` (all) |
| B5 saga | `RecommenderSagaService` + `RecommendationController` |
| B6 runbook | Living SPEC §12 + this package |

Specs: OpenSpec `openspec/specs/haystack-recommender/`, Spec-Kit `specification/features/s2b-haystack-recommender-client/`, SPDD `spdd/prompt/S2b-resilient-haystack-recommender-client.md`.

## Copy into Spring repo

```bash
cp -R Feasibility_Study_Spring /path/to/spring-boot/docs/Feasibility_Study
```
