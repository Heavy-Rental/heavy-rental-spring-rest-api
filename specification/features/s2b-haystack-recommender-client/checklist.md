# Checklist: S2b requirements quality (Spec-Kit gate)

Reviewer-owned. Check an item **only** when a human is satisfied that the **requirement docs** are complete, clear, and consistent — not when code is done.

| # | Criterion | Pass? |
|---|-----------|-------|
| 1 | Problem, goals, and non-goals are explicit (no C2/C3/CWD creep) | [x] |
| 2 | User stories have GIVEN/WHEN/THEN acceptance criteria | [x] |
| 3 | OpenSpec FR-S2B-001…009 each have at least one scenario | [x] |
| 4 | Haystack paths are normative `/internal/v1/...` (not legacy `/api/v1/...`) | [x] |
| 5 | Call 2 = recommend quote; Call 3 = chatbot query — paths and fields distinct | [x] |
| 6 | Portal routes, auth, and error codes are specified | [x] |
| 7 | Persistence fields for `ingest_id` and audit keys are specified | [x] |
| 8 | Resilience rules include: timeouts, CB, bulkhead, same-key retry, no re-ingest on Call 2/3 fail, fail-safe no invent equipment | [x] |
| 9 | S2a production-retry join gate is documented; default ingest retry **off** | [x] |
| 10 | Test strategy lists WireMock classes and CI independence from live FastAPI | [x] |
| 11 | Spec-Kit plan, OpenSpec design, SPDD canvas, and living SPEC do not contradict each other | [x] |
| 12 | `SPEC-api-index.md` lists routes with pointer to living SPEC | [x] |
| 13 | Environment SPEC notes hybrid SDD (OpenSpec / Spec-Kit / SPDD) and `client/haystack` package | [x] |

**Gate:** All rows checked → implementation (Java) may begin / continue.

| Reviewer | Date | Notes |
|----------|------|--------|
| implement-request | 2026-08-12 | User authorized implement; Feasibility v2 Call 2/3 realign |

---

## Post-implementation converge

- [x] WireMock suite green
- [x] Exit criteria from feasibility §9 / living SPEC §11.3 satisfied
- [x] Living SPEC status **As-built** (v2.0.0 Call 2 recommend / Call 3 Q&A)
- [x] OpenSpec SoT requirements match as-built behaviour
