# Delta: rental-plan-quote (dynamic pricing)

## MODIFIED Requirements

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
- GIVEN `pricing.dynamic-enabled=true` and an item's result comes back with `degraded=true` but a non-null `daily_rate`/`total_price` (`haystack-fast-api`'s primary data snapshot was unavailable; it served the price from a secondary source)
- WHEN quote is requested
- THEN that item's subtotal uses the returned dynamic price as-is — it does NOT fall back to `DefaultPricingClient`
- AND a `WARN` log is emitted with the plan id, item id, and `model_version` for ops visibility into upstream data-source health

### Requirement: FR-RP-006 Quote pricing source

`POST .../quote` MUST use `DynamicPricingService` when `pricing.dynamic-enabled=true`, backed by a FastAPI `PricingClient` calling `haystack-fast-api`'s `POST /internal/v1/pricing/quote`. When the flag is `false` (default), quote pricing remains Spring-only `DefaultPricingClient` arithmetic from snapshotted line subtotals, with no HTTP call to haystack — unchanged from prior behavior.

#### Scenario: Flag off preserves current behavior
- GIVEN `pricing.dynamic-enabled=false`
- WHEN quote runs
- THEN no HTTP call to haystack is made
- AND `totalAmount` equals the sum of already-snapshotted line subtotals, exactly as before this change
