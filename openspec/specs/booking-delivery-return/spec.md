# Booking, Delivery & Return API — Source of Truth

## Purpose

Mobile-oriented booking list/update and delivery/return status workflow: only `CONFIRMED → MOBILISED` and `MOBILISED → COMPLETED` are advanced through this API.

**Status:** **As-built** (also documents known gaps: no ownership scope, full-replace PUT)  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** access JWT (`ROLE_USER` / `ROLE_ADMIN`)

## Requirements

### Requirement: FR-BDR-001 List and get bookings

The system MUST provide `GET /api/bookings` and `GET /api/bookings/{id}` returning `BookingResponse` including all booking items (asset name + serial). Missing id → `404`.

#### Scenario: List all bookings
- GIVEN a valid access Bearer
- WHEN `GET /api/bookings`
- THEN `200` with every booking (as-built: not filtered by caller)

### Requirement: FR-BDR-002 Update booking details without status

`PUT /api/bookings/{id}` MUST full-replace `startDate`, `endDate`, `siteAddress`, `deliveryNotes` from the body (omitted fields may become null). Status MUST NOT be changeable via this endpoint.

#### Scenario: PUT does not change status
- GIVEN an existing booking
- WHEN `PUT` with updated notes/dates
- THEN status is unchanged
- AND body fields are written as a full replace

### Requirement: FR-BDR-003 Today's deliveries

`GET /api/deliveries` MUST return bookings with `startDate == today` and status in `(CONFIRMED, MOBILISED)`, or empty array (never `404`).

### Requirement: FR-BDR-004 Delivery transition only CONFIRMED → MOBILISED

`PATCH /api/deliveries/{id}/status` MUST accept only `bookingStatus=MOBILISED` when current status is `CONFIRMED`. Any other pair → `400` with no partial write. Invalid enum → `400`. Missing booking → `404`.

#### Scenario: Illegal delivery transition rejected
- GIVEN status is not CONFIRMED
- WHEN patch requests MOBILISED
- THEN `400` and booking unchanged

### Requirement: FR-BDR-005 Today's returns

`GET /api/returns` MUST return bookings with `endDate == today` and status in `(MOBILISED, COMPLETED)`, including `returnNotes` on each item.

### Requirement: FR-BDR-006 Return transition only MOBILISED → COMPLETED

`PATCH /api/returns/{id}/status` MUST accept only `COMPLETED` from `MOBILISED`, optionally persisting `returnNotes` (blank allowed). Illegal transition → `400` with neither status nor notes written.

#### Scenario: Complete return with notes
- GIVEN MOBILISED booking and body `{ "bookingStatus":"COMPLETED", "returnNotes":"..." }`
- WHEN patch returns
- THEN status is COMPLETED and notes persisted

### Requirement: FR-BDR-007 Items list complete

Responses that include booking equipment MUST list **all** `BookingItem` rows for the booking, not a single asset only.

### Requirement: FR-BDR-008 Site address ends with a 6-digit postal code

`PUT /api/bookings/{id}` and `POST /api/bookings` `siteAddress` MUST be non-blank and MUST end with a 6-digit postal code (`^.*\d{6}$`). Leading/trailing whitespace MUST be stripped before validation. Invalid or missing address MUST return `400` with `error` = `validation_failed` before any write. The `Booking.siteAddress` column itself remains an unconstrained nullable string (seed/direct writes are not DTO-validated).

#### Scenario: PUT without postal code leaves booking unchanged
- GIVEN an existing booking
- WHEN `PUT` sends a blank `siteAddress` or one that does not end in six digits
- THEN `400` `validation_failed`
- AND the booking row is unchanged

## Known gaps (documented, not fixed here)

- List/get not ownership-scoped beyond blanket JWT roles  
- `DeliveryRecord` / `ReturnRecord` not written by these status endpoints  
- Booking create is separate (`POST /api/bookings` when present on payment branch / api-index)

## Out of scope

- Full lifecycle to CONFIRMED / CANCELLED via these routes  
- Payments  
- Plan → booking conversion (proposed: [`../../../changes/rental-plan-checkout-conversion/`](../../../changes/rental-plan-checkout-conversion/))
