# Delta: pricing-estimate

## ADDED Requirements

### Requirement: FR-PE-001 Side-effect-free multi-item estimate

The system MUST expose `POST /api/pricing/estimate` that accepts a list of asset ids and a date range, returns per-item and total prices derived from the same Spring pricing source as rental-plan line items (`Asset.baseDailyRate` via `PricingClient`), and MUST NOT create or modify any persistent `RentalPlan`, `Booking`, or payment record.

#### Scenario: Estimate without plan
- GIVEN a valid access Bearer and known assets with a valid date range
- WHEN `POST /api/pricing/estimate` with those items and dates
- THEN `200` includes per-item `dailyRate`, `days`, `subtotal`, and `totalAmount`
- AND no new rental plan or booking row is written

#### Scenario: Validation errors
- GIVEN empty items or invalid/missing dates
- WHEN estimate is called
- THEN `400`

#### Scenario: Unknown asset
- GIVEN an `assetId` that does not exist
- WHEN estimate is called
- THEN `404`

### Requirement: FR-PE-002 Not a haystack proxy

The estimate route MUST NOT call haystack-fast-api as designed. Dynamic/ML pricing is out of scope for this change.

#### Scenario: No FastAPI dependency
- GIVEN haystack is unavailable
- WHEN estimate is implemented with DefaultPricingClient math only
- THEN the estimate can still succeed

### Requirement: FR-PE-003 Shared pricing math

Day count and rate/subtotal calculation MUST reuse `PricingClient.priceItem` (or equivalent shared helper) so estimate and rental-plan quote cannot drift on rounding or inclusive-day convention.

#### Scenario: Same rate source as plan items
- GIVEN asset base daily rate R and the same date window used on a plan item
- WHEN estimate prices that asset
- THEN dailyRate and day count match what `DefaultPricingClient` would produce for a plan line
