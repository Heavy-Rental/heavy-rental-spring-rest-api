# Contract: Postal code validation (portal)

| Field | Value |
|-------|--------|
| **Change** | [`pricing-postal-distance`](../proposal.md) — implementation in progress |
| **Status** | As-built in Spring; **not yet consumed by the frontend** — this doc is the handoff |
| **Behavior** | [`../proposal.md`](../proposal.md) · [`../design.md`](../design.md) |

## `GET /api/postalCodes/{postalCode}`

Real-time validation for a Singapore postal code, meant to be called while the user is still
filling in a site-address form (rental plan create, booking create/update) — **before** final
submit, not instead of it. Requires the same `Authorization: Bearer <accessToken>` header as
every other `/api/**` call (no public/unauthenticated variant).

```
GET /api/postalCodes/619094
Authorization: Bearer <accessToken>
```

### `postalCode` resolves — `200`

```json
{
  "status": "VALID",
  "postalCode": "619094",
  "address": "20 JURONG PORT ROAD SINGAPORE 619094"
}
```

### `postalCode` is well-formed but doesn't exist — `200`

```json
{
  "status": "INVALID",
  "postalCode": "999999",
  "message": "No address found for this postal code"
}
```

### `postalCode` isn't 6 digits — `400`

Standard portal error shape (`{"error","message"}`, same as every other validation failure):

```json
{
  "error": "bad_request",
  "message": "Postal code must be exactly 6 digits"
}
```

### The lookup service is temporarily down — `503`

```json
{
  "status": "UNAVAILABLE",
  "postalCode": "619094",
  "message": "Postal code lookup is temporarily unavailable — you may continue"
}
```

`VALID`/`INVALID` deliberately share HTTP `200` — branch on the `status` field, not the status
code, to tell "field is genuinely invalid" apart from "lookup unavailable." `UNAVAILABLE` is a
distinct `503` specifically so this case can be told apart using the HTTP status alone, without
having to inspect the body first.

## Recommended frontend behavior

1. Call on **blur** of the postal-code input (or once 6 digits have been typed), not on every
   keystroke — debounce isn't required by the backend (`OneMapClient` caches repeat lookups), but
   avoids firing a request against a still-incomplete value.
2. `status: "VALID"` → clear any inline error, allow submission.
3. `status: "INVALID"` (or the `400` malformed case) → inline error, **block submission** until
   resolved — this was the explicit product decision for this change.
4. `503` or a network/fetch failure calling this endpoint → **do not hard-block** the user. Show a
   soft "couldn't verify right now" message if you want, or say nothing and let them proceed — the
   backend's own quote flow already tolerates an unresolved postal code (falls back to a default
   distance for pricing), so a transient outage here shouldn't stop checkout.

## No change to the existing submit payload

`siteAddress` on `RentalPlanCreateRequest` / `CreateBookingRequest` / `BookingUpdateRequest` is
unchanged — still a single free-text string ending in the 6-digit postal code, still validated
server-side by the existing `@Pattern(".*\d{6}$")`. This endpoint is purely additive, for
real-time feedback during form-fill; nothing about final submission changes.
