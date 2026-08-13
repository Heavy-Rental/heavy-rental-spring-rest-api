# Monthly Utilization — Source of Truth

## Purpose

Admin Overview metric: trailing six calendar months of utilization % and payment revenue.

**Status:** **As-built**  
**HTTP:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** `ROLE_ADMIN` only  
## Requirements

### Requirement: FR-MU-001 Trailing six months

`GET /api/monthly-utilization` MUST return exactly six entries (oldest → newest or documented order), each with `id`, `month`, `utilization`, and `revenue` for that calendar month.

#### Scenario: Admin receives six months
- GIVEN a valid `ROLE_ADMIN` access token
- WHEN `GET /api/monthly-utilization`
- THEN `200` with a list of length 6

### Requirement: FR-MU-002 Revenue from successful payments

For each month, `revenue` MUST sum successful `Payment` amounts whose paid timestamp falls in that month (as-built service definition).

### Requirement: FR-MU-003 Utilization from booking-item day overlap

`utilization` MUST be computed as a percentage of (asset count × days in month) using per-`BookingItem` day overlap with parent bookings in active statuses used by the service (`CONFIRMED`, `MOBILISED`, `COMPLETED` as-built).

#### Scenario: Formula is fleet-relative
- GIVEN seeded assets and bookings
- WHEN utilization is computed for a month
- THEN the denominator uses total asset count × days in that month

### Requirement: FR-MU-004 Admin-only access

The route MUST be gated to `ROLE_ADMIN`. Unauthenticated → `401`; `ROLE_USER` → `403`.

#### Scenario: User forbidden
- GIVEN a customer access token
- WHEN the endpoint is called
- THEN `403`

## Known open items

- Current month uses full month length in the denominator (not days elapsed so far).  
- `BigDecimal.ZERO` may serialize as `0` vs `0.00` (cosmetic).  
- No permanent automated test in suite as-built (manual + one-time accuracy test only).

## Related

- Seed scale: [`../seed-data/`](../seed-data/)  
- Route map: [`../api-index/contracts/routes.md`](../api-index/contracts/routes.md)
