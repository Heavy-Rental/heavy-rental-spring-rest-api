# Postal Code Validation — Source of Truth

## Purpose

Real-time Singapore postal-code lookup for portal site-address forms (rental plan create/PATCH and booking create/update). Backed by OneMap (onemap.gov.sg). This endpoint is additive feedback during form fill; it does **not** replace submit-time `@Pattern` on `siteAddress`.

**Status:** **As-built**  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** access JWT (`ROLE_USER` or `ROLE_ADMIN`) — default `SecurityConfig` catch-all; no public variant  
**Related:** rental-plan quote distance ([`../rental-plan-quote/spec.md`](../rental-plan-quote/spec.md) FR-RP-012) shares `OneMapClient` via `DistanceService`  
**Change pack:** [`../../changes/pricing-postal-distance/`](../../changes/pricing-postal-distance/) (proposal, REASONS, ADR)

## Requirements

### Requirement: FR-PC-001 Three-state validation

`GET /api/postalCodes/{postalCode}` MUST:

1. Reject a path value that is not exactly six digits with HTTP `400` and `{ "error": "bad_request", "message": "Postal code must be exactly 6 digits" }` — and MUST NOT call OneMap.
2. Return HTTP `200` `{ "status": "VALID", "postalCode", "address" }` when OneMap resolves the code.
3. Return HTTP `200` `{ "status": "INVALID", "postalCode", "message" }` when the code is well-formed but OneMap has no match.
4. Return HTTP `503` `{ "status": "UNAVAILABLE", "postalCode", "message" }` when OneMap is down, times out, or the circuit is open.

`VALID` and `INVALID` MUST share HTTP `200` so clients branch on `status`, not the status code, for field validity. `UNAVAILABLE` MUST be `503` so a transient outage is distinguishable from an invalid field without inspecting the body. A `503` MUST NOT be treated by this API as a reason to hard-block checkout — quote already falls back to `pricing.default-distance-km` (FR-RP-012).

#### Scenario: Well-formed existing postal code
- GIVEN a valid access Bearer and postal code `619094` that OneMap resolves
- WHEN `GET /api/postalCodes/619094`
- THEN `200` with `status` = `VALID` and a non-blank `address`

#### Scenario: Well-formed unknown postal code
- GIVEN a valid access Bearer and `999999` with no OneMap match
- WHEN `GET /api/postalCodes/999999`
- THEN `200` with `status` = `INVALID`
- AND no `address` field

#### Scenario: Malformed postal code never calls OneMap
- GIVEN a valid access Bearer and `12345`
- WHEN `GET /api/postalCodes/12345`
- THEN `400` `bad_request`
- AND OneMap is not invoked

#### Scenario: OneMap unavailable
- GIVEN OneMap's circuit is open (or a transport/timeout failure)
- WHEN `GET /api/postalCodes/619094`
- THEN `503` with `status` = `UNAVAILABLE`

### Requirement: FR-PC-002 Controller does not call OneMap

The controller MUST stay thin. Geocoding MUST go `PostalCodeService` → `OneMapClient`. The OneMap circuit breaker and bulkhead MUST be independent of Haystack's — one external system failing MUST NOT fail-fast the other.

#### Scenario: Layering
- GIVEN a validation request
- WHEN the controller handles it
- THEN it does not construct a `RestClient` call to onemap.gov.sg

### Requirement: FR-PC-003 Submit payloads stay free-text siteAddress

`siteAddress` on `RentalPlanCreateRequest` / `RentalPlanUpdateRequest` / `CreateBookingRequest` / `BookingUpdateRequest` MUST remain a single free-text string ending in six digits (when provided). This endpoint MUST NOT become a required pre-step of those writes.

#### Scenario: Plan create still validates locally
- GIVEN a present-but-malformed `siteAddress` on `POST /api/rentalPlans`
- WHEN submitted without calling this endpoint
- THEN `400` `validation_failed` from Bean Validation, unchanged

## Out of scope

- Public/unauthenticated variant  
- Driving/road distance  
- Persisting `RentalPlan.siteLatitude` / `siteLongitude`  
- Replacing Bean Validation on submit with a live OneMap check  

## Related

- [`../rental-plan-quote/`](../rental-plan-quote/)  
- [`../booking-delivery-return/`](../booking-delivery-return/)  
- [`../spring-proxy-endpoints/spec.md`](../spring-proxy-endpoints/spec.md)  
