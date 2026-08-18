# Contract: Bookings / deliveries / returns

| Field | Value |
|-------|--------|
| **Capability** | booking-delivery-return |
| **Status** | As-built |

## State machine (this API)

```text
CONFIRMED --[PATCH /api/deliveries/{id}/status]--> MOBILISED
MOBILISED --[PATCH /api/returns/{id}/status]--> COMPLETED
```

Other `BookingStatus` values are not advanced by these routes.

## `BookingResponse` (list/get/put)

```json
{
  "bookingId": 1,
  "customerName": "Alex Tan",
  "startDate": "2026-08-09",
  "endDate": "2026-08-13",
  "bookingStatus": "CONFIRMED",
  "siteAddress": "...",
  "items": [
    { "assetName": "JLG 460SJ Boom Lift", "serialNumber": "SN-..." }
  ],
  "deliveryNotes": ""
}
```

May also include amount fields when booking-create branch is present (`totalAmount` / `depositAmount` / `remainingBalance`).

## Routes

| Method | Path | Roles | Body | Success |
|--------|------|-------|------|---------|
| `GET` | `/api/bookings` | USER (own only) / ADMIN / DRIVER | — | `BookingResponse[]` |
| `GET` | `/api/bookings/{id}` | USER (own only, else `403`) / ADMIN / DRIVER | — | `BookingResponse` |
| `PUT` | `/api/bookings/{id}` | USER (own only, else `403`) / ADMIN / DRIVER | `BookingUpdateRequest` (`siteAddress` MUST end with a 6-digit postal code or `400 validation_failed`) | `BookingResponse` |
| `GET` | `/api/deliveries` | ADMIN / DRIVER only | — | `DeliveryItemResponse[]` |
| `PATCH` | `/api/deliveries/{id}/status` | ADMIN / DRIVER only | `{ "bookingStatus": "MOBILISED" }` | `DeliveryItemResponse` |
| `GET` | `/api/returns` | ADMIN / DRIVER only | — | `ReturnItemResponse[]` (incl. `returnNotes`) |
| `PATCH` | `/api/returns/{id}/status` | ADMIN / DRIVER only | `{ "bookingStatus":"COMPLETED", "returnNotes"? }` | `ReturnItemResponse` |

Errors: shared `{ "error", "message" }` — `400` invalid transition/enum or `validation_failed` (bad `siteAddress`), `403` role/ownership denied, `404` missing.

`POST /api/bookings` (create): `siteAddress` postal-code rule on `CreateBookingRequest`. With `rentalPlanId`, items/dates are ignored and the booking is derived from the plan — [`../../rental-plan-quote/contracts/checkout.md`](../../rental-plan-quote/contracts/checkout.md). Direct create uses inclusive day count (FR-BDR-010). Extra `409` codes: `quote_not_ready`, `quote_expired`.
