# Proposal: Real distance-based pricing via OneMap postal code geocoding

| Field | Value |
|-------|--------|
| **Change id** | `pricing-postal-distance` |
| **Status** | **Proposed — implementation in progress** |
| **Date** | 2026-08-16 |
| **Routes** | `POST /api/rentalPlans/{id}/quote` (modified), `GET /api/postalCodes/{postalCode}` (added) |

## Why

`dynamic-plan-quote-pricing` (`../dynamic-plan-quote-pricing/proposal.md`, "Open decision") shipped `distance_km` as a hardcoded constant (`pricing.default-distance-km`, `20.0`) sent to `haystack-fast-api`'s pricing model, explicitly flagging "a postal-code-based heuristic is a candidate follow-up change, not part of this one." This change is that follow-up.

Separately, the web portal's site-address form (used when creating a rental plan / booking) only validates that the entered address ends in 6 digits (`^.*\d{6}$`, format only) — it cannot tell a well-formed but fake postal code from a real Singapore one, and that check only fires on final submit, too late to usefully prompt the user while they're still filling in the form.

Both problems are solved by the same building block: a Singapore postal code → coordinates lookup via **OneMap**, the Singapore government's geocoding API (onemap.gov.sg). This change adds that lookup, uses it to compute a real straight-line (haversine) `distance_km` for pricing, and exposes it to the frontend as a real-time postal-code validation endpoint.

## What changes

