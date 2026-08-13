# Contract: Portal recommendation API (Spring)

| Field | Value |
|-------|--------|
| **Capability** | haystack-recommender |
| **Status** | **As-built** |
| **Behavior SoT** | [`../spec.md`](../spec.md) |
| **Upstream haystack (read-only)** | [Heavy-Rental/haystack-fast-api](https://github.com/Heavy-Rental/haystack-fast-api) Call 1/2/3 OpenSpec contracts |
Auth: access JWT with `ROLE_USER` or `ROLE_ADMIN`. Ownership: session `user` must match JWT principal unless admin. Haystack `user_id` is **server-derived** — never trust a client-supplied haystack user id.

---

## `POST /api/recommendations/project-spec`

Orchestrates **Call 1 then Call 2 recommend**. Success body is primarily the **Call 2 quote** plus Spring session handles.

### Request (JSON)

| Field | Type | Required | Notes |
|-------|------|----------|--------|
| `projectText` | string | yes* | Non-empty project specification text |
| `startDate` / `endDate` | date | no | `YYYY-MM-DD` (tentative; not Call 2 rental window — see known gaps) |
| `userName` | string | no | Display/audit only |
| `query` | string | no | Optional Call 2 focus; else summary then default |
| `topK` | int | no | Passed to Call 2 |

\* **Also** `multipart/form-data` with form fields `projectText`, `startDate`, `endDate`, `userName`, `query`, `topK`, and optional part `file`. At least one of `file` or non-blank `projectText` is required. Max size: `haystack.max-in-memory-size` / servlet multipart limits (default 20MB).

### Response `200`

| Field | Type | Notes |
|-------|------|--------|
| `recommendationId` | long | Spring PK |
| `ingestId` | string | From Call 1; stored for Call 2/3 |
| `userRequirementSummary` | string | From Call 1 |
| `tentativeStartDate` / `tentativeEndDate` | date \| null | |
| `needsSummary` | array | Optional display; not fleet recs |
| `expectedBudget` | object \| null | Never invent client-side |
| `warnings` | string[] | Call 1 + Call 2 soft issues merged |
| `correlationId` | string | Echo for log join |
| `quoteRef` | string | **From Call 2** |
| `confidenceScore` | number \| null | **From Call 2** |
| `days` | int \| null | **From Call 2** |
| `estimatedTotal` | number \| null | **From Call 2** |
| `specSummary` | string \| null | **From Call 2** |
| `rationale` | string \| null | **From Call 2** |
| `items` | array | Nested quote lines (see below) |

**Not on submit response:** Call 3 `answer` / `sourcesUsed` (use knowledge-query).

### `items[]` element

| Field | Type | Notes |
|-------|------|--------|
| `rankOrder` | int | Rank |
| `matchScore` | number \| null | When present from haystack |
| `reason` | string \| null | Match rationale |
| `lineTotal` | number \| null | Line total |
| `quantity` | int \| null | Quantity |
| `equipment` | object \| null | Nested catalog equipment (pass-through; never invent) |

### `items[].equipment`

| Field | Type | Notes |
|-------|------|--------|
| `id` | number \| string | Catalog asset id when known |
| `name` | string | |
| `category` | string | |
| `baseDailyRate` | number \| null | May fall back from item-level rate if haystack puts rate on the item |
| `weekly` | number \| null | |
| `capacity` | int \| null | |
| `purchaseYear` | int \| null | |
| `location` | string \| null | |
| `available` | boolean \| null | |
| `img` | string \| null | |
| `desc` | string \| null | |
| `tags` | string[] | Empty list when omitted |

Upstream Call 2 may also send `mlPredictedPrice` (haystack contract); Spring maps known portal fields pass-through and MUST NOT invent rates.

---

## `POST /api/recommendations/{recommendationId}/knowledge-query`

**Call 3 only** (no ingest, no Call 2).

**Request:** `{ "query": string, "topK": int? }`  
**Response `200`:** `{ "answer": string, "sourcesUsed": string[]? }`

---

## `GET /api/recommendations/{recommendationId}`

Returns stored session summary (ids, summary, dates, budget, warnings, status, correlation, createdAt) — **not** a live haystack call.

---

## Error mapping

| Condition | HTTP | `error` |
|-----------|------|---------|
| Validation | 400 | `bad_request` |
| Not found | 404 | `not_found` |
| Not owner | 403 | `forbidden` |
| Haystack 4xx | 400/422 | map FastAPI `error` when present |
| CB open / bulkhead | 503 | `recommender_unavailable` |
| Timeout | 504 | `recommender_timeout` |
| Upstream 5xx after policy | 502/503 | `recommender_upstream_error` |

```json
{ "error": "<code>", "message": "<human-readable reason>" }
```

---

## Haystack wire (summary)

| Op | Method | Path |
|----|--------|------|
| Health | GET | `/health` |
| Call 1 Ingest | POST | `/internal/v1/recommendations/submitprojectspecification` |
| Call 2 Recommend | POST | `/internal/v1/recommendations/project-knowledge/getassetrecommendations` |
| Call 3 Q&A | POST | `/internal/v1/recommendations/project-knowledge/query` |

Normative Spring package notes: [`../../../Feasibility_Study_Spring/wire-contract-call1-call2.md`](../../../Feasibility_Study_Spring/wire-contract-call1-call2.md).  
Upstream contracts (read-only): haystack-fast-api `openspec/specs/...` (see [`AGENTS.md`](../../../AGENTS.md)).

---

## Known gaps

- Rental date range is **not** an input to Call 2 pricing (`HR-111`); `startDate`/`endDate` are tentative session fields only.
- Portal-facing summaries elsewhere MUST link here rather than re-derive field tables.
