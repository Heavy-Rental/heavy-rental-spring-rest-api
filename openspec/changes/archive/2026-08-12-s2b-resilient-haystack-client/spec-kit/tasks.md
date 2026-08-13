# Tasks: S2b Haystack Recommender Client

Canonical task list. Keep in sync with OpenSpec change `tasks.md`.

## Phase 0 — Specs

- [x] OpenSpec proposal / design / delta
- [x] Spec-Kit spec / plan / tasks / checklist
- [x] SPDD REASONS canvas
- [x] Living `SPEC-haystack-recommender-client.md` + index/environment/entity/tests updates
- [x] Checklist gate opened by implement request (2026-08-12)
- [x] Realign docs to Feasibility v2 (Call 2 recommend / Call 3 Q&A)

## Phase 1 — Client + timeouts

- [x] Maven deps: Resilience4j + WireMock
- [x] `HaystackProperties` + `application.properties` (recommend-read)
- [x] RestClient config
- [x] DTOs + `HaystackRecommenderClient` (health, ingest, **recommend**, **query**)
- [x] Per-op timeouts + error mapping
- [x] WireMock happy path (quote + answer)

## Phase 2 — Resilience + headers

- [x] Circuit breaker
- [x] Bulkheads (ingest / recommend / Q&A)
- [x] Retry + same `Idempotency-Key`; ingest retry flag default false
- [x] `X-Correlation-Id`
- [x] Resilience WireMock / unit tests

## Phase 3 — Saga + portal

- [x] Extend `AIRecommendation`
- [x] `RecommenderSagaService` (Call 1→2 quote; Call 3 knowledge-query)
- [x] `RecommendationController` + portal DTOs (`quoteRef`/`items`)
- [x] Ownership + error codes
- [x] Saga unit tests

## Phase 4 — Closeout

- [x] Runbook / as-built notes in living SPEC
- [x] Full `./mvnw test` green
- [x] Status → As-built under Feasibility v2 model
- [x] OpenSpec / SPDD / Spec-Kit / Feasibility package sync
