# Rental Plan Build & Quote — Source of Truth

## Purpose

Customer rental plan lifecycle: create (one active plan), add/remove line items (rate snapshot), quote (freeze totals), ownership-scoped access, convert a fresh quote into a booking, cancel, and set/correct `siteAddress`.

**Status:** **As-built** (including plan → booking checkout, flag-gated FastAPI dynamic quote pricing, OneMap distance, optional site address)  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md) · checkout: [`contracts/checkout.md`](./contracts/checkout.md)  
**Auth:** access JWT; plan operations ownership-scoped (`404` not `403` for non-owners)  
**Related changes (as-built):** [`../../changes/dynamic-plan-quote-pricing/`](../../changes/dynamic-plan-quote-pricing/) · [`../../changes/pricing-postal-distance/`](../../changes/pricing-postal-distance/)  
**Postal validation (portal):** [`../postal-code-validation/`](../postal-code-validation/)

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
- THEN `201` with the new line
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

When `pricing.dynamic-enabled=true`, line subtotals MUST be refreshed from `DynamicPricingService` (FastAPI-backed) immediately before summing, instead of trusting the subtotal snapshotted at add-item time. When the flag is `false`, or any item's dynamic price is unavailable, that item's subtotal MUST fall back to `DefaultPricingClient` (`Asset.baseDailyRate`) arithmetic — a quote MUST NOT fail solely because the pricing service is unavailable.

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

#### Scenario: Dynamic pricing applied when enabled
- GIVEN `pricing.dynamic-enabled=true` and a plan with items
- WHEN quote is requested and `haystack-fast-api`'s `/internal/v1/pricing/quote` succeeds
- THEN each line's `dailyRate`/`subtotal` reflect the FastAPI-returned price
- AND `totalAmount` is the sum of those refreshed subtotals

#### Scenario: Pricing service unavailable does not block quote
- GIVEN `pricing.dynamic-enabled=true` and `haystack-fast-api`'s pricing circuit is open
- WHEN quote is requested
- THEN the quote still succeeds (`200`)
- AND the affected item(s) use `DefaultPricingClient` (`Asset.baseDailyRate`) arithmetic instead

#### Scenario: Degraded dynamic price is used, not treated as a failure
- GIVEN `pricing.dynamic-enabled=true` and an item's result comes back with `degraded=true` but a non-null `daily_rate`/`total_price`
- WHEN quote is requested
- THEN that item's subtotal uses the returned dynamic price as-is — it does NOT fall back to `DefaultPricingClient`
- AND a `WARN` log is emitted with the plan id, item id, and `model_version`

### Requirement: FR-RP-005 Ownership-scoped list and get

`GET /api/rentalPlans` MUST return only the caller's plans. Operations on another customer's plan MUST return `404` (not `403`). Responses MUST include `createdAt` and `updatedAt` as ISO-8601 local date-times (no offset). `create()` MUST stamp `createdAt`.

### Requirement: FR-RP-006 Quote pricing source is flagged

`POST .../quote` MUST use `DynamicPricingService` when `pricing.dynamic-enabled=true`, backed by `HaystackPricingClient` calling haystack `POST /internal/v1/pricing/quote`. As-built, `application.properties` sets `pricing.dynamic-enabled=${DYNAMIC_PRICING_ENABLED:true}` — the module default is **on**. When the flag is `false`, quote pricing MUST remain Spring-only `DefaultPricingClient` arithmetic from snapshotted line subtotals, with no HTTP call to haystack.

Cart add-item (`POST .../items`) MUST remain `Asset.baseDailyRate` snapshot pricing (FR-RP-002) regardless of the flag.

#### Scenario: Flag off preserves Spring-only behavior
- GIVEN `pricing.dynamic-enabled=false`
- WHEN quote runs
- THEN no HTTP call to haystack is made
- AND `totalAmount` equals the sum of already-snapshotted line subtotals

#### Scenario: Flag on calls haystack pricing, not the recommender saga
- GIVEN `pricing.dynamic-enabled=true`
- WHEN quote runs
- THEN Spring calls `POST /internal/v1/pricing/quote` only
- AND MUST NOT trigger Call 1 ingest or Call 2 recommend

### Requirement: FR-RP-007 No availability hold from plans

Adding/quoting plan items MUST NOT block equipment availability; only bookings with active statuses do. Checkout MUST re-check overlap (see FR-BDR-009).

### Requirement: FR-RP-008 Site address is optional; when provided must end with a 6-digit postal code

