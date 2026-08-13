# SPDD REASONS Canvas — S2b Resilient Haystack Recommender Client

| Field | Value |
|-------|--------|
| **Document type** | SPDD structured prompt (first-class delivery artifact) |
| **Change** | `s2b-resilient-haystack-client` |
| **Status** | **As-built** (Feasibility v2 Call 1/2/3) |
| **Date** | 2026-08-13 |
| **Discipline** | If reality diverges: **update this canvas first**, then code. Refactors without behavior change: code first, then sync canvas. |

**Linked:** OpenSpec [`openspec/specs/haystack-recommender/`](../../openspec/specs/haystack-recommender/) · portal contract [`contracts/portal-api.md`](../../openspec/specs/haystack-recommender/contracts/portal-api.md) · archived change `openspec/changes/archive/2026-08-12-s2b-resilient-haystack-client/` · [`Feasibility_Study_Spring/`](../../Feasibility_Study_Spring/) · OpenSPDD [`../README.md`](../README.md)

---

## R — Requirements

### Problem
Spring must orchestrate haystack-fast-api **Call 1 (ingest)**, **Call 2 (recommend / quote)**, and **Call 3 (chatbot Q&A)** with timeouts, Resilience4j, idempotent ingest retries, correlation, saga persistence of `ingest_id`, and thin portal REST — without inventing equipment when the recommender is down.

### Definition of Done
- FR-S2B-001…010 implemented and covered by WireMock (or equivalent) tests (incl. nested portal `items[].equipment`)
- Portal: `POST /api/recommendations/project-spec` (Call 1+2 → **quote**), `POST .../knowledge-query` (Call 3 → **answer**), `GET .../{id}`
- Prod default: `haystack.retry.ingest-enabled=false` until S2a confirmed
- Shared error JSON; no re-ingest on Call 2/3 failure; same `Idempotency-Key` on ingest retry

### Scope out
C2 202/SSE, C3 gRPC/queues, C/W/D in Spring, Q&A history table, Flyway, `recommendation_items` row writes from Call 2

---

## E — Entities

| Concept | Representation |
|---------|----------------|
| Recommendation session | `AIRecommendation` (+ `ingest_id`, `haystack_user_id`, `idempotency_key`, `correlation_id`, budget/date/warnings; optional `confidenceScore` from Call 2) |
| Portal user | existing `User` / JWT via `CurrentUserService` |
| Haystack ingest response | lean FR-IX-023 DTO |
| Haystack recommend (Call 2) | `GetAssetRecommendationsResponse` — `quoteRef`, nested `items[]` |
| Portal quote item | `RecommendItemResponse` — `rankOrder`, `matchScore`, `reason`, `lineTotal`, `quantity`, nested `equipment` |
| Portal equipment | `RecommendEquipmentResponse` — `id`, `name`, `category`, `baseDailyRate`, `weekly`, `capacity`, `platformHeight` (JSON omitted when null), `purchaseYear`, `location`, `available`, `img`, `desc`, `tags`. Haystack pass-through except: `img` is the catalog JPEG data URI when numeric `id` matches `asset_images`; never invent equipment/rates. |
| Haystack Q&A (Call 3) | `ProjectKnowledgeQueryResponse` — `answer`, `sources_used`; not persisted in S2b |
| Haystack error | `{error, message}` |
| Ranked assets | Nested portal JSON from Call 2 only; **`RecommendationItem` rows not written in S2b** |

---

## A — Approach

1. **RestClient** to haystack base URL with per-op read timeouts (health / qa / recommend / ingest).
2. **Resilience4j** CB + bulkheads (ingest / recommend / qa) + limited retry (ingest only with same key when flag on).
3. **Saga service** owns keys, Call order (1→2 on submit; 3 on knowledge-query), persistence, and “no re-ingest” rule. After Call 2, map nested `items[].equipment`; omit null `platformHeight`; batch-load `asset_images` for numeric catalog ids and set `img` to the browse JPEG data URI.
4. **Thin controller** for portal; derive user identity server-side.
5. **WireMock** for default CI; optional joint test against real haystack later.
6. Sticky/single FastAPI instance for Call 1→2 is an **ops** constraint, not Spring clustering logic.

---

## S — Structure

```text
com.heavy_rental.rest_api
  client.haystack.*          // properties, config, client, dto, exceptions
  service.RecommenderSagaService
  controller.RecommendationController
  entity.AIRecommendation    // extended
  repository.AIRecommendationRepository
  repository.AssetImageRepository  // catalog JPEG for numeric items[].equipment.id
  dto.*                      // portal records (Submit*, RecommendItem*, RecommendEquipment*, ProjectKnowledge*)
```

Feasibility wire docs remain normative for HTTP shapes:  
`Feasibility_Study_Spring/wire-contract-call1-call2.md`, `call1-ingest-response-for-spring.md`, `portal-to-haystack-mapping.md`.

---

## O — Operations (implementation steps)

### O1 — Dependencies & config
1. Resilience4j + WireMock test dependency (Boot 4.1–compatible).
2. `haystack.*` properties including `timeouts.recommend-read`, recommend bulkhead/retry.
3. `HaystackProperties` (`@ConfigurationProperties`).

