# Specification: Equipment Browse & CRUD API

| Field | Value |
|-------|--------|
| **Feature** | REST API for browsing and managing rentable equipment (`Asset`), including photos |
| **Status** | Implemented, build verified (`mvnw compile` exit 0); manual/curl verification against a running app not yet run — see §8 |
| **Module** | `heavy-rental-spring-rest-api` |
| **Endpoints** | `GET/POST /api/equipment`, `GET/PUT/PATCH/DELETE /api/equipment/{id}` |
| **Depends on** | [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) (`Asset`/`AssetCategory`/`AssetImage`/`Booking`/`BookingItem`), [`SPEC-seed-data.md`](./SPEC-seed-data.md) (image encoding provenance), [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) (access token required to call these routes) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |
| **Related code** | `controller/EquipmentController.java`, `service/AssetService.java`, `dto/EquipmentResponse.java`, `dto/EquipmentRequest.java`, `repository/AssetRepository.java`, `repository/AssetImageRepository.java`, `repository/BookingItemRepository.java` |

This document is the **single source of truth** for the `/api/equipment` REST surface: what it returns, how equipment photos are encoded for the client, how availability is computed, and delete semantics given this codebase has no cascading deletes.

---

## 1. Purpose & outcomes

Before this feature, the backend had no equipment-facing endpoint — the sibling frontend (`heavy-rental-react-web-portal`) browsed equipment against a mock JSON server. This feature exposes the real `Asset`/`AssetCategory`/`AssetImage` data through a contract matching the frontend's existing mock shape, so the frontend's "Browse Equipment" page can point at this backend and render equipment cards with real photos, no frontend code changes required beyond auth wiring (out of scope here — see §3.2).

When this feature is correct:

1. `GET /api/equipment` returns every asset (optionally filtered) with a photo array the frontend can drop directly into `<img src="...">`, with no client-side transformation.
2. `available` reflects real booking overlap for a given date window, not a hardcoded value.
3. Deleting an asset with dependent rows fails with a clear `409`, not a raw DB stack trace.
4. All existing auth/security posture is reused unchanged — no `SecurityConfig` edits.

---

## 2. Scope

### 2.1 In scope

- `GET /api/equipment` — list/browse with optional `category`, `search`, `condition`, `startDate`, `endDate` filters.
- `GET /api/equipment/{id}` — single asset lookup.
- `POST /api/equipment` — create.
- `PUT /api/equipment/{id}` — full replace.
- `PATCH /api/equipment/{id}` — partial update.
- `DELETE /api/equipment/{id}` — delete, with dependent-row conflict handling.
- Server-side conversion of stored base64 image data into browser-renderable data URIs.
- Availability computation from `BookingItem`/`Booking` overlap.

### 2.2 Out of scope

