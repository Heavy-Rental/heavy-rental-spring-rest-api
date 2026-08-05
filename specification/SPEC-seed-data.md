# Specification: Database Seed Data (`data.sql`)

| Field | Value |
|-------|--------|
| **Document type** | SDD data-model reference (as-built) |
| **Status** | Implemented |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related code** | `src/main/resources/data.sql`; `src/main/resources/mock-images/`; `application.properties` (`spring.sql.init.mode`, `spring.jpa.defer-datasource-initialization`); all entities/repositories except `User` |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md), [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) (read first) |

This document is the single source of truth for `data.sql` — what it seeds, in what order, why it's structured the way it is, and what it assumes about tables it doesn't own. It supersedes the narrower, assets-only `SPEC-asset-mock-data.md` (see §9 change control): that file described a Java `ApplicationRunner`, `AssetDataInitializer`, which seeded only `asset_categories`/`assets`/`asset_images`. That class has been deleted; its seed data was folded into this same `data.sql`, which now covers every table except `users`.

---

## 1. Purpose

Every table below (except `users`) had no data and no admin UI to create rows through. `data.sql` provides realistic, internally-consistent local/dev seed data — Singapore context, SGD amounts, metric units — so development and manual testing of any endpoint built against this schema has real rows to work against from first boot.

---

## 2. Outcomes

- Every entity except `User` has representative rows immediately after `./mvnw spring-boot:run` against a fresh or existing schema.
- Rows are internally consistent: line-item subtotals reconcile with `daily_rate × duration`, booking totals equal the sum of their items, deposit amounts follow a fixed ~30% ratio, and booking/asset lifecycle fields (engine hours, conditions, delivery/return records) are only populated for bookings whose dates have actually passed relative to the data's "current" date.
- Feature work on any of the 12 seeded tables (bookings API, AI recommendations API, payments, etc.) has data to build and demo against without writing its own seeding script.

---

## 3. Scope

### 3.1 In scope — 12 tables, seeded in this FK dependency order

`asset_categories` → `assets` → `asset_images` → `rental_plan` → `rental_plan_records` → `bookings` → `booking_items` → `payments` → `delivery_records` → `return_records` → `ai_recommendations` → `recommendation_items`.

### 3.2 Out of scope

- `users` — seeded separately. `DefaultUserInitializer` (`config/DefaultUserInitializer.java`) creates one default admin (`name = 'admin'`) when the table is empty. `data.sql` additionally **depends on** three more users existing by the time it runs — `'Alex Tan'` (customer), `'Ravi Kumar'` (admin), `'Ah Tan'` (driver) — which are not yet seeded by any code in this repo as of this writing; see §7.
- Idempotent refresh/upsert behavior — every insert here is a plain one-shot `INSERT` with an explicit ID. A second app start without truncating tables first will hit duplicate-key errors (see §7).
- Environment/profile gating — no Spring profiles exist in this project today.

---

## 4. Delivery mechanism & decision log

- **SQL file vs. Java class**: Early in this project, a `data.sql` script was drafted for the asset catalog but abandoned because Spring Boot's automatic `data.sql` loading didn't reliably run after Hibernate created the schema, given the configuration at the time — so a Java `ApplicationRunner` (`AssetDataInitializer`) was used instead, mirroring `DefaultUserInitializer`.
- **Reversal**: `spring.jpa.defer-datasource-initialization=true` and `spring.sql.init.mode=always` were added to `application.properties` for this broader seeding effort. That makes `data.sql` run reliably after Hibernate creates/updates the schema and — critically — **before** any `ApplicationRunner` bean executes, which is exactly the ordering guarantee the original decision said didn't hold. That removed the reason to prefer a Java initializer, and left an active conflict: `AssetDataInitializer` only seeds when `asset_categories` is empty, so a `data.sql` insert into that table running first would silently stop it from ever seeding `assets`/`asset_images`. Resolution: `AssetDataInitializer` was deleted and its exact seed data was transcribed into `data.sql`, in the same FK order.
- **Image representation — URL vs. base64**: `AssetImage.image` stores a **base64-encoded string**, not a URL — decided when the asset catalog was first designed, carried over unchanged. Base64 re-encodes an image's binary bytes as text (~33% larger than the source file) so it fits directly in a `TEXT` column; nothing is compressed and no data is lost.
- **Base64 source**: Real photos (free-license stock, Unsplash/Pexels — not AI-generated, not scraped from a competitor) live under `src/main/resources/mock-images/`. Rather than hand-typing multi-thousand-character base64 literals, each file was encoded once via `base64 -w0 <file>` and the resulting single-line string embedded directly as the `image` column value in the corresponding `INSERT INTO asset_images` row. Total embedded size is ~1.85 MB of base64 text across 9 rows.
- **Category list**: Exactly 4 approved equipment types — Excavator, Scissors Lift, Boom Lift, Fork Lift (matching the sibling frontend project's business rule, `heavy-rental-react-web-portal`'s `Spec-mock-api-server.md` FR-002). An earlier draft invented a 5-category set (adding Cranes/Generators); corrected before implementation.
- **The other 10 entities' FK linkage to `assets`/`users`**: Since `assets` are now seeded in this same file with known explicit IDs (1–8), tables that reference them (`rental_plan_records`, `booking_items`, `recommendation_items`) could use bare integer FKs. They instead use `(SELECT id FROM assets WHERE name = '...')` subqueries — slightly more verbose, but self-documenting (a reader sees "JLG 460SJ Boom Lift" inline instead of a bare `5`) and stays correct if asset IDs ever shift. `users` FKs use the same pattern out of necessity, since `users` isn't seeded here at all (see §5).

