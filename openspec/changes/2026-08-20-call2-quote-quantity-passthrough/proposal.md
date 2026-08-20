# Proposal: Pass through Call 2 collapsed quote quantity

| Field | Value |
|-------|--------|
| **Change id** | `2026-08-20-call2-quote-quantity-passthrough` |
| **Status** | **As-built** |
| **Date** | 2026-08-20 |
| **Capability** | haystack-recommender |
| **Trace** | FR-S2B-011 (Spring) · upstream FR-P-013 |
| **Upstream** | [haystack-fast-api PR #136](https://github.com/Heavy-Rental/haystack-fast-api/pull/136) (HR-206) |
| **Route** | `POST /api/recommendations/project-spec` `items[].quantity` |

## Why

Haystack Call 2 used to emit one quote row per expanded unit-need (`need_3__u1`, `need_3__u2`, `need_3__u3`), each with `quantity: 1`. PR #136 (FR-P-013) collapses siblings that share parent need + `equipment.id` into **one** commercial line: `quantity` equals the duplicate count (3 copies → `3`), `lineTotal` is summed, `needId` is the parent `{base}`.

Spring already had `quantity` on `RecommendItemDto` / `RecommendItemResponse` and `RecommenderSagaService.mapItems` copied `i.quantity()`. Automated tests only stubbed `quantity: 1` (or `null`), so a default-to-1 or Jackson drop of the realistic FAST API body would not fail CI. The React portal already consumes `items[]` from this route.

## What changes

- **ADDED** FR-S2B-011: portal `items[].quantity` MUST equal Haystack Call 2 `items[].quantity` (including values other than 1). MUST NOT default to 1 when omitted (leave `null`). MUST NOT invent quantity from `lineTotal` / days / Call 1 `needsSummary`.
- **MODIFIED** tests: saga, RestClient, and portal MockMvc assert a collapsed `quantity: 3` payload (forklift example from PR #136).
- **UNCHANGED** portal JSON shape — no new field. Collapse itself stays in haystack (`map_recommend_to_quote`); Spring does not re-collapse.
- **UNCHANGED** rental-plan quote / cart (no `recommendation_items` write; React still maps quote → cart).

## Out of scope

- Implementing FR-P-013 collapse in this repo (upstream only).
- Mapping `needId` / `mlPredictedPrice` onto `RecommendItemResponse`.
- React `QuoteResultScreen` (still hardcodes `Qty: 1` and drops quantity when adding to the rental plan) — follow-up in `heavy-rental-react-web-portal`.
- Persisting `recommendation_items` rows from Call 2.

## Related

| Artifact | Path |
|----------|------|
| Delta spec | [`specs/haystack-recommender/spec.md`](./specs/haystack-recommender/spec.md) |
| OpenSPDD REASONS | [`design.md`](./design.md) |
| ADR | [`adr.md`](./adr.md) |
| Tasks | [`tasks.md`](./tasks.md) |
| Living SoT | [`../../specs/haystack-recommender/spec.md`](../../specs/haystack-recommender/spec.md) |
| Portal contract | [`../../specs/haystack-recommender/contracts/portal-api.md`](../../specs/haystack-recommender/contracts/portal-api.md) |
| S2b canvas | [`../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md`](../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md) |
