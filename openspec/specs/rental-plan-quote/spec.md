# Rental Plan Build & Quote — Source of Truth

## Purpose

Customer rental plan lifecycle through quote: create (one active plan), add/remove line items (rate snapshot), quote (freeze totals), ownership-scoped access.

**Status:** **As-built**  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** access JWT; plan operations ownership-scoped (`404` not `403` for non-owners)

## Requirements

### Requirement: FR-RP-001 Create plan with one active plan rule

`POST /api/rentalPlans` MUST create a `DRAFT` plan for the caller when they have no active plan (`DRAFT`/`SAVED`/`QUOTED`). A second create while active → `409`.

#### Scenario: Second active plan rejected
- GIVEN caller already has a QUOTED plan
- WHEN they POST another plan
- THEN `409 Conflict`

### Requirement: FR-RP-002 Add item snapshots base daily rate

On `DRAFT`/`SAVED` plans, `POST .../items` with `assetId` MUST create a line with `dailyRate = Asset.baseDailyRate` and `subtotal = dailyRate × inclusive day count`. On `QUOTED` plans, add → `409`.

#### Scenario: Quote locks items
- GIVEN a QUOTED plan
- WHEN add item is attempted
- THEN `409`

### Requirement: FR-RP-003 Remove item on open plan

`DELETE .../items/{itemId}` MUST remove the line on `DRAFT`/`SAVED` plans owned by the caller.

### Requirement: FR-RP-004 Request quote freezes total

`POST .../quote` on a non-empty `DRAFT`/`SAVED` plan MUST set `totalAmount` to the sum of line subtotals and status `QUOTED`. Empty plan → `400`. Concurrent double-submit MUST be guarded (`@Version` → `409`).

#### Scenario: Empty plan cannot quote
- GIVEN a plan with zero items
- WHEN quote is requested
- THEN `400`

### Requirement: FR-RP-005 Ownership-scoped list and get

`GET /api/rentalPlans` MUST return only the caller's plans. Operations on another customer's plan MUST return `404` (not `403`).

### Requirement: FR-RP-006 Quote is Spring-only pricing today

`POST .../quote` MUST NOT call haystack as-built. Pricing uses `PricingClient` / `DefaultPricingClient` arithmetic from snapshotted line subtotals. A FastAPI-backed `PricingClient` is design-only (see spring-proxy-endpoints).

#### Scenario: No haystack hop on quote
- GIVEN DefaultPricingClient is the only implementation
- WHEN quote runs
- THEN no HTTP call to haystack is required

### Requirement: FR-RP-007 No availability hold from plans

Adding/quoting plan items MUST NOT block equipment availability; only bookings with active statuses do.

## Out of scope

- Plan → Booking conversion  
- Discounts / agreement e-sign  
- Line-item quantity column redesign