---

## 5. FK linkage strategy

- **`users`**: joined by `name` (the entity's `UNIQUE` column, not `id`, since this file never inserts into `users`). Expected values: `'admin'`, `'Alex Tan'`, `'Ravi Kumar'`, `'Ah Tan'`. `rental_plan.customer_id` is `NOT NULL` — if `'Alex Tan'` doesn't exist yet, every `rental_plan` insert fails outright. `bookings.customer_id`, `ai_recommendations.user_id`, and `delivery_records`/`return_records.driver_id` are nullable, so a missing name there degrades to `NULL` instead of failing the statement.
- **`assets`**: joined by exact `name` (also `UNIQUE`), e.g. `(SELECT id FROM assets WHERE name = 'CAT 320 Excavator')`. All asset-referencing FK columns in the 4 downstream tables are nullable, so this only matters for realism, not correctness.

---

## 6. Seed data by table

### 6.1 `asset_categories` (4 rows)

| name | description |
|---|---|
| Excavator | Tracked and wheeled excavators for digging and earthmoving |
| Scissors Lift | Vertical-access aerial work platforms |
| Boom Lift | Articulating and telescopic aerial work platforms |
| Fork Lift | Warehouse and yard material-handling forklifts |

### 6.2 `assets` (8 rows, 2 per category)

| name | category | base_daily_rate | condition | purchase_year |
|---|---|---|---|---|
| CAT 320 Excavator | Excavator | 450.00 | GOOD | 2021 |
| Komatsu PC210 Excavator | Excavator | 470.00 | EXCELLENT | 2023 |
| Genie GS-1930 Scissor Lift | Scissors Lift | 120.00 | EXCELLENT | 2022 |
| JLG 2630ES Scissor Lift | Scissors Lift | 140.00 | FAIR | 2018 |
| JLG 460SJ Boom Lift | Boom Lift | 210.00 | GOOD | 2020 |
| Genie Z-45 Boom Lift | Boom Lift | 195.00 | NEEDS_REPAIR | 2017 |
| Toyota 8FD25 Forklift | Fork Lift | 150.00 | GOOD | 2021 |
| Hyster H2.5FT Forklift | Fork Lift | 160.00 | EXCELLENT | 2023 |

Each row also sets `min_daily_rate`/`max_daily_rate` (bracketing `base_daily_rate`), a description, and — where applicable — `capacity` (forklifts, kg) or `platform_height` (scissor/boom lifts, m).

### 6.3 `asset_images` (9 rows)

One image per asset except `CAT 320 Excavator`, which has 2. Each `image` value is the base64 encoding of a real file under `mock-images/`; `uploaded_at` is a fixed timestamp.

### 6.4 `rental_plan` (6 rows)

All `customer_id` → `'Alex Tan'` (the only seeded non-admin, non-driver user). Statuses span `DRAFT`/`SAVED`/`QUOTEED`/`CONVERTED` (the last is spelled as the literal, misspelled enum constant that exists in code). Sites are Singapore addresses (Tuas, Pioneer, Jurong Port, Marina South, Tampines) with `S(xxxxxx)` postal codes. `total_amount` equals the sum of that plan's `rental_plan_records`.

### 6.5 `rental_plan_records` (9 rows)

1–2 line items per plan, `asset_id` resolved by name, `daily_rate` matching the referenced asset's real `base_daily_rate`, `subtotal = daily_rate × plan duration`.

### 6.6 `bookings` (10 rows)

All `customer_id` → `'Alex Tan'`. Two are converted from `rental_plan` (ids 4 and 5, mirroring the original db.json example bookings); the rest are direct. Spans the full `BookingStatus` range (`PENDING`, `CONFIRMED`, `MOBILISED`, `COMPLETED`, `CANCELLED`) and `PaidStatus` range, with dates chosen relative to the data's assumed "today" (2026-08-05) so status is date-consistent: `COMPLETED` bookings are in the past, `PENDING`/`CONFIRMED` are in the future, `MOBILISED` straddles today. `deposit_amount` is a fixed 30% of `total_amount` (matching the ratio in the original db.json example), except the one `CANCELLED`/`UNPAID` booking (no deposit was ever collected).

### 6.7 `booking_items` (11 rows)

Mirrors the lifecycle logic in §6.6: `start_engine_hours`/`end_engine_hours`/`initial_condition`/`return_condition` are only populated for bookings that have actually been delivered and/or returned as of "today"; future or cancelled bookings leave these `NULL`.

### 6.8 `payments` (11 rows)

Fake Stripe-style IDs (`pi_...`, `ch_...`, `cus_...`). One booking (`CANCELLED`) has a single `FAIL` payment with a `failure_reason` and no `paid_at`, consistent with the cancellation note on that booking. Completed/converted bookings paid `FULL` have two payment rows (`DEPOSIT` + `BALANCE`) summing to the booking total.

### 6.9 `delivery_records` (6 rows) / 6.10 `return_records` (5 rows)

Only for bookings whose delivery/return has actually happened as of "today": `driver_id` → `'Ah Tan'` for every row. One `MOBILISED` booking has a delivery record but no return record yet (equipment still on site); the `PENDING`/`CONFIRMED`/`CANCELLED` bookings have neither.

### 6.11 `ai_recommendations` (5 rows)

`user_id` mostly `'Alex Tan'`, one `'Ravi Kumar'` (an admin exploring the feature). Includes a two-row revision chain via `previous_recommendation_id` (a rejected excavator recommendation revised to a larger-capacity one after project scope changed). Statuses span the full `RecommendationStatus` range.

### 6.12 `recommendation_items` (8 rows)

1–2 ranked suggestions per recommendation, `asset_id` resolved by name, `ml_predicted_price` set close to the referenced asset's real `base_daily_rate`.

---

## 7. Assumptions & dependencies

- `spring.jpa.hibernate.ddl-auto=update`, `spring.sql.init.mode=always`, `spring.jpa.defer-datasource-initialization=true` — the combination that makes `data.sql` run once, reliably, after schema creation/update and before any `ApplicationRunner`.
- **Hard dependency, not yet satisfied by any code in this repo**: `'Alex Tan'`, `'Ravi Kumar'`, and `'Ah Tan'` must exist in `users` before `data.sql` runs, or the `rental_plan` inserts (customer_id `NOT NULL`) fail outright and abort the rest of the script. Only `'admin'` is currently seeded by `DefaultUserInitializer`.
- Not idempotent: every insert uses an explicit ID with no `ON CONFLICT` handling. Re-running the app against a database that already has these rows (schema not dropped, since `ddl-auto=update`) will throw duplicate-key errors. Truncate the 12 tables (or drop/recreate the schema) before a second run if fresh data is wanted.
- Relies on all 9 files referenced in §6.3 existing under `src/main/resources/mock-images/` with exact matching filenames at build time (they're read once, by the `base64` command, when `data.sql` was generated — not at runtime, unlike the old `AssetDataInitializer`).

---

## 8. Verification

- Boot the app against the reachable `db` Postgres instance and confirm no constraint-violation errors in the startup log.
- `SELECT count(*) FROM <table>` for each of the 12 tables — expected counts per §6.
- `SELECT * FROM booking_items WHERE asset_id IS NULL` (and the equivalent for `rental_plan_records`, `recommendation_items`) — should return zero rows; a hit means an asset name lookup didn't resolve.
- `SELECT * FROM rental_plan WHERE customer_id IS NULL` — should be impossible (`NOT NULL` column); if the whole `rental_plan` block failed to insert, this is the first place to look.

---

## 9. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-05 | Initial draft (as `SPEC-asset-mock-data.md`) capturing the agreed asset-catalog seeding design prior to implementation. |
| 1.0.0 | 2026-08-05 | Implemented `config/AssetDataInitializer.java` (asset catalog only: `asset_categories`, `assets`, `asset_images`). |
| 1.1.0 | 2026-08-05 | Extended seeding to the other 10 entities via `src/main/resources/data.sql`, using name-based subqueries to link to `users`/`assets` without seeding those tables directly. |
| 1.2.0 | 2026-08-05 | Removed `AssetDataInitializer`; transcribed its seed data into `data.sql` in FK order, resolving the guard-condition conflict described in §4. Asset-referencing tables switched from `ILIKE` keyword guesses to exact-name lookups against the real 8-asset catalog, with all dependent rates/subtotals recalculated accordingly. |
| 1.3.0 | 2026-08-05 | Renamed `SPEC-asset-mock-data.md` → `SPEC-seed-data.md` and rewrote as the single spec covering all 12 seeded tables (previously asset-catalog-only in framing, even after 1.2.0 folded the other tables into the same file in practice). |
