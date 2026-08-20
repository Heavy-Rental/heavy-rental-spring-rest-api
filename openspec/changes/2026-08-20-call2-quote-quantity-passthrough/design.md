# REASONS Canvas: Pass through Call 2 collapsed quote quantity

| Field | Value |
|-------|--------|
| **Document type** | OpenSPDD REASONS canvas |
| **Change** | `2026-08-20-call2-quote-quantity-passthrough` |
| **Status** | **As-built** |
| **Date** | 2026-08-20 |
| **Discipline** | Behavior diverges → update this canvas first, then code. |

**Linked:** OpenSpec FR-S2B-011 · ADR [`adr.md`](./adr.md) · living [`../../specs/haystack-recommender/`](../../specs/haystack-recommender/) · upstream FR-P-013 ([haystack-fast-api PR #136](https://github.com/Heavy-Rental/haystack-fast-api/pull/136)) · S2b canvas [`../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md`](../../../spdd/prompt/S2b-resilient-haystack-recommender-client.md)

---

## R — Requirements

See delta [`specs/haystack-recommender/spec.md`](./specs/haystack-recommender/spec.md) (**FR-S2B-011**) and ADR [`adr.md`](./adr.md).

Portal `POST /api/recommendations/project-spec` `items[].quantity` MUST equal Haystack Call 2 `items[].quantity`. After FR-P-013, that value MAY be greater than 1 (3 collapsed forklift unit-needs → `quantity: 3`, summed `lineTotal`). Spring MUST NOT default omitted quantity to 1, MUST NOT recompute it, and MUST NOT re-collapse rows.

### Definition of Done

- FR-S2B-011 scenarios green: saga pass-through of 1 / 1 / **3** / 1; RestClient decode of realistic FAST API JSON (`needId`, `mlPredictedPrice`, `extra`, float `capacity`, `quantity: 3`); portal JSON `$.items[0].quantity == 3`.
- Omitted quantity stays `null` (existing FR-S2B-010 scenario).
- Living portal contract documents collapsed quantity.

### Scope out

Haystack collapse implementation; React qty badge / cart conversion; `needId` / `mlPredictedPrice` on the portal item DTO; `recommendation_items` persistence.

---

## E — Entities

| Concept | Representation |
|---------|----------------|
| Call 2 quote line | Haystack `RecommendQuoteItem` — `quantity`, `needId`, `lineTotal`, nested `equipment` |
| Spring inbound | `RecommendItemDto.quantity` (`Integer`, JSON `quantity`) |
| Portal quote item | `RecommendItemResponse.quantity` — same integer, or `null` when omitted |
| Collapse | Upstream only (`collapse_duplicate_equipment_quotes`); Spring sees the already-collapsed list |
| Parent need id | `{base}` of `{base}__u{i}` — Haystack rewrites `needId`; Spring currently ignores `needId` |

---

## A — Approach

Keep FR-S2B-010 nested mapping. In `RecommenderSagaService.mapItems`, copy `i.quantity()` with no default.

1. Jackson MUST bind Call 2 `quantity` even when the body also has unknown fields (`needId`, `mlPredictedPrice`, `equipment.extra`) and float `capacity`.
2. Do not join Call 1 `needsSummary` by `needId` to invent quantity if Call 2 omits it.
3. Do not multiply `mlPredictedPrice × days` to derive quantity.
4. Portal JSON field name stays `quantity` (no schema rename for React).

Rejected alternatives are in [`adr.md`](./adr.md).

---

## S — Structure

```text
com.heavy_rental.rest_api
  client.haystack.dto.RecommendItemDto     // Integer quantity
  dto.RecommendItemResponse                // Integer quantity
  service.RecommenderSagaService#mapItems  // i.quantity() pass-through
```

No new HTTP route, column, or portal field.

Tests:

| Class | FR-S2B-011 scenario |
|-------|---------------------|
| `RecommenderSagaServiceTest` | 1, 1, 3, 1 pass-through (PR #136 dump) |
| `HaystackRecommenderClientTest` | Realistic FAST API JSON `quantity: 3` |
| `RecommendationControllerIntegrationTest` | Portal `$.items[0].quantity == 3` |

---

## O — Operations

```bash
cd heavy-rental-spring-rest-api
./mvnw -Dtest=RecommenderSagaServiceTest,HaystackRecommenderClientTest,RecommenderSagaWireMockTest,RecommendationControllerIntegrationTest test
```

1. Tests first (quantity 3 + realistic JSON).
2. Keep `mapItems` pass-through; fix DTO bind only if decode fails.
3. Update living spec + portal contract + this canvas.

---

## N — Norms

- RFC 2119 MUST/SHALL in FR-S2B-011.
- Haystack values pass through (FR-S2B-010); do not invent rates or quantity.
- CamelCase portal JSON; ignore unknown Call 2 fields.
- Update OpenSpec in the same change as the tests.

---

## S — Safeguards

- MUST NOT default `quantity` to 1 when Haystack omits it.
- MUST NOT collapse (or un-collapse) Call 2 rows in Spring.
- MUST NOT invent quantity from `lineTotal`, days, or Call 1 `needsSummary`.
- MUST NOT fail the item solely because Haystack sends unknown `needId` / `mlPredictedPrice` / `extra`.
- MUST NOT treat React `Qty: 1` hardcoded UI as a Spring contract (portal follow-up).
