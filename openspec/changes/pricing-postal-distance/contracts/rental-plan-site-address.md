# Contract: Rental plan site address — optional at creation, editable via PATCH (portal)

| Field | Value |
|-------|--------|
| **Change** | [`pricing-postal-distance`](../proposal.md) |
| **Status** | **As-built** — living SoT [`../../../specs/rental-plan-quote/spec.md`](../../../specs/rental-plan-quote/spec.md) FR-RP-008 / FR-RP-011. Portal consumption is a frontend follow-up. |
| **Behavior** | [`../proposal.md`](../proposal.md) ("Follow-on" section) · [`../design.md`](../design.md) |

## `POST /api/rentalPlans` — `siteAddress` becomes optional

**What's changing:** `siteAddress` is no longer required to create a plan. Everything else about
the route is unchanged.

```json
{
  "startDate": "2026-10-01",
  "endDate": "2026-10-05"
}
```
→ `201`, plan created with `siteAddress: null`. This is the "Skip for now" path.

**Still required:** `startDate`, `endDate`.

**When `siteAddress` *is* provided, validation is exactly as strict as before** — no change here:

```json
{ "startDate": "2026-10-01", "endDate": "2026-10-05", "siteAddress": "not a real address" }
```
→ `400`:
```json
{ "error": "bad_request", "message": "siteAddress: Site address must end with a 6-digit postal code, e.g. \"20 Jurong Port Road, 619094\"" }
```

**Response shape is unchanged** (`RentalPlanResponse`) — `siteAddress` was already a nullable
field in the response; a plan created via "Skip for now" just has `siteAddress: null` in it, same
representation as any other nullable field on this object (e.g. `totalAmount` pre-quote).

**Nothing else changes**, confirmed by tracing every route that touches `siteAddress`:
- `POST /rentalPlans/{id}/items` — never read `siteAddress`, unaffected.
- `POST /rentalPlans/{id}/quote` — already tolerates a missing/unresolvable postal code (falls
  back to a default pricing distance) — see [`postal-code-validation.md`](./postal-code-validation.md).
  A plan quoted with `siteAddress: null` prices normally, just without a real distance factored in.
- `POST /api/bookings` (checkout) — **unchanged and still enforces its own `siteAddress`**,
  independently of whether the originating rental plan had one. A customer created via "Skip for
  now" will be prompted for a real address at checkout regardless — this change only removes the
  early gate at cart-creation time, not the one that matters when money changes hands.

## `PATCH /api/rentalPlans/{id}` — set or change the site address later (new endpoint)

Lets a plan created without an address (or with one that needs correcting) get `siteAddress` set
on its own record, before conversion to a booking — this is what makes the "Skip for now" cart
eventually show its own address rather than only ever getting one via the booking.

```
PATCH /api/rentalPlans/55
Authorization: Bearer <accessToken>
Content-Type: application/json

{ "siteAddress": "20 Jurong Port Road, 619094" }
```

**Success** — `200`, same `RentalPlanResponse` shape as every other rental-plan route:

```json
{
  "id": 55,
  "startDate": "2026-10-01",
  "endDate": "2026-10-05",
  "siteAddress": "20 Jurong Port Road, 619094",
  "status": "DRAFT",
  "totalAmount": null,
  "items": [ /* ... */ ],
  "updatedAt": "2026-10-01T10:32:00",
  "createdAt": "2026-10-01T09:00:00"
}
```

**⚠️ Important for the UI: setting `siteAddress` on a `QUOTED` plan reverts it to `DRAFT` and
clears `totalAmount`.** Same rule already in place for adding/removing a line item on a `QUOTED`
plan (`FR-RP-002`/`FR-RP-003`) — the previously-quoted price was computed using the *old* address's
distance, so it can't be trusted once the address changes. The response above shows exactly this
case: `status` came back `"DRAFT"` and `totalAmount` came back `null` even though the plan may have
been `QUOTED` a moment before the PATCH. **If your UI is mid-checkout-flow when this happens,
treat it the same as any other silent revert-to-DRAFT (same as after adding/removing an item on a
quoted plan) — prompt the user to request a fresh quote before proceeding, don't assume the old
`totalAmount` is still valid.**

**Validation** — identical rule to `POST`: when `siteAddress` is present in the body, it must
still end in a 6-digit postal code, or:
```json
{ "error": "bad_request", "message": "siteAddress: Site address must end with a 6-digit postal code, e.g. \"20 Jurong Port Road, 619094\"" }
```
(`400`.)

**Error cases:**

| HTTP | `error` | When |
|------|---------|------|
| `404` | `not_found` | Plan missing or not owned by the caller |
| `400` | `bad_request` | `siteAddress` present but malformed |
| `409` | `already_converted` | Plan is already `CONVERTED` |
| `409` | `already_cancelled` | Plan is already `CANCELLED` |

`siteAddress` can also be explicitly set back to `null` in the PATCH body to clear an address —
same `@Pattern`-allows-null rule as `POST`, not a special case.

## Not changing as part of this endpoint

`PATCH /api/rentalPlans/{id}` only accepts `siteAddress` in this version — it does not (yet) let
you change `startDate`/`endDate` or anything else about the plan. If that's needed later, it's a
separate ask, not assumed here.
