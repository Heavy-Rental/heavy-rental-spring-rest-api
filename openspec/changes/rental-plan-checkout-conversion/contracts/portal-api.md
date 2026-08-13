# Contract: Rental plan checkout (portal)

| Field | Value |
|-------|--------|
| **Change** | `rental-plan-checkout-conversion` |
| **Status** | **Proposed** — not as-built |
| **Behavior** | [`../proposal.md`](../proposal.md) · [`../design.md`](../design.md) |

Literal request/response for the web portal once this change is implemented. As-built quote/booking contracts remain in [`../../../specs/rental-plan-quote/`](../../../specs/rental-plan-quote/) and [`../../../specs/booking-delivery-return/`](../../../specs/booking-delivery-return/).

## Status enum — wire format is UPPERCASE

`RentalPlan.status` is Java `Enum.name()`:

```
"DRAFT" | "SAVED" | "QUOTED" | "CONVERTED"
```

Not lowercase. `SAVED` is declared but unused; treat it as valid if seen.

## `RentalPlanResponse`

Returned by create, list, get, add item, remove item, and quote.

```json
{
  "id": 55,
  "startDate": "2026-09-01",
  "endDate": "2026-09-05",
  "siteAddress": "20 Jurong Port Road, 619094",
  "status": "QUOTED",
  "totalAmount": 2250.00,
  "items": [
    {
      "id": 101,
      "assetId": 4,
      "assetName": "CAT 320 Excavator",
      "dailyRate": 450.00,
      "subtotal": 2250.00
    }
  ],
  "updatedAt": "2026-08-13T10:30:00",
  "createdAt": "2026-08-13T09:15:00"
}
```

- **New:** `updatedAt`, `createdAt` — ISO-8601 local date-time, no offset (same as `RecommendationSessionResponse.createdAt`).
- `updatedAt` is last-quoted-at when `status == "QUOTED"`. Checkout allowed when `now - updatedAt <= 24h`.
- `totalAmount` is `null` whenever `status != "QUOTED"`, including after a quoted plan reverts to `DRAFT`.

## Item add/remove on `QUOTED` — no longer `409`

`POST /api/rentalPlans/{id}/items` and `DELETE /api/rentalPlans/{id}/items/{itemId}` succeed on `QUOTED`:

- Mutation applied
- `status` → `"DRAFT"`
- `totalAmount` → `null`
- `updatedAt` → now

Response is the same `RentalPlanResponse` shape.

## `POST /api/rentalPlans/{id}/quote`

Still returns `RentalPlanResponse`. Success refreshes `updatedAt` (resets the 24-hour window).

## `POST /api/bookings` with `rentalPlanId`

```json
{
  "rentalPlanId": 55,
  "siteAddress": "20 Jurong Port Road, 619094",
  "deliveryNotes": "Site access via loading bay B"
}
```

- `items`, `startDate`, `endDate` are optional and **ignored** when `rentalPlanId` is present.
- `siteAddress` stays required (`@NotBlank` + 6-digit postal code).
- Success: existing `BookingResponse`; `totalAmount` equals the plan’s quoted total; status `PENDING_DEPOSIT`.
- Side effect: plan `status` → `"CONVERTED"` in the same transaction. `POST /api/rentalPlans` is allowed again.

## Error codes

All errors: `{ "error": "<code>", "message": "<text>" }`. Branch UI on `error`, not `message`.

| HTTP | `error` | When | New? |
|------|---------|------|------|
| `404` | `not_found` | Plan missing or not owned | No |
| `409` | `quote_not_ready` | `rentalPlanId` present, status ≠ `QUOTED` | **Yes** |
| `409` | `quote_expired` | `QUOTED` but `now - updatedAt > 24h` — send the user back to quote | **Yes** |
| `409` | `conflict` | Optimistic-lock double-submit | No |
| `400` | `bad_request` | No `rentalPlanId` and no items/dates | No |
| `400` | `validation_failed` | `siteAddress` blank or missing 6-digit postal code | No |

## `GET /api/rentalPlans` — no active-plan filter

Filter client-side: at most one plan with `status != "CONVERTED"` (FR-RP-001).

## Single-item price preview — no new endpoint

This change does **not** add `POST /api/pricing/estimate` (see [`../../pricing-estimate/`](../../pricing-estimate/) if that route is still wanted). Client-side:

```
price = asset.baseDailyRate × days
days  = (endDate - startDate in whole days) + 1
```

`2026-09-01` → `2026-09-05` is **5** days. `baseDailyRate` comes from `GET /api/equipment`.
