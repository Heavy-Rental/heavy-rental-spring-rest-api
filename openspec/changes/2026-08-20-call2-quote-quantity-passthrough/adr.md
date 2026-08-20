# ADR: Pass through Haystack Call 2 quote quantity (do not collapse in Spring)

| Field | Value |
|-------|--------|
| **Status** | Accepted |
| **Date** | 2026-08-20 |
| **Capability** | haystack-recommender (Call 2 portal mapping) |
| **Trace** | FR-S2B-011 |
| **OpenSpec** | [`../../specs/haystack-recommender/spec.md`](../../specs/haystack-recommender/spec.md) |
| **OpenSPDD** | [`design.md`](./design.md) |
| **Upstream** | haystack-fast-api FR-P-013 / [PR #136](https://github.com/Heavy-Rental/haystack-fast-api/pull/136) |

## Context

FR-006 on haystack expands a decomposed need with `quantity: N` into N unit-needs before ranking. Internal `RecommendationItem` MUST NOT carry `quantity`. Until PR #136, Call 2 mapped each unit-need to its own quote line with `quantity=1`, so the portal showed duplicate equipment cards.

PR #136 collapses **only on the Call 2 quote envelope**: group by parent `{base}` of `{base}__u{i}` **and** `equipment.id`; merged `quantity` is the duplicate count (3 copies → `3`); `lineTotal` is summed; `needId` becomes the parent.

Spring is the orchestrating client. `RecommendItemResponse` already has `quantity`. The question is whether Spring should:

1. Trust Haystack’s collapsed `items[].quantity`, or
2. Re-collapse (or join Call 1 `needsSummary`) if the deployed FAST API still emits `quantity: 1`.

## Decision

**Pass through Call 2 `items[].quantity` as-is.** Collapse remains an upstream quote-layer concern (FR-P-013). Spring:

1. Binds JSON `quantity` onto `RecommendItemDto`.
2. Copies it in `RecommenderSagaService.mapItems` onto `RecommendItemResponse`.
3. Leaves quantity `null` when Haystack omits it (FR-S2B-010: do not invent).
4. Does not default to 1, does not recompute from `lineTotal` / days, and does not join Call 1 needs by `needId`.

Unknown Call 2 fields (`needId`, `mlPredictedPrice`, `equipment.extra`) stay ignored until a later change maps them.

## Consequences

### Positive

- One mapping rule: portal quantity = Haystack quantity.
- No second collapse algorithm to keep in sync with `{base}__u{i}` parent extraction.
- Portal JSON shape unchanged — React can read `items[].quantity` without a new field.

### Negative / accepted

- If a deployed haystack still HTTP-serializes `quantity: 1` while in-memory objects show 3, Spring cannot recover the 3 without inventing. That is an upstream bug.
- React `QuoteResultScreen` currently hardcodes `Qty: 1` and `buildQuoteCartItems` dedupes by equipment id with implicit qty 1. Quote **totals** still work (`lineTotal` is already summed). The qty badge and “Add to rental plan” need a **React follow-up**; this ADR does not change the rental-plan API.

### Rejected alternatives

| Alternative | Why not |
|-------------|---------|
| Re-collapse in `mapItems` by `needId` + `equipment.id` | Duplicates FR-P-013; parent extraction (`split("_")`) is known-wrong upstream |
| Join Call 1 `needsSummary[].quantity` when Call 2 is 1 | Invents quantity when Haystack chose not to collapse (distinct equipment under one parent) |
| Default omitted quantity to 1 | Violates FR-S2B-010 pass-through; hides missing upstream fields |
| Derive quantity from `lineTotal / (daily × days)` | Fragile (clamping, rounding); invents a commercial field |
| Add `needId` / `mlPredictedPrice` to the portal item in this change | Not required to pass quantity; separate mapping change |
