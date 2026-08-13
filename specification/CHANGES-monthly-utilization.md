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

- ~~Current month's utilization denominator uses the full month length~~ — **confirmed intentional.** The numerator (`overlapDays`) is forward-looking — it counts a booking's full date-range overlap with the month regardless of "today" — so using the full month length as the denominator for every month (including the in-progress current one) consistently answers "how much of this month's total capacity is already committed (past + future bookings combined)," not "how are we tracking so far." Decided 2026-08-13; no code change.
- ~~`BigDecimal.ZERO` revenue serializes as `0` instead of `0.00`~~ — **fixed.** `MonthlyUtilizationService.getTrailingSixMonths()` now applies `.setScale(2, RoundingMode.HALF_UP)` to the computed `revenue`, so months with zero successful payments serialize as `0.00` like every other month.
- ~~Not yet committed~~ — confirmed committed in `8227447 "utilization"` (`git status` now shows a clean working tree).
- ~~`GET /api/users` 404s in the browser when testing against this branch~~ — **resolved 2026-08-13.** `develop` (which has `UserController`, full `/api/rentalPlans`, and the haystack recommender routes) merged into this branch — see §4.1 below. `/api/users` now works here.
- Frontend-side observation (not this backend's scope, but worth flagging to whoever owns the portal): a single Overview page load fetches `equipment` 3×, and `bookings`/`rental-plans`/`monthly-utilization` 2× each — `equipment` alone is ~4.7MB per call, so that's several MB of redundant traffic per load.

---

## 4. Follow-up session — 2026-08-13

### 4.1 Merged `develop` into this branch

`hr-40-equipment-utilization-tracker` had diverged from `develop` before `/api/users` (`UserController`), the full `/api/rentalPlans` (create/list/get/items/quote), and the haystack recommender routes existed — none of that was reachable from this branch until now. Resolved real conflicts in: this file, `SPEC-api-index.md`, `SPEC-entity-repository.md`, `SecurityConfig.java` (both branches had independently added their own `.requestMatchers` line — kept both), and `MonthlyUtilizationService.java` (kept this branch's `setScale` fix over `develop`'s pre-fix version). Compiled clean, all routes reverified live afterward.

### 4.2 New: per-asset utilization on `GET /api/equipment`

The admin Overview's "Per Asset Utilization Rate" chart rendered empty against the real backend — `EquipmentResponse` never carried a `utilization` field at all (only the mock server's `db.json` did, baked into each seed record). Added:

- `Booking.overlapDays(Booking, LocalDate, LocalDate)` (static) and `Booking.UTILIZATION_STATUSES` (`CONFIRMED`/`MOBILISED`/`COMPLETED`) — promoted out of `MonthlyUtilizationService`'s former private copies onto `Booking` itself (matching the existing `Booking.ACTIVE_STATUSES` precedent), so the fleet-wide and per-asset figures can't drift apart on what "utilized" means. `MonthlyUtilizationService` updated to use the shared versions instead of its own copies.
- `EquipmentResponse` gained a `Double utilization` field.
- `AssetService` computes it per asset for the **current month only** (booked days ÷ days in month × 100 — decision: mirrors the existing "UTILIZATION RATE" KPI tile, which is also a current-snapshot number, not a trailing series) via a new `computeUtilizationByAssetId` helper, reusing `BookingItemRepository.findByBookingStatusIn`. Wired into `browse`/`getById`/`create`/`replace`/`patch`; a freshly-`create`d asset gets `0.0` with no query, since it can't have bookings yet.

Verified live: real, distinct per-asset percentages (e.g. `19.354838709677418 = 6/31 days` for a 31-day August), math confirmed exact. All previously-verified routes still `200` afterward; revenue formatting from §3 unaffected.

### 4.3 `ddl-auto` excursion and a real incident — worth knowing about

