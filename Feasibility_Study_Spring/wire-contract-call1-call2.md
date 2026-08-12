# Wire contract — Spring → haystack-fast-api (Call 1 / Call 2 / health)

| Field | Value |
|-------|--------|
| **Document type** | Integration contract (Spring-facing) |
| **Version** | **1.0.0** |
| **Date** | 2026-08-12 |
| **Status** | Aligns with haystack OpenSpec as-built (S1a–S1e + S2a) |
| **Base URL (local)** | `http://localhost:8000` |
| **Auth** | None yet — private network only; not browser-facing |
| **Package** | [`README.md`](./README.md) |

---

## Endpoint inventory (live)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Liveness / readiness |
| `/internal/v1/recommendations/submitprojectspecification` | POST | **Call 1** — ingest project-spec (index + KG); lean summary body |
| `/internal/v1/recommendations/project-knowledge/getassetrecommendations` | POST | **Call 2** — project-knowledge Q&A (not ranked fleet assets) |

**Do not use** older public-style paths such as `/api/v1/recommendations/from-project-spec` or `/api/v1/recommendations/project-knowledge/query` as the live Spring→haystack contract. Those names may appear in outdated docs; **normative paths are `/internal/v1/...` above.**

Optional (out of S2b saga): `POST /internal/v1/pricing/quote` — pricing service only; not Call 1–2.

---

## Common conventions

### Content-Type

| Call | Content-Type |
|------|----------------|
| Health | — |
| Call 1 JSON | `application/json` |
| Call 1 file | `multipart/form-data` |
| Call 2 | `application/json` |

### Error body (all routes)

```json
{"error": "<code>", "message": "<human-readable string>"}
```

| HTTP | `error` code | Spring guidance |
|------|--------------|-----------------|
| 400 / 422 | `bad_request` | Fix request; **do not** retry as success |
| 404 | `not_found` | e.g. missing Call 2 session |
| 409 | `conflict` | Map if seen; rare on C1 path |
| 500 | `internal_error` | Transient / alertable; may retry with care |

### Resilience headers (S2a as-built on FastAPI)

| Header | Required | When | Notes |
|--------|----------|------|--------|
| `Idempotency-Key` | **yes for production retry** | Every **Call 1** POST | UUID per **logical** portal submit. Scoped with `user_id` on FastAPI. Successful **200** replayed (same `ingest_id`). **4xx/5xx not cached**. Reuse same key on timeout retry — **never rotate key on retry**. |
| `X-Correlation-Id` | recommended | **All** haystack calls | Logged + **echoed** by FastAPI; server mints UUID if omitted. Propagate from gateway/MDC. |
| `traceparent` | optional | All calls | W3C Trace Context if OTel/Micrometer present |

**Retry guidance:** Spring MAY retry **5xx** and transport timeouts on ingest with the **same** `Idempotency-Key`. **4xx** = client/input problem — fix before reusing a key (or use a new key for a new logical project-spec).

**Limit:** FastAPI idempotency store is **process-local** (not multi-replica shared). See [`s2a-haystack-dependency.md`](./s2a-haystack-dependency.md).

---

## `GET /health`

No body. Probe for load balancer / client optional preflight.

**Response `200` (example):**

```json
{ "status": "ok", "database": "up" }
```

| Field | Notes |
|-------|--------|
| `status` | `"ok"` \| `"degraded"` |
| `database` | `"up"` \| `"down"` — process may be up when DB is down |

---

## Call 1 — `POST /internal/v1/recommendations/submitprojectspecification`

### Request — JSON

| Field | Type | Required | Notes |
|-------|------|----------|--------|
| `user_id` | string | **yes** | Tenant / session identity |
| `user_name` | string | no | Audit only |
| `project_text` | string | one source | Non-empty if no file |
| `start_date` / `end_date` | date | no | `YYYY-MM-DD`; echoed as `tentative_*` when set |
| `options.include_pricing` | bool | no | Future flag — **not** a budget amount |

