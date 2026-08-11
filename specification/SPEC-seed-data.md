# Specification: Database Seed Data (`data.sql`)

| Field | Value |
|-------|--------|
| **Document type** | SDD data-model reference (as-built) |
| **Status** | Implemented |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related code** | `src/main/resources/data.sql`; `src/main/resources/mock-images/`; `application.properties` (`spring.sql.init.mode`, `spring.jpa.defer-datasource-initialization`); all entities/repositories |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md), [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) (read first) |

This document is the single source of truth for `data.sql` — what it seeds, in what order, why it's structured the way it is, and what it assumes about tables it doesn't own. It supersedes the narrower, assets-only `SPEC-asset-mock-data.md` (see §9 change control): that file described a Java `ApplicationRunner`, `AssetDataInitializer`, which seeded only `asset_categories`/`assets`/`asset_images`. That class has been deleted; its seed data was folded into this same `data.sql`, which as of 1.4.0 covers all 13 tables, including `users` (see §6.0).

---

## 1. Purpose

Every table below (except `users`) had no data and no admin UI to create rows through. `data.sql` provides realistic, internally-consistent local/dev seed data — Singapore context, SGD amounts, metric units — so development and manual testing of any endpoint built against this schema has real rows to work against from first boot. As of 2.0.0, the fleet/booking volume was also sized to exercise a Haystack ML feature (`period_utilization`) that aggregates live `Asset`/`Booking`/`BookingItem` rows — see §6.2/§6.6 and the (now-removed) execution plan this was seeded from, `specification/temporary/data-seeding-spec`/`design.md`.

---

## 2. Outcomes

- Every entity except `User` has representative rows immediately after `./mvnw spring-boot:run` against a fresh or existing schema.
- Rows are internally consistent: line-item subtotals reconcile with `daily_rate × duration`, booking totals equal the sum of their items, deposit amounts follow a fixed ~30% ratio, and booking/asset lifecycle fields (engine hours, conditions, delivery/return records) are only populated for bookings whose dates have actually passed (or, for `MOBILISED`, are in progress) relative to the data's "current" date. **Every** booking has a `Payment`, and every `MOBILISED`/`COMPLETED` booking has the delivery/return rows its status implies — this is enforced uniformly across all 90 seeded bookings as of 2.0.0, not just a subset (see §6.6–§6.10).
- Feature work on any of the 12 seeded tables (bookings API, AI recommendations API, payments, etc.) has data to build and demo against without writing its own seeding script.
- Every `Asset` has a real, varying `capacity`/`platform_height`, and every category's fleet spans all 4 `ConditionType` values and has at least one spec-band with enough same-band assets that a live utilization query returns a genuine fraction, not a degenerate `0`/`1` — see §6.2.

---

## 3. Scope

### 3.1 In scope — 13 tables, seeded in this FK dependency order

`users` → `asset_categories` → `assets` → `asset_images` → `rental_plan` → `rental_plan_records` → `bookings` → `booking_items` → `payments` → `delivery_records` → `return_records` → `ai_recommendations` → `recommendation_items`.

`users` (§6.0) is seeded first, in the same file as everything else — see §7. Six rows: `'admin'`, `'Alex Tan'` (customer), `'Ravi Kumar'` (admin), `'Ah Tan'` (driver), `'Mei Ling'` (customer, added 2.0.0), `'Farid Rahman'` (customer, added 2.0.0) — covering the names the rest of this file joins against. (A separate `DefaultUserInitializer` `ApplicationRunner` used to seed `admin` alone when the table was empty; it was removed once `data.sql` covered `users` directly — see §9, 1.4.0.)

### 3.2 Out of scope

- Nothing — as of 1.5.0 every `INSERT` in this file carries `ON CONFLICT (id) ...` (see §7), specifically so it tolerates being run more than once against the same Postgres instance without truncation.
- Environment/profile gating — no Spring profiles exist in this project today.

---

## 4. Delivery mechanism & decision log

