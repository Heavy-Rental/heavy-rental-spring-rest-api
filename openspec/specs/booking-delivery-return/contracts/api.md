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

| Method | Path | Body | Success |
|--------|------|------|---------|
| `GET` | `/api/bookings` | — | `BookingResponse[]` |
| `GET` | `/api/bookings/{id}` | — | `BookingResponse` |
| `PUT` | `/api/bookings/{id}` | `BookingUpdateRequest` | `BookingResponse` |
| `GET` | `/api/deliveries` | — | `DeliveryItemResponse[]` |
| `PATCH` | `/api/deliveries/{id}/status` | `{ "bookingStatus": "MOBILISED" }` | `DeliveryItemResponse` |
| `GET` | `/api/returns` | — | `ReturnItemResponse[]` (incl. `returnNotes`) |
| `PATCH` | `/api/returns/{id}/status` | `{ "bookingStatus":"COMPLETED", "returnNotes"? }` | `ReturnItemResponse` |

Errors: shared `{ "error", "message" }` — `400` invalid transition/enum, `404` missing.
