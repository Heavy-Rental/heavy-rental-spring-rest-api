# Tasks: pricing-postal-distance

One PR, one commit per numbered task below. Stop after each commit for human review before starting the next. Items 4 and 9 are hard human-action blockers (⛔) — later work that needs real OneMap credentials waits on them; everything else is ordinary code/test work.

## Implementation

- [x] 1. Proposal + design (this doc set) — `openspec/changes/pricing-postal-distance/{proposal.md,design.md,tasks.md}`
- [ ] 2. `data.sql`: normalize every seeded `Asset.location` to `"Tuas"` (replaces 18 distinct free-text values), matching the single-fixed-origin-postal-code decision
- [ ] 3. `onemap.email`/`onemap.password` placeholders in `application.properties`, Stripe-block pattern (non-functional defaults, real values never committed)
- [ ] 4. ⛔ **Human action** — register a OneMap account (onemap.gov.sg); add real `ONEMAP_EMAIL`/`ONEMAP_PASSWORD` to the local `.env` (workspace root) and as GitHub Environment secrets for CI. Not a commit.
- [ ] 5. `client/onemap/` package: `OneMapProperties`, `Coordinates`, `OneMapException`, token/search DTOs, `OneMapAuthService`, `OneMapClient`, `OneMapClientConfig`; rest of `onemap.*` properties (base-url/timeouts/resilience) in `application.properties`; `OneMapClientTest` + `OneMapCircuitBreakerTest` + `OneMapAuthServiceTest` (WireMock/unit only — no real OneMap calls, so this doesn't actually need task 4 to be done first, just convenient to sequence after)
- [ ] 6. `util/PostalCodeUtil`, `service/DistanceService` (+ `pricing.origin-postal-code`, `pricing.distance-lookup-enabled` on `PricingProperties`), `DistanceServiceTest` (Mockito)
- [ ] 7. Wire `DistanceService` into `DynamicPricingService.fetchResults()`; `RentalPlanService.create()` populates `sitePostalCode`; update `DynamicPricingServiceTest` (mock `DistanceService`, 3-arg... now 4-arg `PricingProperties`) and `RentalPlanServiceTest`
- [ ] 8. `PostalCodeValidationResponse`, `PostalCodeService`, `PostalCodeController`, `PostalCodeControllerIntegrationTest` (WireMock + MockMvc)
- [ ] 9. `contracts/postal-code-validation.md` (frontend handoff doc) + Postman collection entry for `GET /api/postalCodes/{postalCode}`
- [ ] 10. ⛔ **Manual end-to-end verification** (needs real credentials from task 4): `mvn test` full suite green; `curl` the validation endpoint for a valid/invalid/malformed postal code against real OneMap; point `onemap.base-url` at an unreachable host and confirm both the validation endpoint (`503`) and `POST /api/rentalPlans/{id}/quote` (falls back to default distance, does not fail) behave correctly; via Postman, confirm a real quote sends a non-`20.0` `distance_km` and that a repeat quote for the same plan hits `OneMapClient`'s cache (no second OneMap call in logs)
- [ ] 11. Mark all tasks done; open the PR

## Docs

- [x] proposal + design
- [ ] `contracts/postal-code-validation.md` (task 9)
- [ ] Living SoT update (`../../specs/rental-plan-quote/`) after implement + verification — archive this change like `dynamic-plan-quote-pricing` was
