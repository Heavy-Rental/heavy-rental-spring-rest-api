# Spec-Kit feature pack — S2b Haystack Recommender Client

| Field | Value |
|-------|--------|
| **Feature** | Phase 2 / S2b — resilient Spring client for haystack-fast-api |
| **Spec-Kit flow** | Constitution (env SPEC) → **Specify** → **Plan** → **Tasks** → **Implement** |
| **Status** | **As-built** (Feasibility v2: Call 1 ingest · Call 2 recommend · Call 3 Q&A) |

## Artifacts

| File | Spec-Kit role |
|------|----------------|
| [`spec.md`](./spec.md) | **Specify** — what/why, user stories, acceptance |
| [`plan.md`](./plan.md) | **Plan** — technical design (RestClient, Resilience4j, packages) |
| [`tasks.md`](./tasks.md) | **Tasks** — ordered implementation checklist |
| [`checklist.md`](./checklist.md) | Requirements-quality gate |

## Linked standards

| Standard | Location |
|----------|----------|
| OpenSpec change | [`../../../openspec/changes/s2b-resilient-haystack-client/`](../../../openspec/changes/s2b-resilient-haystack-client/) |
| OpenSpec SoT | [`../../../openspec/specs/haystack-recommender/spec.md`](../../../openspec/specs/haystack-recommender/spec.md) |
| SPDD REASONS | [`../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md`](../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md) |
| Living contract | [`../../SPEC-haystack-recommender-client.md`](../../SPEC-haystack-recommender-client.md) |
| Feasibility | [`../../../Feasibility_Study_Spring/`](../../../Feasibility_Study_Spring/) |
| Constitution | [`../../SPEC-project-environment.md`](../../SPEC-project-environment.md) |

## Normative call model

```text
POST /api/recommendations/project-spec
  → Call 1 ingest → Call 2 getassetrecommendations (quote)
POST /api/recommendations/{id}/knowledge-query
  → Call 3 project-knowledge/query (answer)
```

## How to use

1. Read `spec.md` and OpenSpec requirements — lock intent.
2. Read `plan.md` / OpenSpec `design.md` — lock approach.
3. Review `checklist.md` — mark only when a human reviewer is satisfied.
4. Implement via `tasks.md` (TDD + WireMock). Prefer SPDD canvas for generation prompts.
5. Update living SPEC when as-built behaviour changes.

**Rule (SPDD):** if reality diverges, fix the prompt/spec first, then the code.
