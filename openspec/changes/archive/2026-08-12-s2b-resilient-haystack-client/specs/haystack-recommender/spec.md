# Delta for Haystack Recommender (Spring)

Change: `s2b-resilient-haystack-client`  
Type: **ADDED** capability (as-built against Feasibility_Study_Spring v2)

## Purpose

Introduce Spring’s resilient haystack recommender client, saga, and portal REST for Call 1 (ingest), Call 2 (recommend / quote), and Call 3 (chatbot Q&A).

## ADDED Requirements

### Requirement: FR-S2B-001 Outbound client with per-operation timeouts

The system MUST call haystack-fast-api over HTTP using a configurable RestClient with separate connect and read timeouts for health, recommend (Call 2), Q&A (Call 3), and ingest (ingest longest, health shortest).

#### Scenario: Ingest uses long read timeout
- GIVEN haystack ingest is configured with a long read timeout
- WHEN the client performs Call 1
- THEN the request is aborted if the read timeout elapses
- AND a timeout error is surfaced to the caller (not a silent hang)

### Requirement: FR-S2B-002 Circuit breaker and bulkhead

The system MUST protect haystack calls with a circuit breaker and bulkhead. Prefer separate bulkhead limits for ingest, recommend, and Q&A.

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

The system MUST orchestrate **Call 1 then Call 2 recommend** on portal project-spec submit: after successful Call 1, persist `ingest_id`, then call `getassetrecommendations`. On Call 2 failure, the system MUST NOT perform a second Call 1.

#### Scenario: Portal submit runs ingest then getassetrecommendations
- GIVEN an authenticated portal submit of project text
- WHEN the saga completes successfully
- THEN haystack received one Call 1 and one Call 2 with the same `ingest_id`
- AND the portal response includes Call 2 `quoteRef` / `items`

#### Scenario: Recommend 500 does not re-ingest
- GIVEN ingest succeeded and `ingest_id` was persisted
- WHEN Call 2 returns HTTP 500
- THEN the saga surfaces a retryable recommend / upstream error
- AND exactly one ingest request was made to haystack

### Requirement: FR-S2B-006 Upstream error mapping

The system MUST map haystack error bodies of shape `{"error","message"}` and HTTP status into domain/API errors. Client (4xx) errors MUST NOT be retried as a success path.

#### Scenario: FastAPI error body mapped
- GIVEN haystack returns HTTP 500 with `{"error":"internal_error","message":"..."}`
- WHEN the client maps the failure
- THEN the portal receives a structured error with a stable `error` code and human-readable `message`

### Requirement: FR-S2B-007 Portal REST for project-spec and knowledge query

The system MUST expose authenticated portal endpoints to submit a project-spec (Call 1 then Call 2 recommend) and to run chatbot Q&A (Call 3) against a stored recommendation session. The portal MUST NOT trust a client-supplied haystack `user_id`; identity comes from the JWT principal.

#### Scenario: Submit project-spec as authenticated user
- GIVEN a user with a valid access JWT
- WHEN they POST project text to `/api/recommendations/project-spec`
- THEN the system calls haystack Call 1 then Call 2 with a server-derived user identity
- AND returns a recommendation id, lean ingest summary fields, and Call 2 quote fields

#### Scenario: Knowledge query uses Call 3
- GIVEN a persisted recommendation with `ingest_id`
- WHEN the owner POSTs a query to `/api/recommendations/{id}/knowledge-query`
- THEN Call 3 is invoked with that `ingest_id` and matching haystack user identity
- AND the response includes chatbot `answer`

### Requirement: FR-S2B-008 Fail-safe when recommender is unavailable

When the circuit is open, the bulkhead rejects the call, or haystack is otherwise unavailable, the system MUST fail fast with a clear error code (`recommender_unavailable` or equivalent) and MUST NOT invent ranked assets, inventory, or daily rates.

#### Scenario: Circuit open fails safely
- GIVEN the haystack circuit breaker is open
- WHEN the portal submits a project-spec
- THEN the API returns HTTP 503 with `error` = `recommender_unavailable`
- AND no fabricated equipment list is returned

### Requirement: FR-S2B-009 Automated resilience and saga tests

The system MUST include automated tests (WireMock or equivalent) covering timeout+idempotent retry, circuit breaker open, bulkhead limit, saga no re-ingest, dual-hop quote happy path, Call 3 knowledge-query path, correlation headers, and 4xx mapping. Default CI MUST NOT require a live haystack process.

#### Scenario: WireMock suite is the default CI path
- GIVEN the Spring test suite runs in CI
- WHEN haystack client and saga tests execute
- THEN they use WireMock (or equivalent) stubs
- AND do not require a live FastAPI process
