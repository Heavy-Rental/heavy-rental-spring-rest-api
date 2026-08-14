# Delta: booking-delivery-return (checkout conversion)

## ADDED Requirements

### Requirement: FR-BDR-009 Plan-backed booking create

When `POST /api/bookings` includes `rentalPlanId`, the system MUST:

1. Load the plan; missing or not owned by the caller → `404` (not `403`).
2. Status ≠ `QUOTED` → `409` with `error` = `quote_not_ready`.
3. Quote age (`now - plan.updatedAt`) > 24 hours → `409` with `error` = `quote_expired`.
4. Derive booking items, dates, and `totalAmount` from the plan’s `RentalPlanRecord`s and frozen total — MUST NOT recompute from request `items` / dates and MUST NOT invent rates.
5. Re-check asset availability overlap before persist (plans do not hold availability).
6. Persist booking + items and set the plan `CONVERTED` in the same transaction.
7. Keep `siteAddress` required (FR-BDR-008). Request `items`/`startDate`/`endDate` MAY be sent and MUST be ignored.

`quote_not_ready` and `quote_expired` MUST NOT be collapsed to generic `conflict`.

#### Scenario: Checkout from quoted plan
- GIVEN the caller owns a QUOTED plan quoted within 24 hours
- WHEN they POST `/api/bookings` with that `rentalPlanId`
- THEN `201` booking `totalAmount` equals the plan’s quoted total
- AND plan status is `CONVERTED`

#### Scenario: Other customer’s plan
- GIVEN `rentalPlanId` belongs to another customer
- WHEN checkout is posted
- THEN `404`

#### Scenario: Unquoted plan
- GIVEN the plan is `DRAFT`
- WHEN checkout is posted
- THEN `409` `quote_not_ready`

#### Scenario: Stale quote
- GIVEN the plan is `QUOTED` and `updatedAt` is older than 24 hours
- WHEN checkout is posted
- THEN `409` `quote_expired`
- AND the plan is not converted

## MODIFIED Requirements

### Requirement: FR-BDR-010 Inclusive day count on direct booking create

When `rentalPlanId` is absent, `POST /api/bookings` MUST price with inclusive days `ChronoUnit.DAYS.between(start, end) + 1`, the same convention as `DefaultPricingClient` / rental-plan line items.

#### Scenario: Direct booking day math matches quote
- GIVEN start `2026-09-01` and end `2026-09-05` and a known `baseDailyRate`
- WHEN a booking is created without `rentalPlanId`
- THEN `totalAmount` uses 5 days, not 4
