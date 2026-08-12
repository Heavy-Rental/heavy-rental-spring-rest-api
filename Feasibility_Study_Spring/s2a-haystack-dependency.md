# S2a dependency — haystack-fast-api (FastAPI half of C1)

| Field | Value |
|-------|--------|
| **Document type** | Dependency note for Spring S2b |
| **Version** | **1.0.0** |
| **Date** | 2026-08-12 |
| **Status** | **S2a as-built** on haystack (2026-08-12) |
| **Sibling Spring plan** | [`phase2-s2b-spring-implementation-plan.md`](./phase2-s2b-spring-implementation-plan.md) |

---

## 1. Why Spring cares

Without server-side idempotency, a Spring **timeout retry** of Call 1 can **double-index** the same project-spec (two `ingest_id`s, wasted work, confused sessions).

S2a on haystack makes Call 1 **safe to retry** when Spring reuses the same `Idempotency-Key`.

---

## 2. What S2a shipped (FastAPI)

| Item | Behaviour |
|------|-----------|
| **FR-IX-024** `Idempotency-Key` | Optional header; scoped with `user_id`; successful lean **200** stored process-locally and **replayed** (same `ingest_id`); **4xx/5xx not cached**; concurrent same key uses **single-flight** |
| **FR-IX-025** correlation | Optional `X-Correlation-Id` (mint if missing); log + **echo**; optional `traceparent` logged |
| Error body | Unchanged `{"error","message"}` |
| FR-IX-023 body | Unchanged lean summary fields |

**Hard limit:** store is **process-local memory** (optional TTL, default 24h via `IDEMPOTENCY_TTL_SECONDS`). **Not multi-replica shared.** Multiple FastAPI pods without a sticky/shared store can still double-index across instances.

---

## 3. Headers Spring must send

| Header | Call 1 | Call 2 / health |
|--------|--------|-----------------|
| `Idempotency-Key` | **Yes** (every logical submit; **reuse on retry**) | N/A |
| `X-Correlation-Id` | Yes (recommended) | Yes |
| `traceparent` | Optional | Optional |

Rules:

1. Generate **one** UUID per portal “submit project-spec” at saga start.  
2. On timeout / 5xx retry of **ingest**, send the **same** key — do not mint a new one.  
3. Do not reuse a key for a **different** logical project-spec.  
4. **4xx** → fix input; do not treat as successful cache hit.

---

## 4. Join gate (production retries)

```text
S2a (haystack) — as-built     ── parallel OK ──  S2b client timeouts (Spring)
         \                                              /
          \__________ join before production ingest retries __________/
                                    │
                                    ▼
                    Enable aggressive retry + full saga in prod
```

| Work | Parallel? | Prod ingest retry? |
|------|-----------|---------------------|
| Spring timeouts + WireMock | Yes | No until S2a available |
| Spring CB / bulkhead | Yes | No until S2a for **ingest** retry |
| Correlation headers alone | Yes | Safe anytime (logging) |
| **Ingest retry with key** | — | **Only with S2a live** |

---

## 5. Session affinity (Call 1 → Call 2)

Call 2 uses a **process-local** project-knowledge session registered on successful Call 1.

| C1 practice | Until |
|-------------|--------|
| Sticky session to one FastAPI instance **or** single instance | Phase 5 Pgvector / shared session |
| Persist `ingest_id` in Spring regardless | Always |

S2a idempotency does **not** replace session affinity for Call 2.

---

## 6. How to verify FastAPI S2a

If you have the haystack repo:

- Plan runbook: `Feasibility_Study/phase2-s2a-haystack-implementation-plan.md` §7  
- Tests: `pytest tests/test_ingest_idempotency.py tests/test_correlation_middleware.py`  
- Manual: double POST same `Idempotency-Key` → same `ingest_id`  

Quick curl (against running haystack):

```bash
KEY=$(uuidgen)
BODY='{"user_id":"user_demo","project_text":"Need scissors lift"}'
curl -s -X POST "$HAYSTACK/internal/v1/recommendations/submitprojectspecification" \
  -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" -d "$BODY"
# repeat with same KEY → same ingest_id
```

---

## 7. Document control

| Version | Date | Notes |
|---------|------|--------|
| **1.0.0** | 2026-08-12 | Spring export; S2a as-built dependency |
