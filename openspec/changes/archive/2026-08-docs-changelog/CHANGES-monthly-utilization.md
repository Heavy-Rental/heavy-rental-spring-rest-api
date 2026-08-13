# Changes Made: Monthly Utilization Endpoint

| Field | Value |
|-------|--------|
| **Purpose** | Plain-language log of the backend changes made on `hr-40-equipment-utilization-tracker` to add `GET /api/monthly-utilization` (trailing 6-month utilization/revenue for the admin Overview screen) |
| **Scope** | Backend only (`heavy-rental-spring-rest-api`, this repo) |
| **Branch** | `hr-40-equipment-utilization-tracker` |
| **Status** | Committed — `8227447 "utilization"`, merged into `b037fbb "update md files"` on `hr-40-equipment-utilization-tracker` |

---

## 1. Backend changes (this repo)

### 1.1 Built the endpoint itself

New files:
- `dto/MonthlyUtilizationResponse.java` — record `{id, month, utilization, revenue}`
- `service/MonthlyUtilizationService.java` — `getTrailingSixMonths()`: for each of the trailing 6 calendar months, sums successful `Payment`s paid in that month for `revenue`, and sums per-`BookingItem` day-overlap (booking dates ∩ month dates) across bookings in an active status (`CONFIRMED`, `MOBILISED`, `COMPLETED`) for `utilization` — expressed as a percentage of (total asset count × days in month)
- `controller/MonthlyUtilizationController.java` — `GET /api/monthly-utilization` → `List<MonthlyUtilizationResponse>`

### 1.2 Repository addition

- `repository/BookingItemRepository.java` — added `findByBookingStatusIn(Collection<Booking.BookingStatus>)`, a `JOIN FETCH` query pulling every booking item whose parent booking is in one of the given statuses

### 1.3 Security gate

- `config/SecurityConfig.java` — added `.requestMatchers("/api/monthly-utilization").hasAuthority("ROLE_ADMIN")`, alongside the existing `/actuator` permit rule. This is the same `ROLE_ADMIN`-only pattern used for `/api/users/**` (see `project_admin-user-management-plan` decisions) — everything else in this file uses `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")`.

---

## 2. Verification done this session

Ran the app locally against the real Postgres instance (`db-primary`) and exercised the live endpoint:

- No `Authorization` header → `401`
- `ROLE_USER` token (`alex.tan@example.sg`) → `403`
- `ROLE_ADMIN` token (`admin@localhost`) → `200`, returned 6 months (Mar–Aug) with revenue and utilization trending upward toward the current month, consistent with `SPEC-seed-data.md`'s description of the seeded booking volume ramping toward "now"
- Cross-checked total asset count via `GET /api/equipment` → `27`, matches the seed data spec
- Code compiles cleanly (`./mvnw -o compile`)
- Wrote a one-time `MonthlyUtilizationAccuracyTest` (`@SpringBootTest`, later deleted) that independently recomputed revenue/utilization straight from `Payment`/`BookingItem`/`Asset` repository data — separate code path from `MonthlyUtilizationService`, not copy-pasted — and asserted it matched the live service output for all 6 months. All matched exactly, confirming the formula (not just the trend) is correct.
- Confirmed end-to-end through the actual web portal (real API mode, not the mock server): browser Network tab showed `GET /api/monthly-utilization` returning the identical payload, and the Overview dashboard rendered correctly against it once logged in with the right admin credentials (`ravi.kumar@example.sg` / `admin123` — mismatched email/password pairing was the cause of an earlier round of `401`s, not a backend bug)

No automated test coverage remains for this endpoint (the accuracy test above was intentionally one-time and removed; no permanent test file present in `src/test`).

---

## 3. Known open items

- Current month's utilization denominator uses the full month length (e.g. 31 days for August), not days elapsed so far — consistent across all 6 months but worth confirming against what the Overview chart is meant to show.
- `BigDecimal.ZERO` revenue serializes as `0` instead of `0.00` (cosmetic JSON inconsistency vs. months with real payment sums).
- ~~Not yet committed~~ — confirmed committed in `8227447 "utilization"` (`git status` now shows a clean working tree).
- `GET /api/users` 404s in the browser when testing against this branch — expected, not a bug: `UserController` is the teammate's separate Feature 1 work, not merged into `hr-40-equipment-utilization-tracker`.
- Frontend-side observation (not this backend's scope, but worth flagging to whoever owns the portal): a single Overview page load fetches `equipment` 3×, and `bookings`/`rental-plans`/`monthly-utilization` 2× each — `equipment` alone is ~4.7MB per call, so that's several MB of redundant traffic per load.