```json
{
  "user_id": "user_demo",
  "user_name": "Demo User",
  "project_text": "Indoor elevated work ~8m; need scissors lift on soft clay. Budget SGD 15000.",
  "start_date": "2026-09-01",
  "end_date": "2026-09-12"
}
```

### Request — multipart

| Field | Required | Notes |
|-------|----------|--------|
| `user_id` | **yes** | Form field |
| `file` and/or `project_text` | one non-empty | File types per haystack MIME map (txt, md, pdf, docx, csv, json, xlsx, …) |
| `start_date` / `end_date` | no | Same as JSON |
| `user_name` | no | |

### Headers

```http
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
X-Correlation-Id: spring-req-abc123
```

### Success `200` — lean body (FR-IX-023 as-built)

See [`call1-ingest-response-for-spring.md`](./call1-ingest-response-for-spring.md) for field semantics.

| Field | Type | Spring must |
|-------|------|-------------|
| `ingest_id` | string | **Persist** — Call 2 handle |
| `user_id` | string | **Persist** / verify |
| `user_requirement_summary` | string | Optional display / embed in Call 2 prompt |
| `tentative_start_date` | date \| null | Optional portal display |
| `tentative_end_date` | date \| null | Optional portal display |
| `needs_summary` | array | Optional portal display |
| `expected_budget` | object \| null | Optional; never invent client-side |
| `warnings` | string[] | Soft issues |

**Not on body:** ranked assets, ML daily rates, `results_by_need` (Call 3).

### Example curl

```bash
curl -s -X POST "http://localhost:8000/internal/v1/recommendations/submitprojectspecification" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "X-Correlation-Id: spring-demo-1" \
  -d '{"user_id":"user_demo","project_text":"Need scissors lift for indoor work ~8m"}'
```

---

## Call 2 — `POST /internal/v1/recommendations/project-knowledge/getassetrecommendations`

**Behaviour:** Stage-1 **project-knowledge Q&A** (markdown answer over Call 1 session). Path name is Spring-facing; this is **not** Call 3 ranked fleet + prices.

### Prerequisite

Successful Call 1 in the **same FastAPI process** (session registry is process-local until Phase 5 Pgvector). Use sticky session or single instance for Call 1→2 in C1.

### Request body

| Field | Type | Required | Notes |
|-------|------|----------|--------|
| `user_id` | string | **yes** | Same as Call 1 |
| `ingest_id` | string | **yes** | From Call 1 response |
| `query` | string | **yes** | Free-form question **or** predefined prompt (may embed `user_requirement_summary`) |
| `top_k` | int | no | `1…50` |
| `kg_artifact_path` | string | no | Reload KG if session lost; vectors empty until re-ingest |

```json
{
  "user_id": "user_demo",
  "ingest_id": "ing_a1b2c3d4e5f6",
  "query": "What excavator capacity and soil conditions are specified?"
}
```

### Success `200` (summary)

| Field | Notes |
|-------|--------|
| `answer` | Markdown synthesis |
| `sources_used` | Tool names (e.g. vector + KG) |
| `research_hits` / `graph_hits` / `tool_traces` | Debug / evidence |

**Saga rule:** Call 2 **5xx** must **not** trigger a second Call 1. Retry Q&A only; hold stored `ingest_id`.

---

## Ops limits (document in Spring runbook)

| Limit | Guidance |
|-------|----------|
| Max upload size | Align **gateway + Spring codec + FastAPI/proxy** to the **same** number |
| Ingest read timeout | Long (120–300s+); measure p95 |
| Q&A read timeout | Medium (30–60s) |
| Health read timeout | Short (2–5s) |
| Sticky / single instance | Required for Call 2 until shared session (Phase 5) |

---

## Document control

| Version | Date | Notes |
|---------|------|--------|
| **1.0.0** | 2026-08-12 | Spring export package; `/internal/v1` live paths + S2a headers |
