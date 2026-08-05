# Specification: Asset Catalog Mock Data (`AssetDataInitializer`)

| Field | Value |
|-------|--------|
| **Document type** | Feature SDD |
| **Status** | Superseded — seed data moved from `AssetDataInitializer` (Java) into `src/main/resources/data.sql` (see §9) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Packages** | `com.heavy_rental.rest_api.config` (historical — the class no longer exists) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md), [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) (read first) |
| **Related code** | `src/main/resources/data.sql`; `Asset`, `AssetCategory`, `AssetImage` entities; `AssetRepository`, `AssetCategoryRepository`, `AssetImageRepository` |

This document originally described the **as-built** `AssetDataInitializer`, a Java `ApplicationRunner` that seeded local/dev mock data into the asset catalog tables at application startup. That class has since been removed — see §9 for why and where the same seed data lives now. §§2–8 are kept as historical record of the seed data's content and the reasoning that shaped it; they no longer describe the delivery mechanism.

---

## 1. Purpose

The asset catalog (`asset_categories`, `assets`, `asset_images`) has no data and no admin UI to create rows yet. This feature provides local, automatically-seeded mock data so development and manual testing of asset-related endpoints has real rows to work against.

---

## 2. Background / Decision Log

Decisions made in discussion before writing the code:

- **Delivery mechanism — SQL file vs. Java class**: A `data.sql` script was drafted first, but Spring Boot's automatic `data.sql` loading does not reliably run after Hibernate creates the schema in this project's configuration. Decision: use a **Java `ApplicationRunner` class**, `AssetDataInitializer`, mirroring the existing `DefaultUserInitializer` (`config/DefaultUserInitializer.java`) — same shape: constructor-injected repositories, an early-return guard when data already exists, executed automatically at application startup.
- **Image representation — URL vs. base64**: Decision: store a **base64-encoded string** in `AssetImage.image` (a plain `String` column), not a URL. Base64 re-encodes an image's binary bytes as text (~33% larger than the source file) so it fits directly in a text column; nothing is compressed and no data is lost.
- **Base64 source — hardcoded literal vs. CSV vs. file resource**: Embedding multi-thousand-character base64 strings as Java literals or CSV cells is unreadable. Decision: place actual image files under `src/main/resources/mock-images/`, and have the initializer read each file's bytes and base64-encode them at startup (`Base64.getEncoder().encodeToString(...)`).
- **Image source**: Resolved — the user sourced real photos from free-license stock sites (Unsplash / Pexels), downloaded at modest resolution, and placed them manually into `mock-images/`. Not AI-generated, not scraped from any specific rental company's site (a competitor's product photography was considered and explicitly ruled out as not appropriate to reuse).
- **Category list — corrected mid-design**: The first draft of this spec invented 5 generic categories (Excavators, Cranes, Scissor Lifts, Boom Lifts, Generators). The user caught this: the project's actual approved equipment catalog is **exactly 4 types — Boom Lift, Scissors Lift, Fork Lift, Excavator** (matching the sibling frontend project's business rule in `heavy-rental-react-web-portal`'s `Spec-mock-api-server.md` FR-002). Cranes and Generators were removed; Fork Lift assets were added in their place.
- **Scope — which tables**: Limited to **`asset_categories` → `assets` → `asset_images`** (in that FK order; `assets.category_id` is `NOT NULL`, so categories must exist first). The remaining 10 entities (bookings, payments, rental plans, delivery/return records, AI recommendations) are explicitly out of scope for this pass.

---

## 3. Scope

### 3.1 In scope

- `AssetCategory`: 4 seed rows (Excavator, Scissors Lift, Boom Lift, Fork Lift).
- `Asset`: 8 seed rows, 2 per category.
- `AssetImage`: 9 seed rows, `image` populated from base64-encoded files under `mock-images/` (2 images for the first excavator, 1 image each for the remaining 7 assets).

### 3.2 Out of scope

- All other entities beyond `User` (already handled by `DefaultUserInitializer`) and the three above.
- Idempotent refresh/upsert behavior — the initializer only seeds when `asset_categories` is empty; it does not update or replace existing rows on subsequent runs.
- Environment/profile gating (e.g. dev-only) — this project has a single `application.properties` with no Spring profiles defined today.