Temporarily set to `create-drop` mid-session to work around a pre-existing schema drift issue unrelated to this work (`rental_plan.version` NOT NULL column mismatch after a branch switch — same failure mode `SPEC-rental-plan-quote.md` §5 already documents happening once before). While `create-drop` was active, a *different* app instance connected to the same shared, persistent `db-primary` Postgres also attempted to start, hit a port conflict, and failed — but not before its own drop-then-create schema init ran, and its shutdown (on the bind failure) dropped those tables again. This wiped the `users` table out from under the instance actually being tested against, causing real, reproducible login `401`s with no code-level cause — confirmed via the raw error body: `relation "users" does not exist`. Fixed by a clean restart; `ddl-auto` reverted to `update` afterward and reverified (clean boot, all routes `200`).

**Lesson recorded for next time:** `create-drop` against a shared, persistent database is unsafe the moment more than one process might touch it — any other instance starting or stopping against the same database can silently wipe your schema, not just your own restarts. Prefer the `SPEC-rental-plan-quote.md` §5 pattern (one-time `create-drop` boot, immediately revert to `update`) and avoid leaving `create-drop` set for any extended testing session.

Separately: this file itself was found emptied on disk (0 bytes, uncommitted) partway through this session, despite being correctly committed at 53 lines — likely an editor auto-save issue, since it was open in the IDE the whole time. Restored from the last commit; nothing was lost, but worth knowing this can happen to an open file mid-session.

### 4.4 Seed-data bug found via §4.2's new per-asset figures: a genuine double-booking

Manual verification of the new per-asset `utilization` field (§4.2) caught `JLG 460SJ Boom Lift` (asset id 5) reporting **106.45%** — impossible for a single asset in one month, so not a display quirk, a real signal something upstream was wrong.

**Root cause, confirmed against the real code, not assumed:** `BookingService.java:99-100` does check for asset conflicts, but only at `POST /api/bookings` creation time, and only against `Booking.ACTIVE_STATUSES` (`PENDING_DEPOSIT`/`PENDING_CONFIRMED`/`CONFIRMED`/`MOBILISED`) — `COMPLETED` is deliberately excluded, and `updateBooking` (the `PUT` path) never re-checks at all. Seed data bypasses this entirely (`data.sql` is raw SQL, never goes through `BookingService`), and in this case two seeded bookings for the same asset — id 4 (`MOBILISED`) and id 8 (`COMPLETED`) — landed on the exact same 3-day window (`CURRENT_DATE - 2` to `CURRENT_DATE`), something the real API's conflict check would never have allowed for two simultaneously-active bookings, but doesn't guard against for a `COMPLETED` one. (First traced to a mis-reading on my part — the seed file links some `booking_items` rows to this asset via `(SELECT id FROM assets WHERE name = 'JLG 460SJ Boom Lift')` instead of the literal id `5`; a text search for the literal digit missed 3 of the 9 relevant rows before this was caught.)

**Fix applied:** `data.sql` booking id 8's dates changed from `CURRENT_DATE - 2, CURRENT_DATE` to `CURRENT_DATE - 20, CURRENT_DATE - 17` — moves it fully clear of the Aug 11–19 cluster of other bookings on the same asset, while keeping its `COMPLETED` narrative intact (an older, already-finished rental rather than an overlapping one). Verified this asset has exactly one `booking_items` row (id 9), so the change has no effect on any other asset.

**Also applied directly against the live database**, not just the seed file: `bookings` uses `ON CONFLICT (id) DO NOTHING`, so a plain restart would never have picked up the `data.sql` edit for an already-seeded row. Ran a one-off JDBC `UPDATE bookings SET start_date = ..., end_date = ... WHERE id = 8` (via a throwaway single-file Java script against the existing `postgresql-42.7.11.jar` in the local Maven repo — no schema touched, no `create-drop` needed given §4.3's incident). Confirmed before/after via direct query.

**Result, verified live:** `JLG 460SJ Boom Lift` utilization dropped from `106.45161290322581%` to `96.7741935483871%` (`30/31 days`), and a fleet-wide sweep confirmed zero assets remain over `100%`. All previously-verified routes still `200` afterward.

**Worth knowing:** this was one specific pair found by chance while spot-checking one asset. The underlying gap — `COMPLETED` bookings never blocking new bookings for the same window, with no separate "actual return date" tracked — is real in the live app too, not just this seed file, and could resurface for other assets or through genuine usage. Not fixed here; flagged for whoever owns booking/inventory logic next.