- **ADDED** `client/onemap/` package: `OneMapClient` (postal code → coordinates, via OneMap's Search API), `OneMapAuthService` (email/password → cached bearer token), `OneMapException`, resilience config — mirrors `client/haystack`'s `RestClient` + programmatic Resilience4j (circuit breaker + bulkhead, no retry — see design.md) pattern.
- **ADDED** `DistanceService`: resolves `distance_km` for a rental plan by geocoding a fixed origin postal code and the plan's delivery postal code and computing haversine distance; falls back to `pricing.default-distance-km` on any failure (missing postal code, OneMap unavailable, no match) — the quote must never be blocked by this lookup, same philosophy as `DynamicPricingService`'s existing per-item fallback to `DefaultPricingClient`.
- **MODIFIED** `DynamicPricingService.fetchResults()`: `distance_km` now comes from `DistanceService.resolveDistanceKm(plan)` instead of the flat `pricing.default-distance-km` constant (which remains as the fallback value).
- **MODIFIED** `RentalPlanService.create()`: populates the existing-but-previously-unused `RentalPlan.sitePostalCode` column by extracting the trailing 6 digits of `siteAddress` at creation time, so `DistanceService` has a real postal code to geocode without re-parsing `siteAddress` on every quote.
- **ADDED** `GET /api/postalCodes/{postalCode}`: real-time validation endpoint for the web portal's site-address form, backed by the same `OneMapClient`. Three-state response (`VALID`/`INVALID`/`UNAVAILABLE`) so the frontend can distinguish "postal code doesn't exist" (block submission) from "OneMap is temporarily down" (don't block a legitimate user) — see design.md and `contracts/postal-code-validation.md`.
- **MODIFIED** `data.sql`: every seeded `Asset.location` normalized to `"Tuas"` (previously 18 distinct free-text values) so seed data reflects the single-fixed-origin-postal-code design below, instead of implying a multi-depot setup the pricing logic doesn't model.
- **ADDED** `onemap.*` config (base URL, credentials, timeouts, resilience) and `pricing.origin-postal-code` (default `629462`) in `application.properties`, `onemap.email`/`onemap.password` following the same non-functional-placeholder-default pattern already used for `stripe.api.key`.

## Decisions (locked — do not relitigate without a new proposal)

- **Straight-line (haversine) distance, not routing/road distance.** No OneMap Routing API call, no routing-specific auth scope needed.
- **Origin postal code is a single fixed constant, `629462`**, not derived per-asset. `Asset.location` turned out to hold 18 distinct free-text values, not the 4 originally assumed, and the frontend doesn't wire per-asset location into the quote flow today — per-asset/depot geocoding is out of scope for this change (see "Out of scope"). `data.sql` is normalized to match (all assets → `"Tuas"`).
- **`GET /api/postalCodes/{postalCode}` requires login**, same default as the rest of `/api/**` (`SecurityConfig`'s `anyRequest().hasAnyAuthority("ROLE_USER","ROLE_ADMIN")`) — no `SecurityConfig` change. It's a standalone endpoint (not nested under `/api/rentalPlans`) because the identical postal-code-in-`siteAddress` validation need exists in both the rental-plan and booking site-address forms (`RentalPlanCreateRequest`, `CreateBookingRequest`, `BookingUpdateRequest`).
- **Response contract**: `VALID` and `INVALID` both return HTTP `200` (distinguished by a `status` field); `UNAVAILABLE` returns `503`. This lets the frontend's normal fetch-error handling treat "OneMap is down" differently from "field is genuinely invalid" using the HTTP status alone, without inspecting the body for the common case.
- **Credentials**: `onemap.email`/`onemap.password` via env-var-backed properties (`${ONEMAP_EMAIL:...}`), same convention as `stripe.api.key`. Real values live in a local `.env` (outside the git repo) and GitHub Environment secrets for CI. AWS Secrets Manager is out of scope until EC2 deployment actually happens — nothing in this change depends on it.

## Out of scope

- Real driving/road distance (OneMap Routing API)
- Per-asset or per-depot origin geocoding
- Populating `RentalPlan.siteLatitude`/`siteLongitude` (columns exist but stay unused — `OneMapClient`'s in-memory cache already makes repeat lookups free, so persisting coordinates buys little; candidate future follow-up)
- Retrofitting `RentalPlanCreateRequest`/`CreateBookingRequest`/`BookingUpdateRequest`'s existing `@Pattern` suffix validation to call the new endpoint server-side — this change only adds the endpoint for the frontend to call proactively
- AWS Secrets Manager / EC2 deployment wiring

## Follow-on: optional `siteAddress` at plan creation

Requested by the web portal team mid-implementation (frontend wants a "Skip for now" option on the
site-address step, so an in-progress cart survives a lost browser session even before an address is
chosen — today nothing can be persisted until `siteAddress` is provided, since `POST /rentalPlans`
requires it and items can only be added to a plan that already exists). Grouped into this change
rather than a new one because it depends on and extends work already done here: `PostalCodeUtil`
and `DistanceService` were already built to treat a missing/malformed postal code defensively
(fall back to `pricing.default-distance-km`), and `RentalPlan.siteAddress`/`sitePostalCode` are
already nullable DB columns by design — relaxing creation-time validation is a natural extension
of that same tolerance, one step earlier, not a new architectural direction.

**This directly contradicts a currently as-built, documented requirement** —
`openspec/specs/rental-plan-quote/spec.md` FR-RP-008 states `siteAddress` "MUST be non-blank...
Invalid or missing address MUST return `400`." That requirement gets updated as part of this work
(see design.md), not just the Bean Validation annotation in code.

**What changes:**
- `RentalPlanCreateRequest.siteAddress` — `@NotBlank` removed, `@Pattern` kept (already tolerates
  `null`; a present-but-malformed value is still rejected exactly as before). No other production
  code changes needed — `RentalPlanService.create()`, `PostalCodeUtil.extractTrailing6Digits()`,
  and `DistanceService.resolveDistanceKm()` already handle `null` safely, verified by reading
  every call site.
- `openspec/specs/rental-plan-quote/spec.md` FR-RP-008 — relaxed wording; "missing" scenario split
  into "omitted address accepted" (new) and "malformed-but-present address still rejected"
  (unchanged behavior, reworded).

**Explicitly unchanged** (confirmed, not just asserted): `POST /rentalPlans/{id}/items` (never
touched `siteAddress`), `POST /rentalPlans/{id}/quote` (already tolerates a missing postal code —
this change predates that tolerance existing), `POST /api/bookings` (validates its own `siteAddress`
independently of `rentalPlanId`, per `CreateBookingRequest`'s own `@NotBlank`).

### Secondary, optional: `PATCH /api/rentalPlans/{id}` to set `siteAddress` later

Requested so a plan's own record can reflect the address before conversion, rather than only ever
appearing via the booking. **Note:** no existing "generic PATCH gap" for this route is tracked
anywhere in this repo's OpenSpec docs (checked) — treat this as wholly new work, not something
already planned elsewhere in this codebase.

- New `RentalPlanUpdateRequest` DTO (mirrors `BookingUpdateRequest`'s "validate only when present"
  convention) + `RentalPlanService.updateSiteAddress(...)` + `PATCH /api/rentalPlans/{id}` on
  `RentalPlanController`.
- **Locked decision**: setting `siteAddress` on a `QUOTED` plan reverts it to `DRAFT` and clears
  `totalAmount` — same precedent as add/remove item on a `QUOTED` plan (FR-RP-002/FR-RP-003) —
  because `totalAmount` was priced using `distance_km`, which depends on `siteAddress`; allowing
  the address to change without invalidating the total would leave a stale, wrong price displayed
  until the next explicit `/quote` call.
- Own new requirement (FR-RP-011) in the living spec, since this is new API surface.

## Related

- Follows up on the "Open decision" in [`../dynamic-plan-quote-pricing/proposal.md`](../dynamic-plan-quote-pricing/proposal.md)
- Living quote SoT: [`../../specs/rental-plan-quote/`](../../specs/rental-plan-quote/) — FR-RP-008 (relaxed), FR-RP-011 (new, if the optional PATCH ships)
- Proxy map: [`../../specs/spring-proxy-endpoints/`](../../specs/spring-proxy-endpoints/)
- Frontend contract: [`contracts/postal-code-validation.md`](./contracts/postal-code-validation.md) (added in task 9, see tasks.md)
- Frontend contract: [`contracts/rental-plan-site-address.md`](./contracts/rental-plan-site-address.md) — optional `siteAddress` at creation + the new `PATCH` endpoint (implemented and tested as of task 14)
- Upstream: OneMap API docs — https://www.onemap.gov.sg/apidocs/