---

## 4. Seed data (as implemented)

### 4.1 `asset_categories`

| name | description |
|---|---|
| Excavator | Tracked and wheeled excavators for digging and earthmoving |
| Scissors Lift | Vertical-access aerial work platforms |
| Boom Lift | Articulating and telescopic aerial work platforms |
| Fork Lift | Warehouse and yard material-handling forklifts |

### 4.2 `assets`

| name | category | serialno | base_daily_rate | condition | purchase_year |
|---|---|---|---|---|---|
| CAT 320 Excavator | Excavator | SN-EXC-000320 | 450.00 | GOOD | 2021 |
| Komatsu PC210 Excavator | Excavator | SN-EXC-000210 | 470.00 | EXCELLENT | 2023 |
| Genie GS-1930 Scissor Lift | Scissors Lift | SN-SCL-001930 | 120.00 | EXCELLENT | 2022 |
| JLG 2630ES Scissor Lift | Scissors Lift | SN-SCL-002630 | 140.00 | FAIR | 2018 |
| JLG 460SJ Boom Lift | Boom Lift | SN-BML-000460 | 210.00 | GOOD | 2020 |
| Genie Z-45 Boom Lift | Boom Lift | SN-BML-000045 | 195.00 | NEEDS_REPAIR | 2017 |
| Toyota 8FD25 Forklift | Fork Lift | SN-FKL-008FD25 | 150.00 | GOOD | 2021 |
| Hyster H2.5FT Forklift | Fork Lift | SN-FKL-H25FT | 160.00 | EXCELLENT | 2023 |

Each row also sets `min_daily_rate`/`max_daily_rate` (bracketing `base_daily_rate`), `description`, and — where applicable to the equipment type — `capacity` (forklifts, kg) or `platform_height` (scissor/boom lifts, m). `lastConditionUpdatedAt` is stamped with the initializer's run time.

### 4.3 `asset_images`

| file in `mock-images/` | asset |
|---|---|
| `asset1-cat320-excavator-a.jpg` | CAT 320 Excavator |
| `asset1-cat320-excavator-b.jpg` | CAT 320 Excavator (2nd photo) |
| `asset2-komatsu-pc210-excavator.jpg` | Komatsu PC210 Excavator |
| `asset4-genie-gs1930-scissorlift.jpg` | Genie GS-1930 Scissor Lift |
| `asset5-jlg-2630es-scissorlift.jpg` | JLG 2630ES Scissor Lift |
| `asset6-jlg-460sj-boomlift.jpg` | JLG 460SJ Boom Lift |
| `asset7-genie-z45-boomlift.jpg` | Genie Z-45 Boom Lift |
| `asset7-toyota-8fd25-forklift.jpg` | Toyota 8FD25 Forklift |
| `asset8-hyster-h25ft-forklift.jpg` | Hyster H2.5FT Forklift |

(Filename number prefixes are leftover from an earlier 9-category draft and don't map 1:1 to final asset order — they're stable identifiers, not meaningful sequence numbers.) Each is read via `ClassPathResource`, base64-encoded, and stored with `uploadedAt` stamped at initializer run time.

---

## 5. Functional requirements

- **FR-1**: ✅ `AssetDataInitializer` is a Spring `ApplicationRunner` `@Component` under `config/`, constructor-injected with `AssetCategoryRepository`, `AssetRepository`, `AssetImageRepository`, guarded by `categoryRepository.count() > 0` early return.
- **FR-2**: ✅ Seeding order inside `run()` is `asset_categories` → `assets` → `asset_images`, matching the FK dependency chain.
- **FR-3**: ✅ The initializer only inserts rows when `asset_categories` is empty, so restarting the app does not duplicate mock rows.
- **FR-4**: ✅ `Asset.condition` values use valid `ConditionType` enum constants (`EXCELLENT`, `GOOD`, `FAIR`, `NEEDS_REPAIR`).
- **FR-5**: ✅ `AssetImage.image` is populated by reading each file from `src/main/resources/mock-images/` via `ClassPathResource` and base64-encoding its bytes at startup (`readAsBase64` helper) — not an inline literal, not a URL.
- **FR-6**: ✅ The initializer logs a summary (`categoryRepository.count()`, `assetRepository.count()`, `imageRepository.count()`) via SLF4J after seeding, consistent with `DefaultUserInitializer`.

