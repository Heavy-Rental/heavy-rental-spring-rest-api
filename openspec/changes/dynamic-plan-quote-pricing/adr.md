# ADR: Flag-gated Haystack quote pricing with per-item Spring fallback

| Field | Value |
|-------|--------|
| **Status** | Accepted |
| **Date** | 2026-08-15 (as-built; living specs synced 2026-08-27) |
| **Capability** | rental-plan-quote / spring-proxy-endpoints |
| **Trace** | FR-RP-004 · FR-RP-006 · FR-PROXY-001 · FR-PROXY-005 |
| **OpenSpec** | [`../../specs/rental-plan-quote/spec.md`](../../specs/rental-plan-quote/spec.md) · [`../../specs/spring-proxy-endpoints/spec.md`](../../specs/spring-proxy-endpoints/spec.md) |
| **OpenSPDD** | [`design.md`](./design.md) |
| **Upstream** | haystack-fast-api `POST /internal/v1/pricing/quote` (dynamic-pricing US-4) |

## Context

Portal checkout displays the rental plan's frozen `totalAmount` from `POST /api/rentalPlans/{id}/quote`. That total used to be 100% Spring `baseDailyRate × inclusive days`, even though haystack already ships a dedicated internal pricing endpoint. Putting a second, independently fetched ML price on the booking-summary screen would drift from the number that actually determines the deposit.

Haystack's recommender saga (Call 1 ingest → Call 2 recommend → Call 3 Q&A) is the wrong path: the cart/quote flow has no project spec.

## Decision

1. **Quote step is the single pricing source.** `RentalPlanService.requestQuote()` refreshes line `dailyRate`/`subtotal` from haystack, then sums `totalAmount`. Add-item stays `Asset.baseDailyRate` (FR-RP-002).
2. **Dedicated client, not the recommender saga.** `HaystackPricingClient` calls `POST /internal/v1/pricing/quote` with its own timeout, retry (max 1), bulkhead, and circuit breaker.
3. **Flag-gated.** `pricing.dynamic-enabled` / `DYNAMIC_PRICING_ENABLED`. As-built module default in `application.properties` is **`true`**. Flag off is byte-for-byte the previous Spring-only sum.
4. **Never block checkout.** Whole-call `HaystackException` or a per-item `error`/missing price falls back to `DefaultPricingClient` for that item. `degraded=true` with a usable price is **not** a failure — use the returned price and `WARN`-log `model_version`.
5. **Do not apply haystack `deposit_rate`.** Booking deposit remains `Booking.DEPOSIT_RATE = 0.30`.
6. **Split the quote transaction.** Haystack I/O is outside the DB transaction that holds `@Version`, so a 20s pricing read cannot cause a spurious 409 optimistic-lock on concurrent cart edits (HR-153).

`distance_km` was a constant in this change (`pricing.default-distance-km = 20.0`). Real geocoding is a follow-up: [`../pricing-postal-distance/`](../pricing-postal-distance/).

## Consequences

### Positive

- Frozen quote total, booking summary, and deposit stay single-sourced.
- ML outage degrades to the already-trusted base-rate math instead of failing checkout.
- Recommender bulkhead/circuit is not coupled to cart quote traffic.

### Negative / accepted

- Cart line prices (pre-quote) can disagree with the quoted total until quote is requested.
- Haystack `deposit_rate` on the pricing envelope is ignored (documented SoT split).
- Operators must watch `WARN` logs for fallback and degraded model versions.

### Rejected alternatives

| Alternative | Why not |
|-------------|---------|
| Fetch ML price on the booking-summary screen | Diverges from the frozen total that Stripe charges |
| Route through Call 1/2 recommender saga | No project spec; wrong endpoint; ingest is 180s-class |
| Fail the quote when haystack is down | Blocks checkout for an optional ML enhancement |
| Treat `degraded=true` as fallback | Upstream still returned a model price; discarding it invents a worse number |
| Apply haystack `deposit_rate` to bookings | Conflicts with booking `DEPOSIT_RATE = 0.30`; payments already own that rate |