- **SQL file vs. Java class**: Early in this project, a `data.sql` script was drafted for the asset catalog but abandoned because Spring Boot's automatic `data.sql` loading didn't reliably run after Hibernate created the schema, given the configuration at the time — so a Java `ApplicationRunner` (`AssetDataInitializer`) was used instead, mirroring `DefaultUserInitializer`.
- **Reversal**: `spring.jpa.defer-datasource-initialization=true` and `spring.sql.init.mode=always` were added to `application.properties` for this broader seeding effort. That makes `data.sql` run reliably after Hibernate creates/updates the schema and — critically — **before** any `ApplicationRunner` bean executes, which is exactly the ordering guarantee the original decision said didn't hold. That removed the reason to prefer a Java initializer, and left an active conflict: `AssetDataInitializer` only seeds when `asset_categories` is empty, so a `data.sql` insert into that table running first would silently stop it from ever seeding `assets`/`asset_images`. Resolution: `AssetDataInitializer` was deleted and its exact seed data was transcribed into `data.sql`, in the same FK order.
- **Image representation — URL vs. base64**: `AssetImage.image` stores a **base64-encoded string**, not a URL — decided when the asset catalog was first designed, carried over unchanged. Base64 re-encodes an image's binary bytes as text (~33% larger than the source file) so it fits directly in a `TEXT` column; nothing is compressed and no data is lost.
- **Base64 source**: Real photos (free-license stock, Unsplash/Pexels — not AI-generated, not scraped from a competitor) live under `src/main/resources/mock-images/`. Rather than hand-typing multi-thousand-character base64 literals, each file was encoded once via `base64 -w0 <file>` and the resulting single-line string embedded directly as the `image` column value in the corresponding `INSERT INTO asset_images` row. Base file set is still the original 8 files (~1.3MB base64) — see §6.3 for how 2.0.0's 19 new asset rows get images without adding new files.
- **Category list**: Exactly 4 approved equipment types — Excavator, Scissors Lift, Boom Lift, Fork Lift (matching the sibling frontend project's business rule, `heavy-rental-react-web-portal`'s `Spec-mock-api-server.md` FR-002). An earlier draft invented a 5-category set (adding Cranes/Generators); corrected before implementation.
- **The other 10 entities' FK linkage to `assets`/`users`**: Since `assets` are now seeded in this same file with known explicit IDs, tables that reference them (`rental_plan_records`, `booking_items`, `recommendation_items`) could use bare integer FKs. They instead use `(SELECT id FROM assets WHERE name = '...')` subqueries — slightly more verbose, but self-documenting and stays correct if asset IDs ever shift. `users` FKs use the same pattern out of necessity, since `users` isn't seeded here at all (see §5). New `bookings` rows added in 2.0.0 use bare integer `asset_id`/direct customer subqueries consistently with this pattern.
- **2.0.0 — fleet/booking scale-up for `period_utilization`**: A Haystack ML feature (dynamic pricing) reads live `Asset`/`Booking`/`BookingItem` aggregates to compute per-spec-band utilization. The original 8-asset/20-booking fixture was too thin to exercise that feature meaningfully (every category had only 2 assets, 6 were missing `capacity`, 10 of 20 bookings had no `booking_items` at all, and no booking used `PENDING_DEPOSIT`/`CANCELLED`). Rather than growing the fleet uniformly, each category was restructured around **one 4-asset spec-band (real depth) plus exactly one asset in every other spec-band (real coverage, not an empty gap)** — 27 assets total, up from 8. Booking volume and Payment/DeliveryRecord/ReturnRecord completeness were scaled to match (90 bookings, up from 20; see §6.6–§6.10). Full rationale lived in `specification/temporary/data-seeding-spec`/`design.md` (Haystack-authored execution plan), removed after this reseed executed — this document is now the durable record.

---

## 5. FK linkage strategy