- `SecurityConfig` changes — the existing catch-all `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` rule already covers these new routes.
- Frontend changes — pointing the React portal's `VITE_API_TARGET` at this backend and swapping its mock `issueSession()` for a real `/api/auth/login` call live in the separate `heavy-rental-web-portal` repo.
- Pagination, thumbnails, or a separate image-only endpoint — every `browse()` call returns full-size images inline for all matching assets (~4.6MB total across the 27 seeded images per `SPEC-seed-data.md` §6.3, as of that spec's 2.0.0 reseed). Acceptable at this scale; flagged as a future concern if the catalog grows further.
- An `AssetImage.mimeType` column — see §3.4.

---

## 3. Image encoding — the key design decision

### 3.1 Problem

`AssetImage.image` is a `TEXT` column storing **raw base64** (no `data:image/...;base64,` prefix) — decided when the asset catalog was designed (`SPEC-seed-data.md` §4) and unchanged here. A browser `<img src="...">` cannot render raw base64 directly; it needs either a URL or a complete [data URI](https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/data).

### 3.2 Decision

`EquipmentResponse.img` is a **single string** (`String`, not `List<String>`) — confirmed against the frontend's actual `Equipment` type (`src/app/types.ts` in `heavy-rental-react-web-portal`), which declares `img: string`. An earlier draft of this plan assumed an array without checking that type; corrected here.

`AssetService` fetches one `AssetImage` per asset (`firstImage(assetId)` / a batched `loadImageByAssetId` for `browse()`) and prepends `data:image/jpeg;base64,` when mapping to `EquipmentResponse.img`:

```java
private static final String JPEG_DATA_URI_PREFIX = "data:image/jpeg;base64,";

private String toDataUri(AssetImage image) {
    return image != null ? JPEG_DATA_URI_PREFIX + image.getImage() : null;
}
```

An asset with no image row returns `img: null`.

### 3.2.1 CAT 320 Excavator's second photo — removed from seed data

`asset_images` originally seeded 2 rows for CAT 320 Excavator (asset id 1) and 1 row for every other asset. Since the API now exposes exactly one photo per equipment item, the second CAT 320 row was deleted from `data.sql` rather than kept-but-unused — every asset now has exactly 1 `asset_images` row, 8 rows total (down from 9). See `SPEC-seed-data.md` §6.3 (updated in the same change).

This keeps `AssetImage` a pure persistence mapping (no HTTP-representation logic on the entity, consistent with every other entity in this codebase) and keeps `EquipmentResponse` a plain, behavior-free record (consistent with `LoginResponse`/`MessageResponse`). The service — which already owns all `Asset → EquipmentResponse` mapping — is where this one extra line belongs.

### 3.3 Why hardcoding `image/jpeg` is correct today

Every file under `src/main/resources/mock-images/` is now a **verified real JPEG** (checked via magic bytes: `ff d8 ff...`), and `AssetImage` has no `mimeType` column. Hardcoding is accurate for all current data.

**Correction made in this change**: `asset5-jlg-2630es-scissorlift.jpg` (the image for asset id 4, JLG 2630ES Scissor Lift) was actually a **PNG mislabeled with a `.jpg` extension** (magic bytes `89 50 4e 47...`, the PNG signature) — the original base64 in `data.sql` was PNG data despite the hardcoded `image/jpeg` prefix, which would have declared the wrong MIME type in the data URI (most browsers sniff actual bytes and render it anyway, but it's not a guarantee). Fixed by re-encoding the file as a genuine JPEG (flattened onto a white background via `java.awt.image`/`ImageIO`, since JPEG has no alpha channel) and regenerating its base64 in `data.sql` via `base64 -w0`, matching the original generation method in `SPEC-seed-data.md` §4.

### 3.4 Future-proofing (not built)

If a non-JPEG asset is ever added, a nullable `mime_type` column on `asset_images` is a safe low-risk addition — `spring.jpa.hibernate.ddl-auto=update` can add a nullable column without a migration tool, defaulting existing rows to `image/jpeg`. Not built now since every current asset is a JPEG; listed here so a future change doesn't have to rediscover this constraint.

---

## 4. Availability computation

### 4.1 Why the join can't start from `Booking`

`Booking` has no FK to `Asset` — only `BookingItem` links `asset_id` and `booking_id` together (`SPEC-entity-repository.md` §7). The overlap query therefore lives on `BookingItemRepository`, joining through to `Booking` for status/dates:

```java
@Query("""
    SELECT DISTINCT bi.asset.id
    FROM BookingItem bi
    JOIN bi.booking b
    WHERE bi.asset.id IN :assetIds
      AND b.status IN :activeStatuses
      AND b.startDate <= :endDate
      AND b.endDate >= :startDate
    """)
Set<Long> findAssetIdsWithOverlappingBooking(...);
```

### 4.2 Blocking statuses

`PENDING_DEPOSIT`, `PENDING_CONFIRMED`, `CONFIRMED`, `MOBILISED` count as blocking (the slot is reserved or in progress) — see `AssetService.ACTIVE_BOOKING_STATUSES`. `COMPLETED` and `CANCELLED` do not.

### 4.3 Date window defaults

`browse()` and `getById()` accept optional `startDate`/`endDate` query params:

| Params given | Behavior |
|---|---|
| Neither | Defaults to `LocalDate.now()` for both — "available today" |
| Both | Uses the given window |
| Only one | `400 Bad Request` — "Both startDate and endDate must be provided together" |

---

## 5. Query efficiency

Given `spring.jpa.open-in-view=false` (`application.properties`), every lazy association (`Asset.category`, images, booking overlap) **must** be resolved inside the `@Transactional(readOnly = true)` service method — deferring to the controller or to Jackson serialization would throw `LazyInitializationException`.

Since transaction boundaries already have to be handled carefully for that reason, `browse()` batches rather than loops per-asset:

- `AssetRepository.findAllWithCategory()` — `JOIN FETCH a.category` for the unfiltered path, avoiding one lazy load per row. Filtered paths (`findByCategoryId`, `findByNameContainingIgnoreCase`, `findByCondition`) still lazy-load `category` per row — acceptable at the current 27-asset fleet size; worth batching if the catalog grows further.
- `AssetImageRepository.findByAssetIdIn(assetIds)` — one query for all matching assets' images, collected in-memory into a `Map<assetId, AssetImage>` (each asset now has at most one image row — see §3.2.1).
- `BookingItemRepository.findAssetIdsWithOverlappingBooking(assetIds, ...)` — one query for the whole result set's availability.

Net effect: an unfiltered `browse()` call is **3 queries total**, not one-per-asset.

Category/search/condition filters compose by successive in-memory `.filter()` over the base list (no repository method exists for combined multi-field queries) — acceptable given the catalog is 8 rows.

---

## 6. Delete semantics

No entity in this data model has `@OneToMany`/cascade (`SPEC-entity-repository.md` §7/§10). Deleting an `Asset` with dependent `asset_images`, `booking_items`, `rental_plan_records`, or `recommendation_items` rows would otherwise fail with a raw `DataIntegrityViolationException` / DB FK error.

`AssetService.delete(id)`:

1. `404` via `ResponseStatusException` if the asset doesn't exist.
2. Deletes the asset's own `AssetImage` rows first (images belong solely to the asset being deleted, safe to remove unconditionally).
3. Attempts `assetRepository.deleteById(id)`.
4. Catches `DataIntegrityViolationException` (e.g. from `booking_items`/`rental_plan_records`/`recommendation_items` still referencing this asset) and rethrows as `ResponseStatusException(HttpStatus.CONFLICT, "Asset has associated bookings/records and cannot be deleted")` — reuses the existing `RestExceptionHandler` `ResponseStatusException` branch, no new exception class.

---

## 7. API contracts

### 7.1 `GET /api/equipment`

```http
GET /api/equipment?category=Excavator&search=cat&condition=GOOD&startDate=2026-08-10&endDate=2026-08-15 HTTP/1.1
Authorization: Bearer <access-jwt>
```

All query params optional. `category` matches `AssetCategory.name` exactly (400 if unknown); `search` is a case-insensitive substring match on `Asset.name`; `condition` matches `ConditionType` case-insensitively (400 if invalid); `startDate`/`endDate` are ISO dates, must be given together or omitted together (see §4.3).

**Success `200`** — array of `EquipmentResponse`:

```json
[
  {
    "id": 1,
    "name": "CAT 320 Excavator",
    "category": "Excavator",
    "baseDailyRate": 450.00,
    "minDailyRate": 400.00,
    "maxDailyRate": 500.00,
    "capacity": null,
    "platformHeight": null,
    "purchaseYear": 2021,
    "condition": "GOOD",
    "available": true,
    "desc": "...",
    "img": "data:image/jpeg;base64,/9j/4AAQSkZJRg...",
    "location": "Tuas"
  }
]
```

`img` is `null` for an asset with no image row (e.g. a freshly created one — see §7.3).

### 7.2 `GET /api/equipment/{id}`

Same optional `startDate`/`endDate` params. `404` if the id doesn't exist. Returns a single `EquipmentResponse`.

### 7.3 `POST /api/equipment`

```http
POST /api/equipment HTTP/1.1
Authorization: Bearer <access-jwt>
Content-Type: application/json

{
  "name": "New Excavator",
  "serialno": "SN-12345",
  "categoryId": 1,
  "baseDailyRate": 400.00,
  "minDailyRate": 350.00,
  "maxDailyRate": 450.00,
  "capacity": null,
  "platformHeight": null,
  "purchaseYear": 2024,
  "condition": "EXCELLENT",
  "desc": "Brand new unit",
  "location": "Tuas"
}
```

**DTO:** `EquipmentRequest(name, serialno, categoryId, baseDailyRate, minDailyRate, maxDailyRate, capacity, platformHeight, purchaseYear, condition, desc, location)`

`201 Created` — `EquipmentResponse` with `img: null` and `available: true` (no image/bookings exist yet for a newly created asset). `400` if `categoryId` is missing or unknown.

### 7.4 `PUT /api/equipment/{id}` / `PATCH /api/equipment/{id}`

Same `EquipmentRequest` body. `PUT` replaces every field unconditionally; `PATCH` only overwrites fields present (non-null) in the request body. Both return `200` with the updated `EquipmentResponse` (images unchanged, `available` recomputed for "today"). `404` if the id doesn't exist; `400` if `categoryId`/`condition` given but invalid.

### 7.5 `DELETE /api/equipment/{id}`

`204 No Content` on success. `404` if the id doesn't exist. `409` if dependent rows block the delete (see §6).

### 7.6 Shared errors

```json
{ "error": "<code>", "message": "<reason>" }
```

| HTTP | Typical `error` |
|------|-----------------|
| `400` | `bad_request` |
| `401` | `unauthorized` (no/invalid Bearer — same posture as every other route) |
| `404` | `not_found` |
| `409` | `conflict` |

---

## 8. Verification

### 8.1 Checklist

- [ ] `./mvnw compile` (or `spring-boot:run`) builds with no errors.
- [ ] No Bearer → `401` on every route.
- [ ] `GET /api/equipment` with valid access token → `200`, 27 seeded assets, each `img` value starts with `data:image/jpeg;base64,`.
- [ ] An `img` value, base64-decoded after stripping the prefix, is a valid JPEG (including asset id 4 — previously the mislabeled PNG).
- [ ] `GET /api/equipment/{id}?startDate=...&endDate=...` reflects real booking overlap (seeded `booking_items`/`bookings` data — see `SPEC-seed-data.md` §6.6/§6.7 — should show `available:false` for an asset/date window matching an active seeded booking).
- [ ] `category=<unknown>` → `400`.
- [ ] `startDate` given without `endDate` (or vice versa) → `400`.
- [ ] `DELETE` on an asset referenced by seeded `booking_items` → `409`, not a raw DB error.
- [ ] `DELETE` on an asset with no dependents → `204`, then a subsequent `GET` on that id → `404`.

### 8.2 Manual test with curl

```bash
cd heavy-rental-spring-rest-api
./mvnw spring-boot:run

# no auth -> 401
curl -i http://localhost:8080/api/equipment

# auth flow (see SPEC-auth-login-logout.md for the two-step interim -> access flow)
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
ACCESS=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" -H "Content-Type: application/json" \
  -d '{"email":"alex.tan@example.sg","password":"customer123"}' | jq -r .accessToken)

# browse -> confirm 27 assets, each img value starts with "data:image/jpeg;base64,"
curl -s http://localhost:8080/api/equipment -H "Authorization: Bearer $ACCESS" | jq '.[0]'

# confirm an image actually decodes to a real JPEG
curl -s http://localhost:8080/api/equipment -H "Authorization: Bearer $ACCESS" \
  | jq -r '.[0].img[0]' | sed 's/^data:image\/jpeg;base64,//' | base64 -d > /tmp/check.jpg
file /tmp/check.jpg   # expect: JPEG image data

# single asset + availability window
curl -s "http://localhost:8080/api/equipment/1?startDate=2026-08-10&endDate=2026-08-15" \
  -H "Authorization: Bearer $ACCESS" | jq .

# delete-with-dependents -> expect 409
curl -i -X DELETE http://localhost:8080/api/equipment/1 -H "Authorization: Bearer $ACCESS"
```

### 8.3 Frontend integration check (once the frontend repo is pointed at this backend)

Confirm the "Browse Equipment" cards render real photos directly from `img` with **no client-side transformation** — that's the signal the data-URI prefix is correctly placed server-side, not something the frontend has to compensate for.

---

## 9. Key decisions

| Decision | Rationale |
|----------|-----------|
| Data-URI prefix added server-side, in the service | Keeps entities/DTOs free of HTTP-representation logic; frontend needs zero image-handling code |
| `img` is a single `String`, not `List<String>` | Matches the frontend's actual `Equipment.img: string` type exactly — verified against `src/app/types.ts`, not assumed |
| CAT 320's second photo deleted from seed data, not just hidden by the API | Since the contract only ever exposes one photo per asset, keeping an unreachable second row would be dead data with no future use under this contract |
| Hardcode `image/jpeg`, no `mimeType` column | Every seeded file is a verified real `.jpg` (checked via magic bytes); adding a column now would be speculative |
| Fixed the mislabeled PNG (asset id 4) by re-encoding to real JPEG, not by adding per-image MIME tracking | Simpler fix given only one file was wrong; a `mimeType` column is still the right call *if* a non-JPEG asset is ever legitimately added (see §3.4) |
| Overlap query lives on `BookingItemRepository`, not `BookingRepository` | Only `BookingItem` has an `asset_id` FK |
| `PENDING_DEPOSIT`/`PENDING_CONFIRMED`/`CONFIRMED`/`MOBILISED` block availability; `COMPLETED`/`CANCELLED` don't | Matches real-world booking lifecycle — a completed or cancelled booking no longer holds the asset |
| Default date window to "today" when neither given | Lets the frontend call `GET /api/equipment` with no params and still get a meaningful `available` flag, matching the mock API's always-present field |
| Batch image/availability lookups instead of per-asset loops | `open-in-view=false` already forces careful transaction-scoped mapping; batching is barely more code and cuts query count from ~17 to 3 |
| Delete asset's own images first, then catch `DataIntegrityViolationException` | No cascade exists anywhere in this schema; this is the only path to a clean `409` instead of a raw DB error |
| Contract paths match the frontend's existing mock exactly (`/api/equipment`, `/api/equipment/{id}`) | Zero frontend path changes required |
| No `SecurityConfig` changes | Existing catch-all `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` rule already covers new routes |

---

## 10. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.0.0 | 2026-08-06 | Initial SPEC: `/api/equipment` CRUD, base64→data-URI image encoding decision, batched availability/image lookups, delete-conflict handling. Code written; build and manual verification (§8) not yet run. |
| 1.1.0 | 2026-08-06 | Verified against the frontend's actual `Equipment` type: `img` corrected from `List<String>` to a single `String` (§3.2). Fixed a pre-existing data bug found during review — the seed image for asset id 4 was a PNG mislabeled `.jpg`, re-encoded to a real JPEG (§3.3). Removed CAT 320 Excavator's second seeded photo from `data.sql` per user decision, since the API only ever exposes one photo per asset (§3.2.1). Build verified (`mvnw compile`, exit 0). |
| 1.1.1 | 2026-08-09 | Added `location` to both `EquipmentRequest`/`EquipmentResponse` examples (§7.1, §7.3) and the `EquipmentRequest` DTO signature — a field added to `Asset`/both DTOs in this same PR that was missing from this spec's contract examples. No behavior change. |
| 1.2.0 | 2026-08-11 | Doc-only corrections, no code change: (1) §2.2/§5 asset-count scale ceiling updated from 8 to a planned 16-asset fleet, and the stale "9 seeded images / ~1.85MB" figures (left over from before the CAT 320 second-photo removal already reflected in §3.2.1) corrected to the current 8-image/~1.3MB baseline, per review of `specification/temporary/data-seeding-spec` (not yet executed — the live fleet is still 8 assets as of this note). (2) §4.2/§9 corrected stale `PENDING` status wording to the actual `PENDING_DEPOSIT`/`PENDING_CONFIRMED` split from `HR-77` — the code (`AssetService.ACTIVE_BOOKING_STATUSES`) was already correct, only this doc's prose was stale. The §8 QA checklist/curl script's literal "8 seeded assets" text is intentionally left as-is until the reseed actually executes and `SPEC-seed-data.md` is updated to match. |
| 1.3.0 | 2026-08-11 | `specification/temporary/data-seeding-spec` revised again (still not executed): the planned fleet target grew from 16 to 27 assets, to give every category's spec-band real coverage instead of leaving most bands empty. §2.2/§5 scale-ceiling wording and image-size estimate updated accordingly (16→28-asset ceiling, ~2.6MB→~4.4MB). No other change. |
| 1.4.0 | 2026-08-11 | The planned reseed executed: `data.sql` now seeds 27 assets (up from 8), per `SPEC-seed-data.md` 2.0.0. §2.2/§5 updated from planned/ceiling language to the actual current numbers (27 assets, ~4.6MB embedded images). §8 QA checklist and curl script's "8 seeded assets" updated to 27 — no longer deferred, since the fleet this doc describes is now real. `specification/temporary/data-seeding-spec`/`design.md` removed as part of the same change (their content is now durably captured in `SPEC-seed-data.md`). No code change. |
