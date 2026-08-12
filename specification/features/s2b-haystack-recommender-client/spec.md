# Spec: S2b Haystack Recommender Client (Spring)

| Field | Value |
|-------|--------|
| **Document type** | Spec-Kit specify artifact (WHAT / WHY) |
| **Feature** | Resilient orchestration of haystack-fast-api from Spring Boot |
| **Status** | **As-built** — Feasibility v2 Call 1/2/3 |
| **Date** | 2026-08-12 |
| **OpenSpec** | FR-S2B-001 … FR-S2B-009 |

## 1. Problem

The portal needs equipment recommendations driven by project specifications. Indexing, knowledge-graph work, **recommend/quote**, and **chatbot Q&A** run in **haystack-fast-api**. Spring owns auth, domain data, and multi-call orchestration. Without a resilient client and saga:

- Long ingests hang or get killed mid-flight
- Timeout retries can **double-index** the same project-spec
- Recommend/Q&A failures incorrectly trigger a second ingest
- Outages can produce invented or empty equipment results without a clear “unavailable” signal

## 2. Goals

1. Submit a project-spec once (logical unit) and obtain durable handles (`ingest_id`, session row) plus a **Call 2 recommend quote**.
2. Run zero or more **Call 3 chatbot Q&A** turns against those handles.
3. Survive transient haystack failures with timeouts, limited retries, circuit breaking, and bulkheads.
4. Fail **safely** when the recommender is down — never invent equipment or prices.
5. Give the web portal authenticated REST to drive Call 1+2 (submit) and Call 3 (knowledge-query).

## 3. Non-goals

- Multi-agent C/W/D roles inside Spring
- Async job API (202 + poll/SSE)
- gRPC / queues
- Multipart project-file upload (JSON text only in S2b)
- Writing `recommendation_items` rows from Call 2
- Multi-replica shared FastAPI session store

## 4. Users & stories

### US-1 — Customer submits project-spec

**As a** authenticated portal user  
**I want to** submit my project description (text)  
**So that** the system indexes it (Call 1) and returns a recommend quote (Call 2) in one response

**Acceptance**

- GIVEN I am logged in with ROLE_USER or ROLE_ADMIN  
- WHEN I POST a non-empty project text to submit project-spec  
- THEN Spring calls `submitprojectspecification` then `.../getassetrecommendations`  
- AND I receive a recommendation session id, ingest summary, and Call 2 `quoteRef` / `items`  
- AND the system has stored an `ingest_id` for later Q&A  

### US-2 — Customer asks project-knowledge questions

**As a** authenticated portal user  
**I want to** ask questions about my already-submitted project-spec  
**So that** I can clarify capacity, soil, height, and other constraints before booking

**Acceptance**

- GIVEN I own a recommendation session with a stored `ingest_id`  
- WHEN I POST a free-form query for that session  
- THEN Spring calls Call 3 `.../project-knowledge/query`  
- AND I receive a markdown `answer`  
- AND a Q&A failure does **not** cause a second ingest  

### US-3 — Operator sees clear unavailability

**As a** portal user  
**I want** a clear “recommender unavailable” outcome when haystack is unhealthy or circuit-open  
**So that** I am not shown fabricated equipment

**Acceptance**

- GIVEN the recommender circuit is open or bulkhead rejects  
- WHEN I submit a project-spec  
- THEN I get a structured error (`recommender_unavailable`)  
- AND no ranked asset list is invented  

### US-4 — Safe retries

**As the** platform  
**I want** ingest retries (when enabled) to reuse one idempotency key  
**So that** haystack S2a can replay the same `ingest_id` instead of double-indexing

**Acceptance**

- GIVEN ingest retry is enabled and the first attempt times out  
- WHEN the client retries  
- THEN both attempts use the same `Idempotency-Key`  
- AND production default keeps ingest retry **disabled** until S2a is confirmed  

## 5. Success metrics (definition of done)

- All OpenSpec FR-S2B-001…009 scenarios have automated tests or explicit manual verification notes
- WireMock suite green without live FastAPI
- Portal routes documented in `SPEC-api-index.md` and living feature SPEC
- Ops: timeout matrix (including recommend vs qa), sticky-session note documented

## 6. Dependencies

| Dependency | Notes |
|------------|--------|
| Haystack S2a | Required before **production** ingest retry |
| Auth JWT | Existing access-token model |
| `AIRecommendation` entity | Extended for handles |
| Wire contract | `/internal/v1/recommendations/...` paths only; Call 2 recommend ≠ Call 3 Q&A |

## 7. Open questions (ops — non-blocking for code)

1. Production p95 ingest / recommend duration?
2. Final max project file size and allowed MIME set (multipart deferred)?
3. Is spinner UX enough for C1, or is progress UI required (→ C2)?
