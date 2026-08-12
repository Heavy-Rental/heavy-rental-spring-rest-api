# OpenSpec — Heavy Rental Spring REST API

| Field | Value |
|-------|--------|
| **Module** | `heavy-rental-spring-rest-api` |
| **Base package** | `com.heavy_rental.rest_api` |
| **Stack** | Java 21 · Spring Boot 4.1 · PostgreSQL · OAuth2 Resource Server JWT |
| **Living contracts** | [`specification/`](../specification/) (`SPEC-*.md`) |
| **Environment constitution** | [`specification/SPEC-project-environment.md`](../specification/SPEC-project-environment.md) |
| **Feasibility (S2b)** | [`Feasibility_Study_Spring/`](../Feasibility_Study_Spring/) |
| **Spec-Kit feature pack** | [`specification/features/s2b-haystack-recommender-client/`](../specification/features/s2b-haystack-recommender-client/) |
| **SPDD prompts** | [`spdd/prompt/`](../spdd/prompt/) |

## Purpose

OpenSpec holds **behavior source-of-truth** (`specs/`) and **proposed changes** (`changes/`) for capabilities that evolve via ADDED/MODIFIED/REMOVED deltas.

This is a **brownfield hybrid**: existing feature contracts remain under `specification/SPEC-*.md`. OpenSpec does not replace them; after a change is implemented and verified, deltas archive into `openspec/specs/` and the matching `SPEC-*.md` is marked as-built.

## Domains

| Domain path | Capability |
|-------------|------------|
| `haystack-recommender` | Spring → haystack-fast-api resilient client, saga, portal REST (S2b: Call 1 ingest · Call 2 recommend · Call 3 Q&A) |

## Active changes

| Change folder | Status |
|---------------|--------|
| [`changes/s2b-resilient-haystack-client/`](./changes/s2b-resilient-haystack-client/) | **As-built** — runtime + WireMock; Feasibility v2 Call 1/2/3 |

## Conventions

1. Requirements use RFC 2119 (**MUST** / **SHALL** / **SHOULD** / **MAY**).
2. Scenarios use **GIVEN / WHEN / THEN**.
3. Specs describe observable behavior, not class names (implementation lives in `design.md` / Spec-Kit `plan.md` / SPDD canvas).
4. Controllers stay thin; services orchestrate; shared error JSON is `{ "error", "message" }`.
5. Do not restate Postgres/JWT defaults — see environment SPEC.
