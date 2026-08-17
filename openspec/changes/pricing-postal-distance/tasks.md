# Tasks: pricing-postal-distance

One PR, one commit per numbered task below. Stop after each commit for human review before starting the next. Items 4 and 9 are hard human-action blockers (⛔) — later work that needs real OneMap credentials waits on them; everything else is ordinary code/test work.

## Implementation

- [x] 1. Proposal + design (this doc set) — `openspec/changes/pricing-postal-distance/{proposal.md,design.md,tasks.md}`
- [x] 2. `data.sql`: normalize every seeded `Asset.location` to `"Tuas"` (replaces 18 distinct free-text values), matching the single-fixed-origin-postal-code decision
- [x] 3. `onemap.email`/`onemap.password` placeholders in `application.properties`, Stripe-block pattern (non-functional defaults, real values never committed)
- [x] 4. ⛔ **Human action** — register a OneMap account (onemap.gov.sg); add real `ONEMAP_EMAIL`/`ONEMAP_PASSWORD` to the local `.env` (workspace root) and as GitHub Environment secrets for CI. Not a commit.
- [x] 5. `client/onemap/` package: `OneMapProperties`, `Coordinates`, `OneMapException`, token/search DTOs, `OneMapAuthService`, `OneMapClient`, `OneMapClientConfig`; rest of `onemap.*` properties (base-url/timeouts/resilience) in `application.properties`; `OneMapClientTest` + `OneMapCircuitBreakerTest` + `OneMapAuthServiceTest` (WireMock/unit only — no real OneMap calls, so this doesn't actually need task 4 to be done first, just convenient to sequence after). Also required a small fix to the pre-existing `HaystackClientConfig` — adding a second unqualified `RestClient.Builder` bean broke Spring's ability to disambiguate it from `haystackRestClientBuilder`, fixed with explicit `@Qualifier`s on both sides.
- [x] 6. `util/PostalCodeUtil`, `service/DistanceService` (+ `pricing.origin-postal-code`, `pricing.distance-lookup-enabled` on `PricingProperties`), `DistanceServiceTest` (Mockito), `PostalCodeUtilTest`. Also fixed the 2 existing `new PricingProperties(...)` call sites in `DynamicPricingServiceTest` for the new arity (compile-breaking otherwise; the deeper "mock DistanceService" rework is still task 7).
- [x] 7. Wire `DistanceService` into `DynamicPricingService.fetchResults()`; `RentalPlanService.create()` populates `sitePostalCode`; update `DynamicPricingServiceTest` (new `@Mock DistanceService`, new test proving the resolved distance flows into the outbound `PricingQuoteRequest`) and `RentalPlanServiceTest` (new test proving `create()` extracts `sitePostalCode` from `siteAddress`)
- [x] 8. `PostalCodeValidationResponse`, `PostalCodeService`, `PostalCodeController`, `PostalCodeControllerIntegrationTest` (WireMock + MockMvc) — 5 scenarios: unauthenticated 401, valid postal code 200, no-match 200, malformed 400 (never calls OneMap), OneMap-down 503
- [x] 9. `contracts/postal-code-validation.md` (frontend handoff doc) + Postman collection entry (new "9. Postal Codes" folder + `postalCode` variable) + `postman/README.md` updates. Also added a discoverability breadcrumb row to `openspec/specs/api-index/contracts/routes.md`'s "Design-only / not built" table (implemented but not yet archived/frontend-consumed) — this was flagged as worth doing back when we discussed contract-file upkeep, hadn't been done yet.
- [ ] 10. ⛔ **Manual end-to-end verification** (needs real credentials from task 4): `mvn test` full suite green; `curl` the validation endpoint for a valid/invalid/malformed postal code against real OneMap; point `onemap.base-url` at an unreachable host and confirm both the validation endpoint (`503`) and `POST /api/rentalPlans/{id}/quote` (falls back to default distance, does not fail) behave correctly; via Postman, confirm a real quote sends a non-`20.0` `distance_km` and that a repeat quote for the same plan hits `OneMapClient`'s cache (no second OneMap call in logs)
- [ ] 11. Mark all tasks done; open the PR

## Docs

- [x] proposal + design
- [x] `contracts/postal-code-validation.md` (task 9)
- [ ] Living SoT update (`../../specs/rental-plan-quote/`) after implement + verification — archive this change like `dynamic-plan-quote-pricing` was
