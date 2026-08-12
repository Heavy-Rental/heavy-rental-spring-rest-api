# Call 1 ingest response — Spring consumer guide

| Field | Value |
|-------|--------|
| **Document type** | Consumer guide (Spring / portal) |
| **Endpoint** | `POST /internal/v1/recommendations/submitprojectspecification` |
| **Version** | **1.0.0** |
| **Date** | 2026-08-12 |
| **Related** | [`wire-contract-call1-call2.md`](./wire-contract-call1-call2.md) · FR-IX-023 as-built on haystack |

---

## 1. What Call 1 is (and is not)

| Is | Is not |
|----|--------|
| Project-spec **ingest** + index + mandatory KG on FastAPI | Call 3 ranked fleet assets + ML rent prices |
| Lean **summary** for portal + handles for Call 2 | Full technical dump (`documents[]`, `kg_*` counts) |
| Saga step 1 | Multi-agent C/W/D graph (FastAPI-internal) |

Spring’s job after Call 1: **persist identity**, show summary to portal, then run Call 2 (and later Call 3) with those handles.

---

## 2. Lean success body (shipping contract)

```json
{
  "ingest_id": "ing_a1b2c3d4e5f6",
  "user_id": "user_demo",
  "user_requirement_summary": "Indoor elevated work ~8m; need scissors lift on soft clay. Budget SGD 15000. From 2026-09-01 to 2026-09-12.",
  "tentative_start_date": "2026-09-01",
  "tentative_end_date": "2026-09-12",
  "needs_summary": [
    {
      "need_id": "need_1",
      "description": "Indoor elevated work ~8m; need scissors lift on soft clay.",
      "equipment_hints": [],
      "quantity": 1
    }
  ],
  "expected_budget": {
    "amount": 15000,
    "currency": "SGD",
    "source": "extracted"
  },
  "warnings": []
}
```

### Field table

| Field | Spring action | Notes |
|-------|---------------|--------|
| `ingest_id` | **Must persist** on booking/session aggregate | Required for every Call 2 |
| `user_id` | **Must persist** / match portal user | Same value on Call 2 |
| `user_requirement_summary` | Display; optional embed inside Call 2 `query` | Not a substitute for `ingest_id` session |
| `tentative_start_date` / `tentative_end_date` | Optional display | Request dates preferred; else extract; else null |
| `needs_summary[]` | Optional display | Not fleet recommendations |
| `expected_budget` | Optional display | Null if not found — **never invent** client-side |
| `warnings[]` | Optional display / ops | Soft issues only |

### `needs_summary[]` item

| Field | Type |
|-------|------|
| `need_id` | string \| null |
| `description` | string |
| `equipment_hints` | string[] |
| `quantity` | int \| null |

### `expected_budget`

| Field | Type |
|-------|------|
| `amount` | number |
| `currency` | string \| null |
| `source` | string (e.g. `extracted`) |

---

## 3. What Spring must not do

- Invent `asset_id`, inventory, or daily rates from Call 1  
- Treat `options.include_pricing` as a budget amount  
- Re-call Call 1 when Call 2 fails (saga holds `ingest_id`)  
- Drop `ingest_id` and send only the summary string as “session”  

---

## 4. Saga handoff (minimum)

```text
Call 1 200
  → store user_id, ingest_id (+ correlation id, idempotency key optional audit)
  → optional: show summary / needs / budget / warnings on portal

Call 2
  → POST with user_id + ingest_id + query
  → on 5xx: retry Q&A only; do NOT re-ingest
```

Correlation: send `X-Correlation-Id` on Call 1 and Call 2 for log join.  
Idempotency: one `Idempotency-Key` per logical portal “submit project-spec”; reuse on Call 1 timeout retry only.

---

## 5. Document control

| Version | Date | Notes |
|---------|------|--------|
| **1.0.0** | 2026-08-12 | Spring export package; FR-IX-023 lean consumer view |
