# Delta: rental-plan-quote (checkout conversion)

## MODIFIED Requirements

### Requirement: FR-RP-002 Add item snapshots base daily rate

On `DRAFT`/`SAVED` **or `QUOTED`** plans, `POST .../items` with `assetId` MUST create a line with `dailyRate = Asset.baseDailyRate` and `subtotal = dailyRate × inclusive day count`. On a `QUOTED` plan the mutation MUST succeed, set status `DRAFT`, set `totalAmount` to null, and refresh `updatedAt`. MUST NOT return `409` solely because the plan was quoted.

#### Scenario: Quote no longer locks add
- GIVEN a QUOTED plan owned by the caller
- WHEN add item is attempted
- THEN `200` with the new line
- AND status is `DRAFT`
- AND `totalAmount` is null

### Requirement: FR-RP-003 Remove item on open plan

`DELETE .../items/{itemId}` MUST remove the line on `DRAFT`/`SAVED` **or `QUOTED`** plans owned by the caller. On `QUOTED`, the same revert-to-`DRAFT` + clear `totalAmount` + refresh `updatedAt` rules as FR-RP-002 apply.

#### Scenario: Quote no longer locks remove
- GIVEN a QUOTED plan with at least one item
- WHEN that item is deleted
- THEN `200`
- AND status is `DRAFT`
- AND `totalAmount` is null

### Requirement: FR-RP-004 Request quote freezes total

`POST .../quote` on a non-empty `DRAFT`/`SAVED` plan MUST set `totalAmount` to the sum of line subtotals, set status `QUOTED`, and set `updatedAt` to now (last-quoted-at). Empty plan → `400`. Concurrent double-submit MUST be guarded (`@Version` → `409` `conflict`).

#### Scenario: Re-quote refreshes freshness window
- GIVEN a QUOTED plan whose `updatedAt` is older than 24 hours
- WHEN quote is requested again
- THEN `updatedAt` is now
- AND checkout freshness (booking delta) can succeed

## ADDED Requirements

### Requirement: FR-RP-009 Plan timestamps on responses

`RentalPlanService.create` MUST set `createdAt`. All rental-plan read/write responses MUST include `createdAt` and `updatedAt` as ISO-8601 local date-times (no offset). `updatedAt` is meaningful as last-quoted-at when status is `QUOTED`.

#### Scenario: Create stamps createdAt
- GIVEN a successful `POST /api/rentalPlans`
- WHEN the response is read
- THEN `createdAt` is present
- AND `status` is `DRAFT`

### Requirement: FR-RP-010 Converted plan frees the one-active-plan slot

After a successful plan-backed checkout (booking delta), the plan MUST be `CONVERTED`. `CONVERTED` MUST NOT count as an active plan for FR-RP-001. The customer MUST be able to `POST /api/rentalPlans` again immediately.

#### Scenario: New cart after checkout
- GIVEN the caller’s only plan is `CONVERTED`
- WHEN they POST a new plan
- THEN `201` DRAFT
- AND not `409`
