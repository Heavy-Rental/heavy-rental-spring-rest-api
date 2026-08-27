# REASONS Canvas: OneMap postal distance + validation

| Field | Value |
|-------|--------|
| **Document type** | OpenSPDD REASONS canvas |
| **Change** | `pricing-postal-distance` |
| **Status** | **As-built** |
| **Date** | 2026-08-16 (living specs synced 2026-08-27) |
| **Discipline** | Behavior diverges → update this canvas first, then code. |

**Linked:** OpenSpec FR-RP-008 / FR-RP-011 / FR-RP-012 · FR-PC-001 · ADR [`adr.md`](./adr.md) · living [`../../specs/rental-plan-quote/`](../../specs/rental-plan-quote/) · [`../../specs/postal-code-validation/`](../../specs/postal-code-validation/) · parent change [`../dynamic-plan-quote-pricing/`](../dynamic-plan-quote-pricing/)

---

## R — Requirements

Replace the constant `distance_km` sent to haystack with a real haversine distance (fixed origin postal `629462` → plan `sitePostalCode`) via OneMap. Quote MUST NOT fail when geocoding fails — fall back to `pricing.default-distance-km`.

Expose `GET /api/postalCodes/{postalCode}` for portal form-fill: `VALID`/`INVALID` at HTTP 200, `UNAVAILABLE` at 503, malformed at 400 without calling OneMap.

Follow-on (in this change): `siteAddress` optional on plan create; `PATCH /api/rentalPlans/{id}` to set it later; `QUOTED` + new address reverts to `DRAFT` and clears `totalAmount`.

### Definition of Done

- `DistanceService` returns haversine km on happy path; default km on every failure mode.
- `GET /api/postalCodes/{postalCode}`: 401 unauthenticated, 200 VALID/INVALID, 400 malformed, 503 OneMap down.
- Create without `siteAddress` → 201, `siteAddress`/`sitePostalCode` null.
- PATCH valid address on DRAFT → 200; on QUOTED → DRAFT + null total; malformed → 400; CONVERTED → 409 `already_converted`.
- Seed `Asset.location` all `"Tuas"`.
- Living specs: FR-RP-008/011/012 + `postal-code-validation` capability + routes index.

### Scope out

OneMap Routing API; per-asset origin; persisting lat/lng; replacing Bean Validation on submit with a live OneMap check; AWS Secrets Manager.

---

## E — Entities

| Concept | Representation |
|---------|----------------|
| Origin | `pricing.origin-postal-code` (default `629462`) |
| Destination | `RentalPlan.sitePostalCode` (trailing 6 digits of `siteAddress`) |
| Coordinates | `client.onemap.Coordinates(lat, lon, address)` |
| Distance | `double` km, haversine, Earth radius 6371.0088 |
| Validation DTO | `PostalCodeValidationResponse(status, postalCode, address, message)` |
| Plan PATCH | `RentalPlanUpdateRequest.siteAddress` |

OneMap wire:

```
POST /api/auth/post/getToken
Body: {"email","password"}
→ {"access_token","expiry_timestamp"}

GET /api/common/elastic/search?searchVal={postalCode}&returnGeom=Y&getAddrDetails=Y&pageNum=1
Authorization: Bearer <access_token>
→ found/results[].POSTAL, ADDRESS, LATITUDE, LONGITUDE (strings)
```

`found: 0` is not-found (`Optional.empty()` / `INVALID`), not an error.

---

## A — Approach

1. `client/onemap/` mirrors haystack client shape: properties, RestClient, independent CB + one bulkhead, **no Retry**, in-memory cache of definitive results only, `OneMapAuthService` with buffered token refresh (default 6h).
2. `PostalCodeUtil` — `isWellFormed`, `extractTrailing6Digits`.
3. `DistanceService.resolveDistanceKm` owns all fallbacks; `DynamicPricingService.fetchResults` just calls it.
4. `PostalCodeService` / `PostalCodeController` — three-state HTTP; controller never calls OneMap.
5. Optional address: drop `@NotBlank` on create; PATCH re-derives postal and reverts quote if needed.

Rejected alternatives: [`adr.md`](./adr.md).

---

## S — Structure

```text
com.heavy_rental.rest_api
  client.onemap.*                 // auth, client, config, exception, dto
  util.PostalCodeUtil
  service.DistanceService
  service.PostalCodeService
  controller.PostalCodeController // GET /api/postalCodes/{postalCode}
  dto.PostalCodeValidationResponse
  dto.RentalPlanUpdateRequest
  service.RentalPlanService#create / #updateSiteAddress
  config.PricingProperties        // originPostalCode, distanceLookupEnabled
```

---

## O — Operations

```bash
cd heavy-rental-spring-rest-api
./mvnw -Dtest=OneMapClientTest,OneMapCircuitBreakerTest,OneMapAuthServiceTest,DistanceServiceTest,PostalCodeUtilTest,PostalCodeControllerIntegrationTest,RentalPlanServiceTest,RentalPlanControllerIntegrationTest,DynamicPricingServiceTest test
```

1. Normalize seed locations; add `onemap.*` / `pricing.origin-postal-code` config.
2. OneMap client + tests (WireMock, no live OneMap).
3. `DistanceService` + wire into `DynamicPricingService`; persist `sitePostalCode` on create.
4. Validation endpoint + Postman + living contract.
5. Optional create address + PATCH + FR-RP-008/011.
6. Fold into living `postal-code-validation` + routes index + FR-RP-012.

Manual e2e against real OneMap (task 10) remains an ops checklist, not a living-spec blocker — automated suite covers the contract.

---

## N — Norms

- RFC 2119 MUST/SHALL.
- Controllers thin; OneMap CB independent of Haystack.
- Secrets via env (`ONEMAP_EMAIL` / `ONEMAP_PASSWORD`); no plaintext defaults.
- Update OpenSpec + Postman in the same change as the route.

---

## S — Safeguards

- MUST NOT fail a quote because OneMap is down or the postal code is missing.
- MUST NOT call OneMap for a non-six-digit validation path value.
- MUST NOT cache OneMap exceptions.
- MUST NOT share Haystack's circuit breaker with OneMap.
- MUST NOT derive origin from free-text `Asset.location`.
- MUST NOT persist lat/lng in this change.
- MUST NOT hard-block checkout on `503 UNAVAILABLE` (portal rule; quote already falls back).
