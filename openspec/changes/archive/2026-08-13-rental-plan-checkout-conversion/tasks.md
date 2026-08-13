# Tasks: rental-plan-checkout-conversion

## Docs (this change)

- [x] proposal + design + delta requirements + frontend contract
- [x] Living SoT after implement (`rental-plan-quote`, `booking-delivery-return`, `api-index`)
- [x] Archive this change when as-built

## Implementation

- [x] 1. Stamp `RentalPlan.createdAt` on create and `updatedAt` on quote
- [x] 2. Inclusive day count on `BookingService.createBooking` (`DAYS.between + 1`)
- [x] 3. `RentalPlanConflictException` + handler (`quote_not_ready`, `quote_expired`)
- [x] 4. Plan-backed `POST /api/bookings`: owner/`QUOTED`/24h; derive items; availability re-check; `CONVERTED`
- [x] 5. `CreateBookingRequest`: ignore `items`/dates when `rentalPlanId` present; keep `siteAddress` required
- [x] 6. `RentalPlanResponse.createdAt` / `updatedAt`
- [x] 7. Item add/remove on `QUOTED` reverts to `DRAFT` and clears `totalAmount`
- [x] 8. Tests: convert success, 404 other customer, 409 not quoted, 409 expired, revert-to-DRAFT, inclusive days
- [x] 9. Re-quote of stale `QUOTED` plans (reject only `CONVERTED`)