### O2 — RestClient + DTOs
1. `HaystackClientConfig` builds RestClient with base URL and per-op timeouts.
2. DTOs: lean ingest; **Call 2** `GetAssetRecommendations*`; **Call 3** `ProjectKnowledgeQuery*`; `HaystackErrorBody`.
3. `HaystackRecommenderClient`:
   - `health()`
   - `ingest(...)` — Call 1
   - `recommend(...)` — Call 2 `.../getassetrecommendations`
   - `queryProjectKnowledge(...)` — Call 3 `.../project-knowledge/query`
4. On non-2xx, parse error body when present; throw typed exception with status + codes.

### O3 — Headers
1. Ingest: always set `Idempotency-Key` from command (saga-supplied).
2. All calls: set `X-Correlation-Id` from command/MDC.
3. Optionally set `traceparent` if available.

### O4 — Resilience
1. Wrap ingest / recommend / Q&A with circuit breaker `haystack`.
2. Bulkheads `haystackIngest` / `haystackRecommend` / `haystackQa`.
3. Retry: max attempts from config; only for timeout/5xx; **ingest retries must not mint a new key**.
4. If CB open or bulkhead full → map to `recommender_unavailable` (HTTP 503).
5. Timeout → `recommender_timeout` (HTTP 504).

### O5 — Saga + persistence
1. Extend `AIRecommendation` entity/columns as in design.
2. `submitProjectSpec(user, request, correlationId?)`:
   - generate UUID idempotency key
   - resolve correlation id
   - call ingest (Call 1)
   - persist row (`status=GENERATED`, summary fields, handles)
   - call `recommend` (Call 2) with stored `ingest_id`
     - optional focus: portal `query` → Call 1 summary → fixed default
   - return portal response with Call 2 **quote** (`quoteRef`, `items`, …)
     - nested `equipment.platformHeight` omitted from JSON when null
     - nested `equipment.img` from `asset_images` when `id` is a numeric catalog PK
   - if Call 2 fails: **do not** re-ingest; session remains for retry / Call 3
3. `queryKnowledge(user, recommendationId, query, topK?)`:
   - load by id; 404 if missing; 403 if not owner (unless admin)
   - call **Call 3** with stored `ingest_id` + `haystack_user_id`
   - **do not** call ingest or Call 2
   - return answer DTO (no required persist of answer)

### O6 — Portal API
1. `RecommendationController` mappings for POST project-spec, POST knowledge-query, GET by id.
2. JSON and multipart for project-spec submit (file and/or projectText).
3. Map exceptions via `RestExceptionHandler` / `ResponseStatusException`.

### O7 — Tests
1. Client tests: Call 2 quote mapping; Call 3 answer mapping; headers; 4xx/5xx.
2. Retry test verifies same `Idempotency-Key` header twice.
3. CB test forces N failures then fail-fast.
4. Saga test: ingest 200 + recommend 500 → one ingest; happy path quote body; catalog `img` by numeric id.
5. Knowledge-query uses Call 3 only.
6. Portal MockMvc: nested equipment JSON; omit-null `platformHeight`; catalog `img` data URI.

### O8 — Closeout
1. Document runbook in living SPEC.
2. Sync this canvas if code refactors change structure.
3. Align Feasibility package status to as-built Spring S2b.

---

## N — Norms

1. Controllers stay thin; no haystack calls from controllers.
2. Prefer Java **records** for DTOs; camelCase JSON for portal; snake_case only where haystack contract requires (map in client layer).
3. Errors: `{ "error": "<code>", "message": "<text>" }`.
4. Reuse `CurrentUserService` for principal → `User`.
5. No H2; tests use Postgres or pure unit tests with WireMock without full context where practical.
6. Do not invent `asset_id`, inventory, or daily rates from failure paths or empty Call 2 fleets.
7. Match existing package style under `com.heavy_rental.rest_api`.
8. Update SPECs in the same PR when behavior deliberately changes.
9. Call numbering must match Feasibility: **2 = recommend**, **3 = Q&A**.

---

## S — Safeguards

1. **MUST NOT** re-ingest when Call 2 recommend or Call 3 Q&A fails.
2. **MUST NOT** rotate `Idempotency-Key` on retry of the same logical submit.
3. **MUST NOT** enable production ingest retry without S2a on target haystack.
4. **MUST NOT** return fabricated equipment lists when CB open / bulkhead full / timeout.
5. **MUST NOT** trust client-supplied haystack `user_id`.
6. **MUST NOT** treat Call 2 as chatbot Q&A or expect `answer` on recommend path.
7. **MUST NOT** expand scope into C2/C3 product tracks in this change.
8. **MUST** use `/internal/v1/recommendations/...` paths only.
9. **MUST** keep max upload size configurable and documented for gateway alignment.
10. **MUST NOT** invent `equipment.img` when `id` is not a numeric catalog PK or `asset_images` has no row.
11. **MUST** omit portal `equipment.platformHeight` from JSON when null.

---

## Generation notes (for `/spdd-generate` style runs)

- Implement **task-by-task** following O1→O7 order; stop at package boundaries for PR slices.
- Prefer failing tests first for resilience scenarios.
- After behavior fixes: update OpenSpec scenario / this canvas, then code.
- After pure refactors: code then update **S** / **O** sections of this canvas.
