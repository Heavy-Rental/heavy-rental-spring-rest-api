# Tasks: dynamic-plan-quote-pricing

## Implementation

- [x] 1. Proposal + design + delta requirements
- [ ] 2. `pricing.*` / `haystack.timeouts.pricing-read` / `haystack.resilience.bulkhead-pricing-max-concurrent` config in `HaystackProperties` + `application.properties`
- [ ] 3. DTOs: `PricingQuoteRequest(Item)`, `PricingQuoteResponse(Item)` matching the upstream wire contract (design.md)
- [ ] 4. `HaystackPricingClient` + CB/bulkhead/retry beans in `HaystackClientConfig`
- [ ] 5. `DynamicPricingService` (batch call + per-item fallback to `DefaultPricingClient`)
- [ ] 6. Wire into `RentalPlanService.requestQuote()` behind `pricing.dynamic-enabled`
- [ ] 7. `HaystackPricingClientTest` (WireMock: happy path, per-item error, circuit open)
- [ ] 8. `RentalPlanServiceTest`: flag off unchanged; flag on + success; flag on + upstream failure falls back, still `200`
- [ ] 9. Archive this change into `openspec/specs/rental-plan-quote/` + `spring-proxy-endpoints/` when as-built and flag defaults to `true` in an environment
- [ ] 10. Update `spring-proxy-endpoints` route summary table row for `POST /api/rentalPlans/{id}/quote` from "design-only" to "as-built (flagged)"

## Docs

- [x] proposal + design + delta requirements
- [ ] Living SoT update after implement + flag rollout decision
