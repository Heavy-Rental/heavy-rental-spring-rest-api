# Tasks: Call 2 quote quantity pass-through

- [x] Record OpenSpec FR-S2B-011 (change delta + living haystack-recommender spec).
- [x] Record OpenSPDD REASONS (`design.md`) and update S2b canvas quantity / safeguards.
- [x] Record ADR (`adr.md`): pass-through, do not collapse in Spring.
- [x] Keep `RecommenderSagaService.mapItems` as `i.quantity()` with no default.
- [x] `RecommenderSagaServiceTest` — quantities 1, 1, 3, 1 (PR #136 dump).
- [x] `HaystackRecommenderClientTest` — realistic FAST API JSON `quantity: 3` plus unknown `needId` / `mlPredictedPrice` / `extra`.
- [x] `RecommendationControllerIntegrationTest` — portal `$.items[0].quantity == 3`.
- [x] Portal contract + DOCUMENTATION: quantity MAY be greater than 1 after FR-P-013.
- [x] Test inventory + Feasibility wire notes.
- [x] Run focused Maven tests (`RecommenderSagaServiceTest`, `HaystackRecommenderClientTest`, `RecommenderSagaWireMockTest`, `RecommendationControllerIntegrationTest`).
