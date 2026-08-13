# Changes Made: Linking the Web Portal to the Real Equipment API

| Field | Value |
|-------|--------|
| **Purpose** | Plain-language log of every backend change made to build `/api/equipment` and support `heavy-rental-react-web-portal`'s "Browse Equipment" page pointing at it instead of the mock server |
| **Scope** | Backend only (`heavy-rental-spring-rest-api`, this repo). The paired frontend changes now live in the `heavy-rental-react-web-portal` repo itself, not here. |
| **Related docs** | [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) (backend design detail), [`SPEC-seed-data.md`](./SPEC-seed-data.md) (data fixes) |

---

## 1. Backend changes (this repo)

### 1.1 Built the equipment API itself
New files:
- `dto/EquipmentResponse.java`, `dto/EquipmentRequest.java`
- `service/AssetService.java`
- `controller/EquipmentController.java` — `/api/equipment` (list/get/create/replace/patch/delete)

Small additions (no existing code removed):
- `repository/AssetImageRepository.java` — added `findByAssetIdIn`
- `repository/BookingItemRepository.java` — added `findAssetIdsWithOverlappingBooking`
- `repository/AssetRepository.java` — added `findAllWithCategory`

No `SecurityConfig` changes — new routes fall under the existing auth rule automatically.

### 1.2 Fixed two real data bugs found in `data.sql`
- `CAT 320 Excavator` had 2 photo rows seeded; removed the second (the API only ever returns one photo per equipment item, matching the frontend's `Equipment.img: string` type — a single string, not an array)
- `asset5-jlg-2630es-scissorlift.jpg` (asset id 4's photo) was a PNG mislabeled with a `.jpg` extension; re-encoded to a real JPEG

### 1.3 Added two stub endpoints (purely to unblock the frontend)
- `controller/DepotController.java` — `GET /api/depots` → `[]`
- `controller/RentalPlanController.java` — `GET /api/rental-plans` → `[]`

Neither has a real entity behind it. They exist only because the frontend's equipment page also calls these on every load, and errors out entirely if `/api/depots` fails — even though it's unrelated to equipment itself.

**Update, 2026-08-11:** `RentalPlanController` is no longer a stub — it's now the full REQ-1–5 implementation built on `hr-19-request-quote` (see [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md)), and its route was renamed `/api/rental-plans` → `/api/rentalPlans` in that same branch's PR review. The line above is left as-is since it's an accurate record of what this specific change (equipment-frontend integration) actually built at the time; `DepotController` is still a real stub today, only `RentalPlanController` has moved on.

### 1.4 Corrected `SPEC-entity-repository.md` per PR review feedback
A teammate's review caught that this branch's code changes had made 4 statements in that doc false (it said there was no equipment CRUD API, omitted 3 new repository methods, and described cascade-delete/controller handling as future work that had actually already shipped). Fixed all 4, plus a 5th instance of the same pattern found while checking. Documentation-only — no entity, repository, or relationship content changed. See that doc's own `1.1.0` changelog entry for the full list.

### 1.5 Merged `origin/develop` (teammate's HR-77 booking work) — clean, no conflicts
Pulled in a teammate's `Booking` entity edit and new mock booking data. `data.sql` and `Booking.java`/`BookingRepository.java` merged automatically — the two branches' changes lived in different, non-overlapping parts of each file. Our equipment fixes (deduped photo, re-encoded JPEG) survived untouched, confirmed by re-checking the row count and file content after merging.

### 1.6 Fixed a compile break the merge exposed
`Booking.BookingStatus.PENDING` no longer existed after the merge — the teammate's entity edit split it into `PENDING_DEPOSIT` and `PENDING_CONFIRMED`. `AssetService.ACTIVE_BOOKING_STATUSES` (used to compute equipment availability) still referenced the old single `PENDING` constant. Updated it to include both new pending states.

### 1.7 Switched `ddl-auto` from `update` to `create-drop`
After fixing 1.6, startup still failed — Postgres rejected the merged `data.sql`'s new booking rows because the live database's `bookings_status_check` constraint still listed the *old* enum values (`update` mode never rewrites existing constraints, only adds new columns/tables). Switching to `create-drop` means the schema is dropped and rebuilt fresh from the entities on every restart, so constraints always match current code — permanently avoiding this class of problem for any future enum change, at the cost of local data no longer persisting across restarts (acceptable since `data.sql` reseeds everything anyway).

### 1.8 Cleaned up and replaced seed photos
- Deleted `mock-images/asset1-cat320-excavator-b.jpg`, the orphaned source file for CAT 320's already-removed duplicate photo (confirmed unreferenced anywhere in the codebase first)
- Renamed the remaining files so their leading number matches their actual `asset_id` (previously mismatched/confusing — e.g. `asset5-jlg-2630es-scissorlift.jpg` was actually asset id 4)
- Replaced the actual photo content for 3 assets (ids 3, 4, 6 — Genie GS-1930 Scissor Lift, JLG 2630ES Scissor Lift, Genie Z-45 Boom Lift), each verified as a genuine JPEG (magic-byte check) before regenerating its base64 into `data.sql`

### 1.9 Made `available` nullable — "not checked yet" vs. `true`/`false`
Previously, no `startDate`/`endDate` given meant the API silently defaulted to checking "available today," which could show equipment as **Booked** before a user had picked any dates at all — confusing, and not something anyone had actually asked for. Changed:
- `EquipmentResponse.available`: `boolean` → `Boolean` (nullable)
- `AssetService.resolveAvailabilityWindow`: no dates → returns `null` instead of defaulting to today (skips the booking-overlap query entirely in that case)
- `browse()`/`getById()`: pass `null` for `available` when there's no date window, real `true`/`false` once dates are picked

Paired with a matching frontend change (in `heavy-rental-react-web-portal`) that hides the availability badge entirely until `available` is an actual boolean.

### 1.10 Added `location` as a real field
Previously omitted (equipment cards showed a `—` fallback). Added properly:
- `Asset.location` — new nullable column
- `EquipmentResponse.location` / `EquipmentRequest.location` — added to both DTOs
- `AssetService` — wired through `toResponse`, `applyRequest` (create/replace), and `patch`
- `data.sql` — all 8 assets now seeded with a real location, alternating `Tuas`/`Marina South`

No frontend code changes were needed for this — fallbacks already in place in `heavy-rental-react-web-portal` handled a real value correctly once one existed.

### 1.11 Reverted `ddl-auto` from `create-drop` back to `update`
The last commit on this branch (`ad80def`, "change ddl to update") flips `spring.jpa.hibernate.ddl-auto` back to the value it had before 1.7, with no accompanying explanation in the commit message. **This needs a follow-up note once the reason is known** — 1.7 switched to `create-drop` specifically to stop `update` mode's known failure mode (it never rewrites an existing constraint, so a live DB with the old `bookings_status_check` values would reject the merged `Booking` enum's new rows). Reverting to `update` without a corresponding schema fix risks reintroducing that exact startup failure the next time a checked-out constraint drifts from the entity. §4, §7, §10, and §11.2 of `SPEC-entity-repository.md` have been updated to describe the code as it stands (`update`) rather than the now-superseded 1.7 decision, but that's a documentation-accuracy fix, not a statement that reverting was safe — confirm before merging.

---

## 2. What still isn't built

Only `/api/equipment`, `/api/auth/*`, and the two empty stubs above exist on this backend. Anything needing real bookings, checkout/payment, admin asset management, or AI recommendations will `404` if exercised — expected, not a bug, until those features are built the same way equipment was.

**Update, 2026-08-11:** as of `hr-19-request-quote` (see §1.3's note above), rental plans/quoting is real and built — only `/api/depots` remains an empty stub today. Bookings, checkout/payment, admin asset management, and AI recommendations are still not built, as originally stated.

**Update, 2026-08-13:** admin asset management is now built — see [`CHANGES-admin-asset-records.md`](./CHANGES-admin-asset-records.md). The route this section describes was also renamed in that change: `/api/equipment` is `/api/assets` as of that date (`EquipmentController`/`EquipmentRequest`/`EquipmentResponse` → `AssetController`/`AssetRequest`/`AssetResponse`); this file's `/api/equipment` references above describe the route as it was built at the time and are left as a historical record, not updated in place.

---

## 3. How to run both sides

```bash
# Backend (this repo)
cd heavy-rental-spring-rest-api
./mvnw spring-boot:run

# Frontend (separate repo/devcontainer)
npm run dev:api    # real backend — .env.api must have VITE_API_TARGET=http://heavy-rental-rest-api:8080
# or
npm run dev:mock   # mock server, unaffected by any of the above
```

Login (real backend mode): `alex.tan@example.sg` / `customer123` (customer), `ravi.kumar@example.sg` / `admin123` (admin).
