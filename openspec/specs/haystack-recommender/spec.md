# Haystack Recommender (Spring) — Source of Truth

## Purpose

Spring Boot is the orchestrating client of `haystack-fast-api` for:

| Call | Path | Role |
|------|------|------|
| **1** | `POST /internal/v1/recommendations/submitprojectspecification` | Project-spec ingest |
| **2** | `POST /internal/v1/recommendations/project-knowledge/getassetrecommendations` | **Recommend / quote** |
| **3** | `POST /internal/v1/recommendations/project-knowledge/query` | **Chatbot Q&A** |

Resilience, saga orchestration, and portal REST for the recommender journey live here.

**Status:** **As-built** (S2b + FR-S2B-011 quantity pass-through). Requirements below match Feasibility_Study_Spring, runtime code, and portal nested quote items.

**Portal HTTP fields:** [`contracts/portal-api.md`](./contracts/portal-api.md)  
**Upstream wire (read-only):** [haystack-fast-api](https://github.com/Heavy-Rental/haystack-fast-api) OpenSpec Call 1/2/3 contracts (FR-P-013 quantity collapse)  
**OpenSPDD canvas:** [`../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md`](../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md)  
**FR-S2B-011 change:** [`../../changes/2026-08-20-call2-quote-quantity-passthrough/`](../../changes/2026-08-20-call2-quote-quantity-passthrough/) (proposal, REASONS, ADR)

## Requirements

### Requirement: FR-S2B-001 Outbound client with per-operation timeouts

The system MUST call haystack-fast-api over HTTP using a configurable RestClient with separate connect and read timeouts for health, **recommend (Call 2)**, **Q&A (Call 3)**, and ingest (ingest longest; health shortest; recommend typically longer than Q&A).

#### Scenario: Ingest uses long read timeout
- GIVEN haystack ingest is configured with a long read timeout
- WHEN the client performs Call 1
- THEN the request is aborted if the read timeout elapses
- AND a timeout error is surfaced to the caller (not a silent hang)

#### Scenario: Recommend and Q&A use distinct read timeouts
- GIVEN recommend-read and qa-read are configured independently
- WHEN Call 2 or Call 3 is invoked
- THEN each uses its own RestClient timeout profile

### Requirement: FR-S2B-002 Circuit breaker and bulkhead

The system MUST protect haystack calls with a circuit breaker and bulkhead. Prefer separate bulkhead limits for **ingest**, **recommend**, and **Q&A**.

#### Scenario: Circuit opens after repeated failures
- GIVEN haystack returns HTTP 500 repeatedly above the configured threshold
- WHEN further haystack calls are attempted while the circuit is open
- THEN the system fails fast with `recommender_unavailable`
- AND does not invent equipment or prices

### Requirement: FR-S2B-003 Idempotent ingest retries

The system MUST send an `Idempotency-Key` header on every Call 1 POST. When ingest is retried after timeout or 5xx, the system MUST reuse the same key and MUST NOT rotate it mid-retry. Production ingest retry MUST remain disabled until haystack S2a is confirmed on the target environment.

#### Scenario: Retry reuses the same key
- GIVEN a logical project-spec submit with Idempotency-Key K
- AND the first ingest attempt times out
- WHEN the client retries ingest
- THEN both attempts send `Idempotency-Key: K`

#### Scenario: 4xx is not success-retried
- GIVEN haystack returns HTTP 400 on ingest
- WHEN the client handles the response
- THEN no success-path retry loop is entered for that failure

### Requirement: FR-S2B-004 Correlation headers

The system MUST send `X-Correlation-Id` on every haystack call (health, Call 1, Call 2, Call 3). The system MAY send W3C `traceparent` when tracing is available. If the inbound request already carries `X-Correlation-Id`, that value SHOULD be propagated.

#### Scenario: Correlation on ingest and recommend
- GIVEN a correlation id C for a portal submit
- WHEN Call 1 and Call 2 run
- THEN both outbound requests include `X-Correlation-Id: C`

### Requirement: FR-S2B-005 Saga without re-ingest on recommend failure

The system MUST orchestrate **Call 1 then Call 2 recommend** on portal project-spec submit: after successful Call 1, persist `ingest_id`, then call `getassetrecommendations`. On Call 2 failure, the system MUST NOT perform a second Call 1. Follow-up chatbot Q&A is **Call 3** only and also MUST NOT re-ingest.

#### Scenario: Portal submit runs ingest then getassetrecommendations
- GIVEN an authenticated portal submit of project text
- WHEN the saga completes successfully
- THEN haystack received one Call 1 and one Call 2 recommend with the same `ingest_id`
- AND the portal response includes Call 2 `quoteRef` and `items` (not Call 3 `answer`)

#### Scenario: Recommend 500 does not re-ingest
- GIVEN ingest succeeded and `ingest_id` was persisted
- WHEN Call 2 recommend returns HTTP 500
- THEN the saga surfaces a retryable recommend / upstream error
- AND exactly one ingest request was made to haystack

### Requirement: FR-S2B-006 Upstream error mapping

The system MUST map haystack error bodies of shape `{"error","message"}` and HTTP status into domain/API errors. Client (4xx) errors MUST NOT be retried as a success path. Transient 5xx and transport failures MAY be retried within policy.

#### Scenario: FastAPI 400 is not retried as success
- GIVEN haystack returns HTTP 400 with `{"error":"bad_request","message":"..."}`
- WHEN the client handles the response
- THEN the error is mapped to the portal as a client error
- AND the client does not enter a success-path retry loop

### Requirement: FR-S2B-007 Portal REST for project-spec and knowledge query

The system MUST expose authenticated portal endpoints to submit a project-spec (**Call 1 then Call 2 recommend**) and to run follow-up chatbot Q&A (**Call 3**) against a stored recommendation session. The portal MUST NOT trust a client-supplied haystack `user_id`; identity comes from the JWT principal.

#### Scenario: Submit project-spec as authenticated user
- GIVEN a user with a valid access JWT
- WHEN they POST project text to `/api/recommendations/project-spec`
- THEN the system calls haystack Call 1 then Call 2 with a server-derived user identity
- AND returns a recommendation id, lean ingest summary fields, and Call 2 quote fields (`quoteRef`, `items`, …)

#### Scenario: Knowledge query uses Call 3
- GIVEN a persisted recommendation with `ingest_id`
- WHEN the owner POSTs a query to `/api/recommendations/{id}/knowledge-query`
- THEN Call 3 `.../project-knowledge/query` is invoked with that `ingest_id`
- AND the response includes chatbot `answer` (not a recommend quote)

### Requirement: FR-S2B-008 Fail-safe when recommender is unavailable

When the circuit is open, the bulkhead rejects the call, or haystack is otherwise unavailable, the system MUST fail fast with a clear error code (`recommender_unavailable` or equivalent) and MUST NOT invent ranked assets, inventory, or daily rates.

#### Scenario: Circuit open fails safely
- GIVEN the haystack circuit breaker is open
- WHEN the portal submits a project-spec
- THEN the API returns HTTP 503 with `error` = `recommender_unavailable`
- AND no fabricated equipment list is returned

### Requirement: FR-S2B-009 Automated resilience and saga tests

The system MUST include automated tests (WireMock or equivalent) covering timeout+idempotent retry, circuit breaker open, bulkhead limit, saga no re-ingest on Call 2 fail, dual-hop quote happy path, Call 3 path for knowledge-query, correlation headers, and 4xx mapping. Default CI MUST NOT require a live haystack process.

#### Scenario: WireMock verifies single ingest on saga recommend failure
- GIVEN WireMock stubs ingest 200 and Call 2 recommend 500
- WHEN the saga runs project-spec submit
- THEN WireMock records exactly one ingest request
- AND the repository holds the ingest_id from that response

#### Scenario: Portal dual-hop happy path returns quote
- GIVEN WireMock stubs Call 1 200 and Call 2 200 with `quoteRef` / `items`
- WHEN the saga completes successfully
- THEN the portal response includes `quoteRef` and mapped `items`
- AND Call 3 was not required for submit

### Requirement: FR-S2B-010 Nested portal quote items

The system MUST map Call 2 quote lines to the portal as nested objects: each item includes `rankOrder`, optional `matchScore` / `reason` / `quantity` / `lineTotal`, and nested `equipment` with `id`, `name`, `category`, `baseDailyRate`, `weekly`, `capacity`, `platformHeight`, `purchaseYear`, `location`, `available`, `img`, `desc`, and `tags`. The system MUST NOT flatten equipment into `equipmentId` / `equipmentName` top-level item fields.

Haystack values MUST be passed through. The system MUST NOT invent equipment objects, rates, scores, or reasons when omitted upstream.

`platformHeight` MUST be mapped when haystack provides it and MUST be **omitted from portal JSON** when null (not serialized as `null`).

When nested `equipment.id` is a numeric catalog asset id, the system MUST look up `asset_images` and, if a row exists, set `equipment.img` to the same JPEG data URI used by equipment browse (`data:image/jpeg;base64,<raw>`). If no catalog image exists, or the id is not a numeric catalog PK, haystack `img` MUST be passed through unchanged.

Field table: [`contracts/portal-api.md`](./contracts/portal-api.md) `items[].equipment`.

#### Scenario: Submit response exposes nested equipment
- GIVEN Call 2 returns an item with nested `equipment` and optional `reason` / `quantity` / `matchScore` / `platformHeight`
- WHEN the portal project-spec response is built
- THEN each item has nested `equipment` with catalog fields present when provided by haystack
- AND missing optional fields are null or empty (not fabricated), except `platformHeight` which is omitted from JSON when null

#### Scenario: Item-level baseDailyRate falls back onto equipment
- GIVEN haystack places `baseDailyRate` on the item and omits it on `equipment`
- WHEN the item is mapped for the portal
- THEN `equipment.baseDailyRate` receives that value when equipment is present
- AND no other rates are invented

#### Scenario: Null platformHeight is omitted from portal JSON
- GIVEN Call 2 equipment has no `platformHeight`
- WHEN the portal project-spec response is serialized
- THEN `items[].equipment.platformHeight` is absent from the JSON
- AND a present haystack `platformHeight` is still serialized as a number

#### Scenario: Catalog image is loaded onto equipment.img by numeric id
- GIVEN Call 2 equipment `id` is a numeric catalog asset id with an `asset_images` row
- WHEN the portal project-spec response is built
- THEN `equipment.img` is `data:image/jpeg;base64,` plus the stored raw image
- AND a non-numeric id (for example `asset-1`) does not invent a catalog photo

**Automated evidence (BDD JUnit scenarios):**  
`RecommenderSagaServiceTest` — DisplayNames containing `(FR-S2B-010)` plus catalog-img-by-id;  
`RecommenderSagaWireMockTest` dual-hop nested items;  
`RecommendationControllerIntegrationTest` nested JSON, no flattened `equipmentId`/`equipmentName`, omit-null `platformHeight`, catalog `img` data URI;  
`HaystackRecommenderClientTest` Call 2 DTO fields.

### Requirement: Pass through Call 2 collapsed quote quantity (FR-S2B-011)

The system MUST map Haystack Call 2 `items[].quantity` onto portal `items[].quantity` without change. After upstream FR-P-013 ([haystack-fast-api PR #136](https://github.com/Heavy-Rental/haystack-fast-api/pull/136)), unit-need siblings that share parent need + `equipment.id` are already collapsed on the quote envelope: `quantity` is the duplicate count (3 copies → `3`) and `lineTotal` is the sum of grouped unit totals. Spring MUST NOT re-collapse rows, MUST NOT default omitted quantity to 1, and MUST NOT invent quantity from `lineTotal`, rental days, daily rate, or Call 1 `needsSummary`.

Jackson MUST bind `quantity` from a realistic Call 2 body that also includes unknown fields (`needId`, `mlPredictedPrice`, `equipment.extra`) and float `capacity`. Those extra fields MAY be ignored.

Portal JSON field name remains `quantity`. No new item field is required for this requirement.

Change pack: [`../../changes/2026-08-20-call2-quote-quantity-passthrough/`](../../changes/2026-08-20-call2-quote-quantity-passthrough/).

#### Scenario: Collapsed forklift quantity passes through
- GIVEN Call 2 returns four quote lines with quantities 1, 1, **3**, 1 (Haystack FR-P-013 collapse; forklift `lineTotal` 5318.4)
- WHEN the portal project-spec response is built
- THEN `items[2].quantity` is 3
- AND `items[2].lineTotal` is 5318.4
- AND the other three lines keep quantity 1

#### Scenario: Realistic FAST API JSON still binds quantity
- GIVEN Call 2 JSON for one item includes `quantity: 3`, `needId`, `mlPredictedPrice`, `equipment.extra`, and `capacity: 4200.0`
- WHEN the haystack client deserializes the recommend response
- THEN `items[0].quantity` is 3
- AND the item is not dropped because of unknown fields

#### Scenario: Portal JSON exposes collapsed quantity
- GIVEN WireMock stubs Call 2 with `quantity: 3`
- WHEN `POST /api/recommendations/project-spec` succeeds
- THEN the response JSON has `items[0].quantity` equal to 3

#### Scenario: Omitted quantity stays null
- GIVEN Call 2 omits `quantity` on an item
- WHEN the item is mapped for the portal
- THEN portal `items[].quantity` is null
- AND the system does not default it to 1

**Automated evidence (BDD JUnit scenarios):**  
`RecommenderSagaServiceTest` — DisplayName containing `(FR-P-013)`;  
`HaystackRecommenderClientTest` — realistic Call 2 JSON `quantity: 3`;  
`RecommendationControllerIntegrationTest` — portal JSON collapsed quantity (`FR-S2B-011`).

## Out of scope (this capability baseline)

- FastAPI multi-agent C/W/D roles inside Spring
- 202 Accepted + poll/SSE job API (connection track C2)
- gRPC or message queues (C3)
- Writing `recommendation_items` rows from Call 2 (portal maps quote JSON; optional later)
- Shared multi-replica FastAPI session store (Phase 5)
- React quote-card quantity UI / rental-plan cart conversion (portal still hardcodes `Qty: 1`; follow-up in `heavy-rental-react-web-portal`)
