# Rental Plan Build & Quote — Source of Truth

## Purpose

Customer rental plan lifecycle: create (one active plan), add/remove line items (rate snapshot), quote (freeze totals), ownership-scoped access, and convert a fresh quote into a booking.

**Status:** **As-built** (including plan → booking checkout)  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md) · checkout: [`contracts/checkout.md`](./contracts/checkout.md)  
**Auth:** access JWT; plan operations ownership-scoped (`404` not `403` for non-owners)

## Requirements

### Requirement: FR-RP-001 Create plan with one active plan rule

`POST /api/rentalPlans` MUST create a `DRAFT` plan for the caller when they have no active plan (`DRAFT`/`SAVED`/`QUOTED`). A second create while active → `409`. `CONVERTED` and `CANCELLED` MUST NOT count as active (see FR-RP-010).

#### Scenario: Second active plan rejected
- GIVEN caller already has a QUOTED plan
- WHEN they POST another plan
- THEN `409 Conflict`

#### Scenario: New cart after checkout
- GIVEN the caller’s only plan is `CONVERTED`
- WHEN they POST a new plan
- THEN `201` DRAFT

### Requirement: FR-RP-002 Add item snapshots base daily rate

On `DRAFT`/`SAVED` **or `QUOTED`** plans, `POST .../items` with `assetId` MUST create a line with `dailyRate = Asset.baseDailyRate` and `subtotal = dailyRate × inclusive day count`. On a `QUOTED` plan the mutation MUST succeed, set status `DRAFT`, set `totalAmount` to null, and refresh `updatedAt`. MUST NOT return `409` solely because the plan was quoted.

#### Scenario: Quote no longer locks add
- GIVEN a QUOTED plan owned by the caller
- WHEN add item is attempted
- THEN `200` with the new line
- AND status is `DRAFT`
- AND `totalAmount` is null

### Requirement: FR-RP-003 Remove item on open plan

`DELETE .../items/{itemId}` MUST remove the line on `DRAFT`/`SAVED` **or `QUOTED`** plans owned by the caller. On `QUOTED`, the same revert-to-`DRAFT` + clear `totalAmount` + refresh `updatedAt` rules as FR-RP-002 apply. Non-owner → `404`.

#### Scenario: Quote no longer locks remove
- GIVEN a QUOTED plan with at least one item
- WHEN that item is deleted
- THEN `200`
- AND status is `DRAFT`
- AND `totalAmount` is null

### Requirement: FR-RP-004 Request quote freezes total

`POST .../quote` on a non-empty `DRAFT`/`SAVED` **or `QUOTED`** plan MUST set `totalAmount` to the sum of line subtotals, set status `QUOTED`, and set `updatedAt` to now (last-quoted-at). Empty plan → `400`. `CONVERTED` plan → `409`. Concurrent double-submit MUST be guarded (`@Version` → `409` `conflict`). Re-quoting a stale `QUOTED` plan is the recovery path for `quote_expired`.

#### Scenario: Empty plan cannot quote
- GIVEN a plan with zero items
- WHEN quote is requested
- THEN `400`

#### Scenario: Re-quote refreshes freshness window
- GIVEN a QUOTED plan whose `updatedAt` is older than 24 hours
- WHEN quote is requested again
- THEN `200` and `updatedAt` is now

#### Scenario: Converted plan cannot be re-quoted
- GIVEN a CONVERTED plan
- WHEN quote is requested
- THEN `409`

### Requirement: FR-RP-005 Ownership-scoped list and get

`GET /api/rentalPlans` MUST return only the caller's plans. Operations on another customer's plan MUST return `404` (not `403`). Responses MUST include `createdAt` and `updatedAt` as ISO-8601 local date-times (no offset). `create()` MUST stamp `createdAt`.

### Requirement: FR-RP-006 Quote is Spring-only pricing today

`POST .../quote` MUST NOT call haystack as-built. Pricing uses `PricingClient` / `DefaultPricingClient` arithmetic from snapshotted line subtotals. A FastAPI-backed `PricingClient` is design-only (see spring-proxy-endpoints).

#### Scenario: No haystack hop on quote
- GIVEN DefaultPricingClient is the only implementation
- WHEN quote runs
- THEN no HTTP call to haystack is required

### Requirement: FR-RP-007 No availability hold from plans

Adding/quoting plan items MUST NOT block equipment availability; only bookings with active statuses do. Checkout MUST re-check overlap (see FR-BDR-009).

### Requirement: FR-RP-008 Site address ends with a 6-digit postal code

`POST /api/rentalPlans` `siteAddress` MUST be non-blank and MUST end with a 6-digit postal code (`^.*\d{6}$`). Leading/trailing whitespace MUST be stripped before validation. Invalid or missing address MUST return `400` with `error` = `validation_failed` before the one-active-plan check or any persist. The `RentalPlan.siteAddress` column itself remains an unconstrained nullable string.

#### Scenario: Missing postal code rejected
- GIVEN a caller with no active plan
- WHEN they POST a plan whose `siteAddress` is blank or does not end in six digits
- THEN `400` `validation_failed`
- AND no `RentalPlan` row is created

#### Scenario: Padded valid address accepted
- GIVEN `siteAddress` is `"  20 Jurong Port Road, 619094  "`
- WHEN the plan is created
- THEN validation passes on the stripped value

### Requirement: FR-RP-009 Convert quoted plan into a booking

When `POST /api/bookings` includes `rentalPlanId`, the system MUST apply FR-BDR-009. After success the plan MUST be `CONVERTED` in the same transaction.

### Requirement: FR-RP-010 Cancel a plan

`POST .../cancel` on a plan owned by the caller MUST set status `CANCELLED`, clear `totalAmount`, and refresh `updatedAt`, regardless of the plan's current `DRAFT`/`SAVED`/`QUOTED` state. A `CONVERTED` plan MUST NOT be cancellable (`409` `already_converted`) — it already became a booking. An already-`CANCELLED` plan MUST NOT be re-cancelled (`409` `already_cancelled`). `CANCELLED` MUST NOT count as active for FR-RP-001, so cancelling frees the caller to create a new plan. Non-owner → `404`.

#### Scenario: Cancel a draft plan
- GIVEN a DRAFT plan owned by the caller
- WHEN `POST .../cancel`
- THEN `200` and status is `CANCELLED`

#### Scenario: Cancelling frees the one-active-plan slot
- GIVEN the caller's only plan is `CANCELLED`
- WHEN they POST a new plan
- THEN `201` DRAFT

#### Scenario: Converted plan cannot be cancelled
- GIVEN a CONVERTED plan
- WHEN `POST .../cancel`
- THEN `409` `already_converted`

## Out of scope

- Discounts / agreement e-sign  
- Line-item quantity column redesign  
- Haystack-backed quoting
