# Tasks: rental-plan-checkout-conversion

## Docs (this change)

- [x] proposal + design + delta requirements + frontend contract
- [ ] Living SoT after implement (`rental-plan-quote`, `booking-delivery-return`, `api-index`)
- [ ] Archive this change when as-built

## Implementation (later — not this PR)

- [ ] 1. Stamp `RentalPlan.createdAt` on create and `updatedAt` on quote
- [ ] 2. Inclusive day count on `BookingService.createBooking` (`DAYS.between + 1`)
- [ ] 3. `RentalPlanConflictException` + handler (`quote_not_ready`, `quote_expired`)
- [ ] 4. Plan-backed `POST /api/bookings`: owner/`QUOTED`/24h; derive items; availability re-check; `CONVERTED`
- [ ] 5. `CreateBookingRequest`: ignore `items`/dates when `rentalPlanId` present; keep `siteAddress` required
- [ ] 6. `RentalPlanResponse.createdAt` / `updatedAt`
- [ ] 7. Item add/remove on `QUOTED` reverts to `DRAFT` and clears `totalAmount`
- [ ] 8. Tests: convert success, 404 other customer, 409 not quoted, 409 expired, revert-to-DRAFT, inclusive days, deposit flow still works
- [ ] 9. Manual walk from leftover pack step 7 (create → item → quote → checkout → new plan allowed)
