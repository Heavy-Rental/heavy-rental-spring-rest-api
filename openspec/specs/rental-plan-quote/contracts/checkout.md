# Contract: Rental plan checkout (portal)

| Field | Value |
|-------|--------|
| **Capability** | rental-plan-quote + booking-delivery-return |
| **Status** | **As-built** |
| **Behavior SoT** | [`../spec.md`](../spec.md) FR-RP-009 · [`../../booking-delivery-return/spec.md`](../../booking-delivery-return/spec.md) FR-BDR-009 |

## Status enum — wire format is UPPERCASE

```
"DRAFT" | "SAVED" | "QUOTED" | "CONVERTED" | "CANCELLED"
```

`SAVED` is declared but unused; treat it as valid if seen.

## `RentalPlanResponse`

Returned by create, list, get, add item, remove item, quote, cancel, and PATCH site address.

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
      "assetId": 1,
      "assetName": "CAT 320 Excavator",
      "dailyRate": 450.00,
      "subtotal": 2250.00
    }
  ],
  "updatedAt": "2026-08-13T10:30:00",
  "createdAt": "2026-08-13T09:15:00"
}
```

- `updatedAt` / `createdAt` — ISO-8601 local date-time, no offset.
- `updatedAt` is last-quoted-at when `status == "QUOTED"`. Checkout allowed when `now - updatedAt <= 24h`.
- `totalAmount` is `null` whenever `status != "QUOTED"`, including after a quoted plan reverts to `DRAFT`.

## Item add/remove on `QUOTED`

Succeeds: mutation applied, `status` → `"DRAFT"`, `totalAmount` → `null`, `updatedAt` → now.

## `PATCH /api/rentalPlans/{id}`

Sets `siteAddress` (and derived `sitePostalCode`). Body `{ "siteAddress": "…" }` — same optional/when-provided 6-digit postal rule as create. On `QUOTED`, reverts to `DRAFT` and clears `totalAmount`. `CONVERTED` → `409 already_converted`; `CANCELLED` → `409 already_cancelled`.

## `POST /api/rentalPlans/{id}/quote`

Returns `RentalPlanResponse`. Success refreshes `updatedAt`. Re-quote of `QUOTED` is allowed (stale-quote recovery). `CONVERTED` → `409`.

When `pricing.dynamic-enabled=true` (module default), line rates are refreshed from haystack `/internal/v1/pricing/quote` with per-item Spring fallback; optional `X-Correlation-Id` is forwarded.

## `POST /api/rentalPlans/{id}/cancel`

Returns `RentalPlanResponse`. Sets `status` → `"CANCELLED"`, `totalAmount` → `null`, refreshes `updatedAt`. Allowed from `DRAFT`/`SAVED`/`QUOTED`. `CONVERTED` → `409 already_converted`; already `CANCELLED` → `409 already_cancelled`.

## `POST /api/bookings` with `rentalPlanId`

```json
{
  "rentalPlanId": 55,
  "siteAddress": "20 Jurong Port Road, 619094",
  "deliveryNotes": "Site access via loading bay B"
}
```

- `items`, `startDate`, `endDate` are optional and **ignored**.
- `siteAddress` stays required (`@NotBlank` + 6-digit postal code).
- Success: `BookingResponse`; `totalAmount` equals the plan’s quoted total; status `PENDING_DEPOSIT`.
- Plan `status` → `"CONVERTED"` in the same transaction.

## Error codes

`{ "error": "<code>", "message": "<text>" }`. Branch UI on `error`.

| HTTP | `error` | When |
|------|---------|------|
| `404` | `not_found` | Plan missing or not owned |
| `409` | `quote_not_ready` | status ≠ `QUOTED` |
| `409` | `quote_expired` | `QUOTED` but `now - updatedAt > 24h` — re-quote then retry |
| `409` | `conflict` | Optimistic-lock double-submit, or overlapping booking |
| `409` | `already_converted` | Cancel or PATCH attempted on a `CONVERTED` plan |
| `409` | `already_cancelled` | Cancel or PATCH attempted on an already-`CANCELLED` plan |
| `400` | `bad_request` | No `rentalPlanId` and no items/dates |
| `400` | `bad_request` | `siteAddress` blank or missing 6-digit postal code (Bean Validation) |

## `GET /api/rentalPlans`

No active-plan filter. Client: at most one plan with `status` not in `("CONVERTED", "CANCELLED")`.

## Single-item price preview

No `POST /api/pricing/estimate` on this path (see [`../../../changes/pricing-estimate/`](../../../changes/pricing-estimate/)). Client-side: `baseDailyRate × ((end − start days) + 1)`.
