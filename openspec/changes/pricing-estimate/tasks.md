# Tasks: pricing-estimate

## Blocked

- [ ] Resolve availability-check open question (design.md) — **required before implement**

## Implementation (after decision)

- [ ] 1. DTOs: estimate request/response records
- [ ] 2. `PricingEstimateService` using `PricingClient`
- [ ] 3. `POST /api/pricing/estimate` controller; access JWT USER/ADMIN
- [ ] 4. If option B: wire overlap query + `409`
- [ ] 5. Unit/MockMvc tests (empty items, unknown asset, happy path, optional conflict)
- [ ] 6. Archive this change into `openspec/specs/pricing-estimate/` when as-built
- [ ] 7. Update `api-index` routes row from design-only → as-built

## Docs

- [x] proposal + design + delta requirements
- [ ] Living SoT after implement