`POST /api/rentalPlans` `siteAddress` is OPTIONAL — a plan MAY be created with it omitted or `null` (the "Skip for now" cart flow; see `openspec/changes/pricing-postal-distance/` "Follow-on: optional siteAddress at plan creation"). WHEN PROVIDED, it MUST be non-blank and MUST end with a 6-digit postal code (`^.*\d{6}$`). Leading/trailing whitespace MUST be stripped before validation. A present-but-invalid address MUST return `400` with `error` = `bad_request` before the one-active-plan check or any persist. `RentalPlan.siteAddress`/`sitePostalCode` remain unconstrained nullable columns. `PATCH /api/rentalPlans/{id}` (FR-RP-011) is how a plan created without an address gets one set later.

#### Scenario: Omitted address accepted
- GIVEN a caller with no active plan
- WHEN they POST a plan with `siteAddress` omitted (or explicitly `null`)
- THEN `201` and a `DRAFT` plan is created with `siteAddress: null`

#### Scenario: Malformed postal code rejected
- GIVEN a caller with no active plan
- WHEN they POST a plan whose `siteAddress` is present but blank or does not end in six digits
- THEN `400` `bad_request`
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

### Requirement: FR-RP-011 Update site address

`PATCH /api/rentalPlans/{id}` MUST set `siteAddress` on a plan owned by the caller — how a plan created without one (FR-RP-008) gets one later, or how an existing one gets corrected. WHEN PROVIDED, `siteAddress` MUST satisfy the same non-blank + 6-digit-postal-code rule as FR-RP-008 (`400` `bad_request` otherwise, no persist). Since the frozen `totalAmount` on a `QUOTED` plan was priced using `distance_km`, which is derived from `siteAddress`, setting a new address on a `QUOTED` plan MUST revert it to `DRAFT` and clear `totalAmount` — same rule as FR-RP-002/FR-RP-003 for item changes. A `CONVERTED` plan MUST NOT be updatable (`409` `already_converted`). An already-`CANCELLED` plan MUST NOT be updatable (`409` `already_cancelled`). Non-owner → `404`.

#### Scenario: Set address on a plan created without one
- GIVEN a DRAFT plan owned by the caller with `siteAddress: null`
- WHEN `PATCH .../{id}` with a valid `siteAddress`
- THEN `200` and the plan's `siteAddress` is set; `status` remains `DRAFT`

#### Scenario: Changing address on a quoted plan reverts to draft
- GIVEN a QUOTED plan owned by the caller with a non-null `totalAmount`
- WHEN `PATCH .../{id}` with a different valid `siteAddress`
- THEN `200`, `status` is `DRAFT`, and `totalAmount` is `null`

#### Scenario: Malformed address rejected
- GIVEN a plan owned by the caller
- WHEN `PATCH .../{id}` with a `siteAddress` that does not end in six digits
- THEN `400` `bad_request` and the plan is unchanged

#### Scenario: Converted plan cannot be updated
- GIVEN a CONVERTED plan
- WHEN `PATCH .../{id}`
- THEN `409` `already_converted`

### Requirement: FR-RP-012 Distance for dynamic quote

When `pricing.dynamic-enabled=true`, `distance_km` sent to haystack MUST be the straight-line (haversine) distance between the configured origin postal code (`pricing.origin-postal-code`, default `629462`) and the plan's `sitePostalCode`, both geocoded via OneMap. On any lookup failure (flag `pricing.distance-lookup-enabled=false`, missing/malformed postal code, OneMap no-match, timeout, circuit open) the system MUST fall back to `pricing.default-distance-km` (default `20.0`) and MUST NOT fail the quote. Origin MUST be the single fixed depot postal code — not per-asset `Asset.location`.

`RentalPlanService.create()` and `updateSiteAddress()` MUST populate `RentalPlan.sitePostalCode` from the trailing six digits of `siteAddress` (or `null` when omitted).

#### Scenario: Missing delivery postal uses default distance
- GIVEN a DRAFT plan with `siteAddress` omitted (`sitePostalCode` null)
- WHEN quote runs with dynamic pricing enabled
- THEN haystack still receives a `distance_km` equal to `pricing.default-distance-km`
- AND the quote succeeds

#### Scenario: OneMap down does not block quote
- GIVEN `pricing.distance-lookup-enabled=true` and OneMap's circuit is open
- WHEN quote runs
- THEN Spring uses `pricing.default-distance-km`
- AND the quote still succeeds (`200`)

## Out of scope

- Discounts / agreement e-sign  
- Line-item quantity column redesign  
- Dynamic pricing on add-item (cart remains `baseDailyRate`)  
- Driving/road distance (OneMap Routing API)  
- Per-asset or per-depot origin geocoding  
- `POST /api/pricing/estimate` (see [`../../changes/pricing-estimate/`](../../changes/pricing-estimate/))
