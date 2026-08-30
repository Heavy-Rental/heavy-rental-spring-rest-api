# Feasibility studies — Spring Boot (export package)

| Field | Value |
|-------|--------|
| **Version** | **2.1.1** |
| **Date** | 2026-08-13 |
| **Audience** | Spring Boot engineers integrating with `haystack-fast-api` |
| **S2b Spring status** | **As-built** in this repo (client, saga, portal REST, WireMock) |
| **Upstream repo** | [Heavy-Rental/haystack-fast-api](https://github.com/Heavy-Rental/haystack-fast-api) (**read-only**) |
| **Synced from** | `develop` @ `12f89dda9b27ba0196c7a37f7f4310459731cb1e` (2026-08-13) |

**Behaviour SoT (Spring):** OpenSpec [`../openspec/specs/haystack-recommender/`](../openspec/specs/haystack-recommender/) (+ [`contracts/portal-api.md`](../openspec/specs/haystack-recommender/contracts/portal-api.md)).  
**Wire / orchestration notes:** this package.  
**Upstream contracts (read-only):** haystack OpenSpec Call 1/2/3 under that repo’s `openspec/specs/`.

> **Do not treat this package as the product SoT.** It is a 2026-08-13 Haystack Call 1/2/3 wire snapshot (S2b as-built). Later as-built work lives only in OpenSpec: FR-S2B-011 quantity pass-through, flag-gated `POST /internal/v1/pricing/quote` on plan quote, and OneMap postal/distance. Implementation-plan checklists here describe the original S2b build; they are not an unimplemented backlog.

### Aligned with haystack (Call numbering)

| Call | Path | Role | Upstream contract (haystack-fast-api) |
|------|------|------|----------------------------------------|
| **1** | `.../submitprojectspecification` | Ingest | `openspec/specs/indexing/contracts/ingest-from-project-spec.md` |
| **2** | `.../project-knowledge/getassetrecommendations` | **Recommend / quote** | `openspec/specs/recommendation-pipeline/contracts/get-asset-recommendations.md` |
| **3** | `.../project-knowledge/query` | **Chatbot Q&A** | `openspec/specs/knowledge-graph/contracts/project-knowledge-query.md` |

| Topic | Haystack | This package |
|-------|----------|--------------|
| Portal saga | `implementation-plan.md` §1.2.0+ | [`portal-to-haystack-mapping.md`](./portal-to-haystack-mapping.md) **2.0.3** |
| Call 2 recommend | OpenSpec recommend contract (+ `mlPredictedPrice` + FR-P-013 quantity collapse) | [`wire-contract-call1-call2.md`](./wire-contract-call1-call2.md) **2.0.2** |
| Call 3 Q&A | KG contract `project-knowledge-query.md` | Same wire doc § Call 3 |
| S2a | `phase2-s2a-*.md` | [`s2a-haystack-dependency.md`](./s2a-haystack-dependency.md) |
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
7. Spring OpenSpec: [`../openspec/AGENTS.md`](../openspec/AGENTS.md)

## Documents

| Document | Version |
|----------|---------|
| portal-to-haystack-mapping.md | **2.0.3** |
| wire-contract-call1-call2.md | **2.0.2** |
| call1-ingest-response-for-spring.md | **2.0.0** |
| phase2-s2b-spring-implementation-plan.md | **2.1.0** |
| spring-boot-fastapi-integration-resilience.md | **2.2.2** |
| s2a-haystack-dependency.md | **1.2.0** |
| HANDOFF.md | **2.1.1** |

## Spring as-built map (this repo)

| Feasibility step | Spring artifact |
|------------------|-----------------|
| B1–B2 client + timeouts | `client.haystack.HaystackRecommenderClient`, `HaystackProperties` |
| B3 Resilience4j | CB `haystack`; bulkheads ingest/recommend/qa; retry |
| B4 headers | `Idempotency-Key` (Call 1), `X-Correlation-Id` (all) |
| B5 saga | `RecommenderSagaService` + `RecommendationController` |
| B6 runbook | OpenSpec haystack-recommender + this package |

Specs: OpenSpec `openspec/specs/haystack-recommender/`, OpenSPDD `spdd/prompt/S2b-resilient-haystack-recommender-client.md` (Spec-Kit pack archived under `openspec/changes/archive/2026-08-12-s2b-resilient-haystack-client/spec-kit/`).

## Sync policy (read-only)

1. Pull latest upstream Feasibility / OpenSpec contracts via GitHub **read** APIs only — never push to haystack-fast-api from this repo.  
2. Diff `Feasibility_Study_Spring/` against upstream package; keep Spring as-built class maps.  
3. Stamp `Synced from` branch + commit SHA on updated files.  
4. Prefer linking upstream OpenSpec paths for normative wire detail rather than duplicating full schemas.

## Copy into Spring repo

```bash
cp -R Feasibility_Study_Spring /path/to/spring-boot/docs/Feasibility_Study
```
