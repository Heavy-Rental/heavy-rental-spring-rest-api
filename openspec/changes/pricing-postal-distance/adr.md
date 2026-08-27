# ADR: OneMap haversine distance + three-state postal validation

| Field | Value |
|-------|--------|
| **Status** | Accepted |
| **Date** | 2026-08-16 (as-built; living specs synced 2026-08-27) |
| **Capability** | rental-plan-quote (FR-RP-008, FR-RP-011, FR-RP-012) · postal-code-validation |
| **Trace** | FR-RP-012 · FR-PC-001 |
| **OpenSpec** | [`../../specs/rental-plan-quote/spec.md`](../../specs/rental-plan-quote/spec.md) · [`../../specs/postal-code-validation/spec.md`](../../specs/postal-code-validation/spec.md) |
| **OpenSPDD** | [`design.md`](./design.md) |
| **Upstream** | OneMap Search + token APIs (onemap.gov.sg) |

## Context

`dynamic-plan-quote-pricing` sent haystack a constant `distance_km = 20.0` and explicitly deferred geocoding. Portal site-address forms only checked that the string ended in six digits, on submit, with no way to tell a fake postal code from a real Singapore one while the user was still typing.

OneMap is the government's geocoding API. The same lookup can (a) compute a real `distance_km` for the pricing model and (b) power a real-time validation endpoint.

## Decision

1. **Straight-line (haversine) distance, not road routing.** No OneMap Routing API, no extra auth scope. Mean Earth radius 6371.0088 km.
2. **Single fixed origin postal code `629462`** (`pricing.origin-postal-code`), not per-asset `Asset.location`. Seed `Asset.location` values are normalized to `"Tuas"` so seed data does not imply a multi-depot model the quote path does not implement.
3. **Quote must never fail on geocoding.** `DistanceService` always returns a number: kill-switch off, missing/malformed `sitePostalCode`, OneMap no-match, or `OneMapException` → `pricing.default-distance-km`. Independent kill-switch `pricing.distance-lookup-enabled` (default `true`).
4. **Independent OneMap resilience domain.** Own RestClient, circuit breaker, and bulkhead. **No Retry** — a failed geocode already has a cheap fallback; retry only adds latency. Cache definitive success and confirmed not-found; never cache exceptions.
5. **`GET /api/postalCodes/{postalCode}` requires login** (USER/ADMIN catch-all). Standalone path (not nested under rental plans) because bookings have the same postal suffix.
6. **Three-state HTTP:** `VALID`/`INVALID` → `200` (branch on `status`); `UNAVAILABLE` → `503`. Malformed (not `^\d{6}$`) → `400` `bad_request` and no OneMap call.
7. **`siteAddress` optional at plan create** (portal "Skip for now"). WHEN PROVIDED, the existing 6-digit suffix rule still applies. `PATCH /api/rentalPlans/{id}` sets it later; changing address on a `QUOTED` plan reverts to `DRAFT` and clears `totalAmount` because the frozen total used `distance_km`.
8. **Do not persist `siteLatitude`/`siteLongitude`.** OneMap's in-memory cache makes repeat lookups cheap; unused columns stay unused.

## Consequences

### Positive

- Haystack sees a real (or documented fallback) `distance_km`.
- Portal can block fake postal codes during form fill without blocking checkout when OneMap is down.
- Quote and validation share one client; failure domains stay separate from Haystack.

### Negative / accepted

- Haversine understates road distance for some sites.
- Single-depot origin will be wrong if the fleet later has multiple yards.
- Frontend consumption of `GET /api/postalCodes/{postalCode}` is a portal follow-up; the Spring contract is as-built either way.

### Rejected alternatives

| Alternative | Why not |
|-------------|---------|
| OneMap Routing API | Extra scope, latency, and failure modes for a second-order input to the ML model |
| Per-asset origin from `Asset.location` | 18 distinct free-text seed values, not depots; frontend does not pass asset location into quote |
| Fail the quote when OneMap is down | Contradicts "never block checkout" from the pricing ADR |
| Cache OneMap exceptions | Turns a 5-second outage into a sticky wrong `INVALID`/`default km` |
| Nested `/api/rentalPlans/.../postalCode` | Bookings need the same check |
| `VALID` as 200 and `INVALID` as 400 | Forces the portal to treat "doesn't exist" like a transport error |
| Keep `siteAddress` required on create | Blocks the "Skip for now" cart persist the portal asked for |