- **`users`**: joined by `name` (the entity's `UNIQUE` column, not `id`) against the six rows this same file now inserts first: `'admin'`, `'Alex Tan'`, `'Ravi Kumar'`, `'Ah Tan'`, `'Mei Ling'`, `'Farid Rahman'`. `rental_plan.customer_id` is `NOT NULL` — if `'Alex Tan'` doesn't exist yet, every `rental_plan` insert fails outright. `bookings.customer_id`, `ai_recommendations.user_id`, and `delivery_records`/`return_records.driver_id` are nullable, so a missing name there degrades to `NULL` instead of failing the statement.
- **`assets`**: joined by exact `name` (also `UNIQUE`, fleet-wide — not per category), e.g. `(SELECT id FROM assets WHERE name = 'CAT 320 Excavator')`. All asset-referencing FK columns in the 4 downstream tables are nullable, so this only matters for realism, not correctness. 2.0.0's 19 new assets each have a unique name (new models under an already-seeded brand — e.g. `CAT 330 Excavator` alongside the existing `CAT 320 Excavator`).

---

## 6. Seed data by table

### 6.0 `users` (6 rows)

| id | name | email | role | password (plaintext, dev-only) |
|---|---|---|---|---|
| 1 | admin | admin@localhost | ADMIN | `admin1234` (hardcoded hash; no longer overridable via `app.security.default-password`, which was removed) |
| 2 | Alex Tan | alex.tan@example.sg | USER | `customer123` |
| 3 | Ravi Kumar | ravi.kumar@example.sg | ADMIN | `admin123` |
| 4 | Ah Tan | ah.tan@example.sg | DRIVER | `driver123` |
| 5 | Mei Ling | mei.ling@example.sg | USER | `customer234` (added 2.0.0 — new bookings needed more than one customer) |
| 6 | Farid Rahman | farid.rahman@example.sg | USER | `customer345` (added 2.0.0) |

All passwords are stored as `BCryptPasswordEncoder` hashes generated with the same encoder bean the app uses (`config/SecurityConfig.java`), so they verify correctly via the normal login endpoint. Plaintext values are listed here only because this is local/dev seed data — never do this for a real environment.

Unlike the other 12 tables (`ON CONFLICT (id) DO NOTHING`), this insert uses `ON CONFLICT (id) DO UPDATE` — see §7. `users` is the one table where a rerun should actively overwrite whatever's already there (e.g. a stale password hash from an earlier seed), rather than leaving it alone.

### 6.1 `asset_categories` (4 rows)

| name | description |
|---|---|
| Excavator | Tracked and wheeled excavators for digging and earthmoving |
| Scissors Lift | Vertical-access aerial work platforms |
| Boom Lift | Articulating and telescopic aerial work platforms |
| Fork Lift | Warehouse and yard material-handling forklifts |

### 6.2 `assets` (27 rows, up from 8 as of 2.0.0)

Each category is shaped around **one 4-asset spec-band** (real depth — a live utilization query there can read `0`, `1/4`, `2/4`, `3/4`, or `1`, not just `0`/`1`) **plus exactly one asset in every other spec-band** (real coverage of that category's full size range, no band left empty). Spec-bands bucket by `capacity` for Excavator/Fork Lift and by `platform_height` for Scissors Lift/Boom Lift (`pricing_tables.CAPACITY_BINS`/`HEIGHT_BINS` in the Haystack repo) — grouping by raw category alone would let a fully-booked small-excavator fleet make a large excavator look artificially scarce, which is why the bands exist at all.

| id | name | category | capacity (kg) | platform_height (m) | base_daily_rate | condition | location |
|---|---|---|---|---|---|---|---|
| 1 | CAT 320 Excavator | Excavator | 3500 | — | 450.00 | GOOD | Tuas |
| 2 | Komatsu PC210 Excavator | Excavator | 6500 | — | 470.00 | EXCELLENT | Marina South |
| 9 | CAT 330 Excavator | Excavator | 4500 | — | 465.00 | FAIR | Pioneer |
| 10 | Komatsu PC300 Excavator | Excavator | 5500 | — | 480.00 | NEEDS_REPAIR | Jurong Port |
| 11 | CAT 301.5 Mini Excavator | Excavator | 2000 | — | 320.00 | GOOD | Tuas |
| 12 | Komatsu PC78 Excavator | Excavator | 10000 | — | 560.00 | EXCELLENT | Marina South |
| 13 | CAT 349 Excavator | Excavator | 20000 | — | 780.00 | FAIR | Gul Circle |
| 3 | Genie GS-1930 Scissor Lift | Scissors Lift | 300 | 7.80 | 120.00 | EXCELLENT | Tuas |
| 4 | JLG 2630ES Scissor Lift | Scissors Lift | 350 | 9.75 | 140.00 | FAIR | Marina South |
| 14 | Genie GS-2646 Scissor Lift | Scissors Lift | 230 | 6.00 | 100.00 | GOOD | Jurong |
| 15 | JLG 1932R Scissor Lift | Scissors Lift | 250 | 6.80 | 95.00 | NEEDS_REPAIR | Woodlands |
| 16 | Genie GS-3246 Scissor Lift | Scissors Lift | 280 | 7.40 | 130.00 | EXCELLENT | Kranji |
| 17 | JLG 3246ES Scissor Lift | Scissors Lift | 400 | 11.00 | 175.00 | GOOD | Senoko |
| 18 | Genie GS-4047 Scissor Lift | Scissors Lift | 450 | 13.50 | 190.00 | FAIR | Benoi |
| 5 | JLG 460SJ Boom Lift | Boom Lift | 250 | 15.72 | 210.00 | GOOD | Tuas |
| 6 | Genie Z-45 Boom Lift | Boom Lift | 220 | 13.70 | 195.00 | NEEDS_REPAIR | Marina South |
| 19 | JLG 600S Boom Lift | Boom Lift | 280 | 16.50 | 215.00 | EXCELLENT | Gul Drive |
| 20 | Genie Z-60 Boom Lift | Boom Lift | 300 | 17.50 | 200.00 | FAIR | Kallang Ave |
| 21 | JLG 660SJ Boom Lift | Boom Lift | 320 | 20.00 | 260.00 | GOOD | Pandan Loop |
| 22 | Genie S-85 Boom Lift | Boom Lift | 380 | 27.00 | 340.00 | EXCELLENT | Ayer Rajah |
| 23 | JLG 1250AJP Boom Lift | Boom Lift | 420 | 38.00 | 480.00 | FAIR | Sungei Kadut |
| 7 | Toyota 8FD25 Forklift | Fork Lift | 2500 | — | 150.00 | GOOD | Tuas |
| 8 | Hyster H2.5FT Forklift | Fork Lift | 2500 | — | 160.00 | EXCELLENT | Marina South |
| 24 | Toyota 8FD22 Forklift | Fork Lift | 2200 | — | 155.00 | FAIR | Penjuru Road |
| 25 | Hyster H3.2FT Forklift | Fork Lift | 3200 | — | 145.00 | NEEDS_REPAIR | Jalan Papan |
| 26 | Toyota 6FD15 Forklift | Fork Lift | 1500 | — | 110.00 | GOOD | Gul Drive |
| 27 | Hyster H4.2FT Forklift | Fork Lift | 4200 | — | 210.00 | EXCELLENT | Kranji Way |

The 4-asset band per category: Excavator `(3000,7000]` kg (ids 1,2,9,10); Scissors Lift `(0,8]` m (ids 3,14,15,16); Boom Lift `(0,18]` m (ids 5,6,19,20); Fork Lift `(2000,3500]` kg (ids 7,8,24,25). Every other id in each category is the sole occupant of its own band.

Assets 1, 2, 5, 6, 7, 8 are the original 8-asset fixture, unchanged in name/serial/rates; 1, 2, 3, 4, 5, 6 had their `capacity`/`platform_height` backfilled (previously `NULL` or, for the 2 forklifts, an identical `2500`) rather than replaced with new rows. `min_daily_rate`/`max_daily_rate` bracket `base_daily_rate` per row (not shown above) using the same per-category spread the original 8 assets already established. New assets are new models under an already-seeded brand (e.g. `CAT 330 Excavator` alongside `CAT 320 Excavator`) — see §6.3 for why that specific pairing matters.

### 6.3 `asset_images` (27 rows, up from 8 as of 2.0.0)

The original 8 rows are unchanged (one image per asset, matching `src/main/resources/mock-images/`'s 8 real JPEG files — no files added or removed in 2.0.0). Each of the 19 new assets' `asset_images` row **reuses its same-brand sibling's existing base64 `image` value verbatim** — e.g. `CAT 330 Excavator`'s image is a byte-for-byte copy of `CAT 320 Excavator`'s. This was a deliberate choice over sourcing 19 new photos: it needed zero new files under `mock-images/`, zero new `base64 -w0` runs, and keeps every asset's `img` field non-null through the Equipment Browse API (`SPEC-equipment-browse-api.md`) exactly as before. The cost is embedded `data.sql` size: ~1.3MB (8 images) → ~4.6MB (27 images, including duplicates) — see that spec's own change control for the corresponding scale-ceiling update.

All 8 source files under `mock-images/` are still verified real JPEGs (checked via magic bytes); `asset4-jlg-2630es-scissorlift.jpg` was previously corrected from a mislabeled PNG (see 1.6.0 below) — that fix is inherited by every new scissor-lift asset reusing its base64.

### 6.4 `rental_plan` (6 rows — unchanged)

All `customer_id` → `'Alex Tan'` (the only seeded non-admin, non-driver, non-`Mei Ling`/`Farid Rahman` user at the time this table was originally seeded — not revisited in 2.0.0, since `rental_plan` was out of scope for the reseed). Statuses span `DRAFT`/`SAVED`/`QUOTEED`/`CONVERTED` (the last is spelled as the literal, misspelled enum constant that exists in code). Sites are Singapore addresses (Tuas, Pioneer, Jurong Port, Marina South, Tampines) with `S(xxxxxx)` postal codes. `total_amount` equals the sum of that plan's `rental_plan_records`.

### 6.5 `rental_plan_records` (9 rows — unchanged)

1–2 line items per plan, `asset_id` resolved by name, `daily_rate` matching the referenced asset's real `base_daily_rate` **at the time this table was seeded** — note assets 1/2/5/6/7/8's `base_daily_rate` values themselves are unchanged by 2.0.0 (only `capacity`/`platform_height` were backfilled), so these subtotals still reconcile.

### 6.6 `bookings` (90 rows, up from 20 as of 2.0.0)

**The original 20 rows (ids 1–20) are unchanged** except three single-column `status` corrections found while auditing the table for internal consistency (not part of the fleet/booking scale-up itself, but cheap enough to fix in the same pass):

| id | was | now | why |
|---|---|---|---|
| 2 | `MOBILISED` | `COMPLETED` | Already had a `DEPOSIT`+`BALANCE` payment pair summing to the full total, plus both a `delivery_records` and a `return_records` row — every hallmark of a completed rental, not one still out. |
| 6 | `COMPLETED` | `CANCELLED` | Its only payment was `DEPOSIT`/`FAIL` with a `failure_reason` and no successful payment; its `booking_items` row had `NULL` engine-hours/condition (the pattern for a booking never delivered); no delivery/return row existed. |
| 7 | `MOBILISED` | `COMPLETED` | Same pattern as id 2 — full payment (`FULL_PAYMENT`/`SUCCESS`) and both delivery and return records already present; its `booking_items` row already had both `start_engine_hours`/`end_engine_hours` and `initial_condition`/`return_condition` populated, the `COMPLETED` shape, not the `MOBILISED` one. |

No other field on ids 1–20 changed — dates, amounts, customer, and every other row's `status` are exactly as before.

**70 new bookings (ids 21–90)** were added, spread roughly 2–3 per asset across all 27 assets, dates generated relative to seed time via `CURRENT_DATE ± N` (the same convention the original 20 already used — see §7), spanning ~30 days back to ~60 days forward. Spread across all 3 customers (`Alex Tan`, `Mei Ling`, `Farid Rahman`). Status distribution across the full 90-row table now covers all 6 `BookingStatus` values, including `PENDING_DEPOSIT` and `CANCELLED` (both absent before 2.0.0):

| status | count |
|---|---|
| COMPLETED | 28 |
| CONFIRMED | 22 |
| PENDING_CONFIRMED | 15 |
| MOBILISED | 14 |
| CANCELLED | 7 |
| PENDING_DEPOSIT | 4 |

Each category's 4-asset spec-band (§6.2) has at least one booking placed specifically so a `[today, today+5]`-style query returns a fractional result (2 of the 4 band assets have an active-status booking overlapping that window, 1 has a `COMPLETED` booking safely in the past, 1 has a `CONFIRMED` booking safely in the future) — this is what the fleet reshape in §6.2 was for; see the (now-removed) `specification/temporary/data-seeding-spec` for the full acceptance-scenario reasoning.

`remaining_balance` follows the pre-existing (undocumented until now) convention visible in the original 20 rows: `total_amount - deposit_amount` for `PENDING_CONFIRMED`, `total_amount` (nothing yet collected) for `PENDING_DEPOSIT`, and `0.00` for every other status — new rows follow the same rule.

### 6.7 `booking_items` (97 rows, up from 11 as of 2.0.0)

**Every one of the 90 bookings now has at least one `booking_items` row** — as of 2.0.0 this closes the gap where 10 of the original 20 bookings (ids 11–20) had none at all. Those 10 were backfilled using their pre-existing `status`/dates and a line item (one or two assets) whose `daily_rate × duration` reconciles to the booking's total. Two of the ten (ids 15 and 20) hit a genuine constraint: their original `total_amount`/duration combination (`980.00`/3 days, `860.00`/3 days) isn't evenly divisible into whole cents by any `daily_rate` choice — `980.00`÷3 and `860.00`÷3 aren't multiples of $0.03, so no single- or multi-item split can land exactly on the original total at that duration, for any rate. Rather than force an artificial split or touch the dates, `total_amount` on those two rows was adjusted by $0.01 (`980.00`→`980.01`, `860.00`→`860.01`) to the nearest value the fixed 3-day duration *can* reach exactly — the only field on either row that changed beyond the status corrections in §6.6. `deposit_amount` was left as-is on both (the ~30% ratio isn't meaningfully affected by a cent), and booking 20's `BALANCE` payment (§6.8) was adjusted by the same $0.01 so `DEPOSIT + BALANCE` still sums to the corrected total exactly.

Lifecycle fields (`start_engine_hours`/`end_engine_hours`/`initial_condition`/`return_condition`) follow the same rule as before: `NULL` for `PENDING_DEPOSIT`/`PENDING_CONFIRMED`/`CONFIRMED`/`CANCELLED`, `start_engine_hours`+`initial_condition` only for `MOBILISED` (still out, not yet returned), and all four populated for `COMPLETED`.

### 6.8 `payments` (114 rows, up from 11 as of 2.0.0)

**Every one of the 90 bookings now has at least one `payments` row**, status-driven:

| status | payment(s) |
|---|---|
| `PENDING_DEPOSIT` | one `DEPOSIT`/`PENDING` row, no `paid_at` — deposit invoiced but not yet paid |
| `PENDING_CONFIRMED` / `CONFIRMED` / `MOBILISED` | one `DEPOSIT`/`SUCCESS` row |
| `COMPLETED` | `DEPOSIT`/`SUCCESS` + `BALANCE`/`SUCCESS` summing to `total_amount` (a handful of the original rows instead use a single `FULL_PAYMENT`/`SUCCESS` row — both patterns are valid, `PaymentType` has both) |
| `CANCELLED` | one `DEPOSIT`/`FAIL` row with `failure_reason` set, no `paid_at` |

This is the first version of this table where `PaymentStatus.PENDING` is actually used — every payment before 2.0.0 was `SUCCESS` or `FAIL` only, since no `PENDING_DEPOSIT` booking existed to need it.

### 6.9 `delivery_records` (42 rows, up from 6) / 6.10 `return_records` (28 rows, up from 5)

Now populated for **every** `MOBILISED`/`COMPLETED` booking (`delivery_records`) and **every** `COMPLETED` booking (`return_records`) across all 90 bookings — including the 10 backfilled orphans (§6.7). `driver_id` → `'Ah Tan'` for every row, as before. `PENDING_DEPOSIT`/`PENDING_CONFIRMED`/`CONFIRMED`/`CANCELLED` bookings still have neither.

### 6.11 `ai_recommendations` (5 rows — unchanged)

`user_id` mostly `'Alex Tan'`, one `'Ravi Kumar'` (an admin exploring the feature). Includes a two-row revision chain via `previous_recommendation_id` (a rejected excavator recommendation revised to a larger-capacity one after project scope changed). Statuses span the full `RecommendationStatus` range.

### 6.12 `recommendation_items` (8 rows — unchanged)

1–2 ranked suggestions per recommendation, `asset_id` resolved by name, `ml_predicted_price` set close to the referenced asset's real `base_daily_rate`. All 8 reference original-fixture assets (ids 1–8); not revisited in 2.0.0.

---

## 7. Assumptions & dependencies

- `spring.jpa.hibernate.ddl-auto=update`, `spring.sql.init.mode=always`, `spring.jpa.defer-datasource-initialization=true` — the combination that makes `data.sql` run reliably after schema creation/update and before any `ApplicationRunner`, on **every** `ApplicationContext` refresh (not just the first app boot ever — see next point).
- `'Alex Tan'`, `'Ravi Kumar'`, `'Ah Tan'`, `'Mei Ling'`, and `'Farid Rahman'` must exist in `users` before the rest of `data.sql` runs, or the `rental_plan` inserts (`customer_id` `NOT NULL`) fail outright and abort the rest of the script. Satisfied by the `0. users` block at the top of the same file (see §6.0).
- **`data.sql` runs more than once per test suite, against the same Postgres instance.** `spring.sql.init.mode=always` reruns it on every distinct `ApplicationContext` — e.g. `AuthenticationIntegrationTest`'s `@AutoConfigureMockMvc` config produces a different context than a plain `@SpringBootTest`, so a single `mvn test`/`mvn clean install` boots at least two contexts, each re-executing this file against a database that isn't dropped between them (`ddl-auto=update` never truncates). Every `INSERT` in this file carries `ON CONFLICT (id) DO NOTHING` (or, for `users`, `DO UPDATE` — see §6.0) specifically so this is safe: a rerun converges on the same rows instead of throwing `duplicate key value violates unique constraint`. **This is also why the 90 bookings' `CURRENT_DATE`-relative dates don't self-refresh on every boot**: `ON CONFLICT DO NOTHING` means a booking's date is computed once, at whichever boot first inserts it, then frozen until the row is deleted or the database is dropped — the same behavior the original 20 rows already had (their dates went stale over time for exactly this reason, well before 2.0.0). This spec doesn't attempt to change that behavior (would require switching bookings to `DO UPDATE`, a larger change); a truly fresh-looking date window requires a full reseed against an empty/dropped database.
- Relies on the 8 files referenced in §6.3 existing under `src/main/resources/mock-images/` with exact matching filenames at build time. **Still exactly 8 files as of 2.0.0** — the 19 new `asset_images` rows reuse existing base64 content rather than requiring new source files (see §6.3).

---

## 8. Verification

- Boot the app against the reachable `db` Postgres instance and confirm no constraint-violation errors in the startup log.
- `SELECT count(*) FROM assets` → 27. `SELECT count(*) FROM assets WHERE capacity IS NULL` → 0.
- `SELECT ac.name, count(DISTINCT a.condition) FROM assets a JOIN asset_categories ac ON a.category_id = ac.id GROUP BY ac.name` → 4 for every category.
- `SELECT count(*) FROM asset_images` → 27.
- `SELECT count(*) FROM bookings` → 90. `SELECT count(DISTINCT status) FROM bookings` → 6.
- `SELECT count(*) FROM bookings b LEFT JOIN booking_items bi ON bi.booking_id = b.id WHERE bi.id IS NULL` → 0 (no orphaned bookings).
- `SELECT count(*) FROM bookings b LEFT JOIN payments p ON p.booking_id = b.id WHERE p.id IS NULL` → 0 (every booking has a payment).
- `SELECT count(*) FROM bookings b LEFT JOIN delivery_records dr ON dr.booking_id = b.id WHERE b.status IN ('MOBILISED','COMPLETED') AND dr.id IS NULL` → 0.
- `SELECT count(*) FROM bookings b LEFT JOIN return_records rr ON rr.booking_id = b.id WHERE b.status = 'COMPLETED' AND rr.id IS NULL` → 0.
- `SELECT id, status FROM bookings WHERE id IN (2, 6, 7)` → `COMPLETED`, `CANCELLED`, `COMPLETED` (the three corrections, §6.6).
- `SELECT * FROM rental_plan_records`/`recommendation_items` `WHERE asset_id IS NULL` — should be impossible; a hit means an asset name lookup didn't resolve.
- `SELECT * FROM rental_plan WHERE customer_id IS NULL` — should be impossible (`NOT NULL` column).

---

## 9. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-05 | Initial draft (as `SPEC-asset-mock-data.md`) capturing the agreed asset-catalog seeding design prior to implementation. |
| 1.0.0 | 2026-08-05 | Implemented `config/AssetDataInitializer.java` (asset catalog only: `asset_categories`, `assets`, `asset_images`). |
| 1.1.0 | 2026-08-05 | Extended seeding to the other 10 entities via `src/main/resources/data.sql`, using name-based subqueries to link to `users`/`assets` without seeding those tables directly. |
| 1.2.0 | 2026-08-05 | Removed `AssetDataInitializer`; transcribed its seed data into `data.sql` in FK order, resolving the guard-condition conflict described in §4. Asset-referencing tables switched from `ILIKE` keyword guesses to exact-name lookups against the real 8-asset catalog, with all dependent rates/subtotals recalculated accordingly. |
| 1.3.0 | 2026-08-05 | Renamed `SPEC-asset-mock-data.md` → `SPEC-seed-data.md` and rewrote as the single spec covering all 12 seeded tables (previously asset-catalog-only in framing, even after 1.2.0 folded the other tables into the same file in practice). |
| 1.4.0 | 2026-08-05 | Added a `0. users` block to `data.sql`, resolving the hard dependency noted in §7: `'admin'`, `'Alex Tan'`, `'Ravi Kumar'`, `'Ah Tan'` are now seeded with `BCryptPasswordEncoder` password hashes before the rest of the file runs. `users` is no longer fully out of scope for this file (§3.2 updated accordingly). |
| 1.5.0 | 2026-08-05 | Discovered `data.sql` reruns on every distinct `ApplicationContext` (not once per app lifetime), so `mvn test`/`mvn clean install` was hitting `duplicate key value violates unique constraint` once `users` (1.4.0) removed the one thing (`DefaultUserInitializer`'s empty-table check) that had been shielding a rerun from this. Added `ON CONFLICT (id) DO NOTHING` to all 12 non-`users` inserts and `ON CONFLICT (id) DO UPDATE` to `users`, so every insert in this file now tolerates reruns against a non-empty database. Also fixed unrelated, pre-existing schema drift on the live `asset_images` table hit immediately after (leftover `NOT NULL image_url` column and `image` still `varchar(255)`, both stale relative to the `AssetImage` entity) via a one-off `ALTER TABLE`; `ddl-auto=update` cannot fix this class of drift on its own since it never alters or drops existing columns. |
| 1.6.0 | 2026-08-06 | Two corrections found while building `SPEC-equipment-browse-api.md`: (1) `asset5-jlg-2630es-scissorlift.jpg` (asset id 4's image) was a PNG mislabeled with a `.jpg` extension — re-encoded to a real JPEG and its `data.sql` base64 regenerated. (2) `CAT 320 Excavator`'s second `asset_images` row (id 2) was removed, since the equipment API now exposes exactly one photo per asset — `asset_images` is now 8 rows, not 9 (§6.3). |
| 1.7.0 | 2026-08-08 | Deleted `mock-images/asset1-cat320-excavator-b.jpg`, the leftover source file for CAT 320's second photo removed in 1.6.0. It was unreferenced by any `INSERT` and confirmed unreferenced anywhere else in the codebase before deletion — `mock-images/` now contains exactly the 8 files §6.3/§7 actually use, no orphaned files. |
| 1.8.0 | 2026-08-09 | §6.6/§6.9-6.10 corrected to match `HR-77` (merged to `develop` before this branch branched off): `Booking.BookingStatus.PENDING` was split into `PENDING_DEPOSIT`/`PENDING_CONFIRMED`, and `Booking.PaidStatus`/`paid_status` was removed from the entity entirely — this doc still described the pre-`HR-77` shape (plain `PENDING`, a `PaidStatus`/`UNPAID` value) despite `data.sql` itself already having been updated by that same change. Documentation-only correction; no seed data changed. |
| 1.9.0 | 2026-08-09 | Two corrections found in PR review: (1) §6.3 said "All 9 files" and still named the pre-rename `asset5-jlg-2630es-scissorlift.jpg` — a separate, unrelated merge from `develop` (HR-77) renamed every `assetN-*` image file to match its actual asset id (the file for asset 4 is `asset4-jlg-2630es-scissorlift.jpg`) and dropped one now-orphaned old file; corrected to "8 files" and the current name (the 1.6.0/1.7.0 entries above are left as-is — they're accurate for what was true when written, before that rename landed). (2) Added a `location` column to the §6.2 asset table — `Asset.location` was added to the entity/seed data in this same PR but never documented here. |
| 2.0.0 | 2026-08-11 | Executed the reseed planned in `specification/temporary/data-seeding-spec`/`design.md` (Haystack-authored requirements for their `period_utilization` ML feature; both files removed after this executed). Fleet grown from 8 to 27 assets (§6.2) — every category reshaped around one 4-asset spec-band plus a 1-asset filler in every other band, backfilling `capacity`/`platform_height` on the 6 pre-existing assets that lacked it (or had it duplicated). 19 new `asset_images` rows added by reusing an existing same-brand asset's base64 verbatim — zero new photo files (§6.3). Bookings grown from 20 to 90 (§6.6): the original 20 are untouched except three `status` corrections found while auditing the table (ids 2, 6, 7 — see §6.6) and a $0.01 `total_amount` adjustment on ids 15/20 (see §6.7 — their fixed 3-day duration couldn't reconcile the original total to any cent-precision rate at all); 70 new bookings bring in all 6 `BookingStatus` values (`PENDING_DEPOSIT`/`CANCELLED` previously absent) and 2 new `USER`-role customers (`Mei Ling`, `Farid Rahman`, §6.0). `booking_items`/`payments`/`delivery_records`/`return_records` completeness extended to **every** booking, including the 10 that were previously orphaned (§6.7–§6.10), with zero cent-level reconciliation gaps anywhere in the table — closing a gap that predates this reseed. All counts in §8 updated accordingly. |

**Design / execution runbook:** (was `specification/temporary/data-seeding-design`, removed after this version executed — see 2.0.0 above)