---

## 6. Dependencies & assumptions

- Relies on the schema already existing when the `ApplicationRunner` executes. This holds because `application.properties` sets `spring.jpa.hibernate.ddl-auto=update` (verified directly from the file on 2026-08-05), and Hibernate applies schema changes at context startup before `ApplicationRunner` beans run.
  - **Discrepancy flagged, not resolved here**: [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) §4 states `ddl-auto=create-drop` (schema dropped at shutdown). The current `application.properties` does not match that — it specifies `update`. The two specs should be reconciled with whichever value is actually correct.
- Relies on all 9 files listed in §4.3 existing under `src/main/resources/mock-images/` with exact matching filenames (including extension) at build/run time — the initializer throws `IOException` if a referenced file is missing.
- Images are free-license stock photos (Unsplash/Pexels), not proprietary or AI-generated; sized to keep each file well under 500 KB pre-encoding so the resulting base64 strings stay reasonable in size.

---

## 7. Open questions

- Should this initializer be restricted to a dev/local profile once the project introduces Spring profiles? No profiles exist today, so this is not yet actionable.
- No `@Transactional` boundary wraps the full seed operation — if the run fails partway (e.g. a missing image file after some assets are already saved), the initializer leaves partially-seeded data rather than rolling back. Acceptable for local/dev mock data; would need revisiting if this pattern is reused for anything less disposable.

---

## 9. Migration to `data.sql` (supersedes §2's delivery-mechanism decision)

`spring.jpa.defer-datasource-initialization=true` and `spring.sql.init.mode=always` were added to `application.properties` (for the broader data-seeding effort covering the other 10 entities — see [`SPEC-entity-repository.md`](./SPEC-entity-repository.md)). That makes `data.sql` run reliably after Hibernate creates the schema and, critically, **before** any `ApplicationRunner` bean executes — which is exactly the ordering problem §2's original decision log said didn't hold in this project. Two consequences:

1. The reason to prefer a Java initializer over `data.sql` no longer applies.
2. Leaving `AssetDataInitializer` in place would actively break: `data.sql` inserting into `asset_categories` first would trip the initializer's own `categoryRepository.count() > 0` guard, silently preventing it from ever seeding `assets`/`asset_images`.

Resolution: `config/AssetDataInitializer.java` was deleted, and its exact seed data (§4) was transcribed into `src/main/resources/data.sql`, in the same `asset_categories → assets → asset_images` FK order (§3's ordering requirement still holds — it's just enforced by statement order in one file instead of Java code). Image files under `mock-images/` are unchanged; each is base64-encoded once (via `base64 -w0`) and embedded as a literal in the corresponding `INSERT INTO asset_images` row instead of being read/encoded at runtime.

The tradeoffs called out in §7 change accordingly: seeding is now a single non-transactional script (Postgres does wrap a single `data.sql` run in a transaction by default, unlike the old per-row `save()` calls with no `@Transactional` boundary — an incidental improvement, not a design goal here). The idempotency behavior described in §3.2/FR-3 (only seed when empty) does **not** carry over: `data.sql`'s inserts are plain one-shot `INSERT`s with explicit IDs, consistent with how the rest of `data.sql` is written — a second app start without truncating the tables first will hit duplicate-key errors, same as every other table in that file.

---

## 10. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-05 | Initial draft capturing the agreed design prior to implementation. No code written yet. |
| 1.0.0 | 2026-08-05 | Implemented `config/AssetDataInitializer.java`. Corrected the category list from an invented 5-category set (Excavators/Cranes/Scissor Lifts/Boom Lifts/Generators) to the actual approved 4 categories (Excavator/Scissors Lift/Boom Lift/Fork Lift), replacing the crane and generator assets with two forklifts. Recorded final image source (user-downloaded free-license stock photos) and the 9-file `mock-images/` mapping actually used. |
| 1.1.0 | 2026-08-05 | Superseded the Java delivery mechanism: removed `AssetDataInitializer`, moved its seed data into `data.sql` (§9), now that `defer-datasource-initialization`/`sql.init.mode=always` make `data.sql` run reliably ahead of `ApplicationRunner`s in this project. |
