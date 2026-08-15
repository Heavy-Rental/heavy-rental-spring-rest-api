# Tasks: dynamic-plan-quote-pricing

## Implementation

- [x] 1. Proposal + design + delta requirements
- [x] 2. `pricing.*` / `haystack.timeouts.pricing-read` / `haystack.resilience.bulkhead-pricing-max-concurrent` config in `HaystackProperties` + `application.properties`
- [x] 3. DTOs: `PricingQuoteRequest(Item)`, `PricingQuoteResponse(Item)` matching the upstream wire contract (design.md)
- [x] 4. `HaystackPricingClient` + CB/bulkhead/retry beans in `HaystackClientConfig`
- [x] 5. `DynamicPricingService` (batch call + per-item fallback to `DefaultPricingClient`)
- [x] 6. Wire into `RentalPlanService.requestQuote()` behind `pricing.dynamic-enabled`
- [x] 7. `HaystackPricingClientTest` (WireMock: happy path, per-item error, circuit open, 4xx)
- [x] 8. `DynamicPricingServiceTest` + `RentalPlanServiceTest`: flag off unchanged; flag on + success; whole-batch and per-item fallback
- [ ] 9. Archive this change into `openspec/specs/rental-plan-quote/` + `spring-proxy-endpoints/` when as-built and flag defaults to `true` in an environment
- [ ] 10. Update `spring-proxy-endpoints` route summary table row for `POST /api/rentalPlans/{id}/quote` from "design-only" to "as-built (flagged)"

Full suite green: 96/96 (`./mvnw test`), including `RestApiApplicationTests` (full context load with the new beans).

## Docs

- [x] proposal + design + delta requirements
- [ ] Living SoT update after implement + flag rollout decision
