# Specification: Equipment Browse & CRUD API

| Field | Value |
|-------|--------|
| **Feature** | REST API for browsing and managing rentable equipment (`Asset`), including photos |
| **Status** | Implemented, build + full test suite verified (`mvnw test`, 61/61 passing including new `AssetAdminIntegrationTest`) — see §8 |
| **Module** | `heavy-rental-spring-rest-api` |
| **Endpoints** | `GET/POST /api/assets`, `GET/PUT/PATCH/DELETE /api/assets/{id}`, `PUT /api/assets/{id}/image` |
| **Depends on** | [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) (`Asset`/`AssetCategory`/`AssetImage`/`Booking`/`BookingItem`), [`SPEC-seed-data.md`](./SPEC-seed-data.md) (image encoding provenance), [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) (access token required to call these routes) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |
| **Related code** | `controller/AssetController.java`, `service/AssetService.java`, `dto/AssetResponse.java`, `dto/AssetRequest.java`, `dto/AssetImageRequest.java`, `repository/AssetRepository.java`, `repository/AssetImageRepository.java`, `repository/BookingItemRepository.java` |

This document is the **single source of truth** for the `/api/assets` REST surface (renamed from `/api/equipment` 2026-08-13 — see §9/§10): what it returns, how equipment photos are encoded for the client, how availability is computed, and delete semantics given this codebase has no cascading deletes.

---

## 1. Purpose & outcomes

Before this feature, the backend had no equipment-facing endpoint — the sibling frontend (`heavy-rental-react-web-portal`) browsed equipment against a mock JSON server. This feature exposes the real `Asset`/`AssetCategory`/`AssetImage` data through a contract matching the frontend's existing mock shape, so the frontend's "Browse Equipment" page can point at this backend and render equipment cards with real photos, no frontend code changes required beyond auth wiring (out of scope here — see §3.2).

When this feature is correct:

1. `GET /api/assets` returns every asset (optionally filtered) with a photo array the frontend can drop directly into `<img src="...">`, with no client-side transformation.
2. `available` reflects real booking overlap for a given date window, not a hardcoded value.
3. Deleting an asset with dependent rows fails with a clear `409`, not a raw DB stack trace.
4. Writes (`POST`/`PUT`/`PATCH`/`DELETE`/image upload) are restricted to `ROLE_ADMIN`; `GET` remains open to any authenticated user (**changed 2026-08-13** — see §9/§10; originally this feature reused the existing catch-all rule with no admin distinction).

---

## 2. Scope

### 2.1 In scope

- `GET /api/assets` — list/browse with optional `category`, `search`, `condition`, `startDate`, `endDate` filters.
- `GET /api/assets/{id}` — single asset lookup.
- `POST /api/assets` — create (`ROLE_ADMIN` only).
- `PUT /api/assets/{id}` — full replace (`ROLE_ADMIN` only).
- `PATCH /api/assets/{id}` — partial update (`ROLE_ADMIN` only).
- `DELETE /api/assets/{id}` — delete, with dependent-row conflict handling (`ROLE_ADMIN` only).
- `PUT /api/assets/{id}/image` — upload/replace an asset's photo (`ROLE_ADMIN` only, added 2026-08-13 — see §7.6).
- Server-side conversion of stored base64 image data into browser-renderable data URIs.
- Availability computation from `BookingItem`/`Booking` overlap.
- Duplicate-`name` conflict handling on create/replace/rename-via-patch (added 2026-08-13 — see §9).
- Bean-validation on required `AssetRequest` fields (added 2026-08-13 — see §9).

### 2.2 Out of scope

- Frontend changes — the React portal's `equipmentApi` still calls `/api/equipment` as of this change; updating it to `/api/assets` (and consuming the new `serialno`/`lastConditionUpdatedAt`/image-upload capability) is a separate, not-yet-scoped frontend change. See [`CHANGES-admin-asset-records.md`](./CHANGES-admin-asset-records.md).
- Pagination, thumbnails, or a separate image-only *read* endpoint — every `browse()` call returns full-size images inline for all matching assets (~4.6MB total across the 27 seeded images per `SPEC-seed-data.md` §6.3, as of that spec's 2.0.0 reseed). Acceptable at this scale; flagged as a future concern if the catalog grows further.
- An `AssetImage.mimeType` column — see §3.4.
- Admin CRUD for `AssetCategory` — only reads exist today; out of scope for this change.

---

## 3. Image encoding — the key design decision

### 3.1 Problem

`AssetImage.image` is a `TEXT` column storing **raw base64** (no `data:image/...;base64,` prefix) — decided when the asset catalog was designed (`SPEC-seed-data.md` §4) and unchanged here. A browser `<img src="...">` cannot render raw base64 directly; it needs either a URL or a complete [data URI](https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/data).

### 3.2 Decision

`AssetResponse.img` is a **single string** (`String`, not `List<String>`) — confirmed against the frontend's actual `Equipment` type (`src/app/types.ts` in `heavy-rental-react-web-portal`), which declares `img: string`. An earlier draft of this plan assumed an array without checking that type; corrected here.

`AssetService` fetches one `AssetImage` per asset (`firstImage(assetId)` / a batched `loadImageByAssetId` for `browse()`) and prepends `data:image/jpeg;base64,` when mapping to `AssetResponse.img`:

```java
private static final String JPEG_DATA_URI_PREFIX = "data:image/jpeg;base64,";

private String toDataUri(AssetImage image) {
    return image != null ? JPEG_DATA_URI_PREFIX + image.getImage() : null;
}
```

An asset with no image row returns `img: null`.

### 3.2.1 CAT 320 Excavator's second photo — removed from seed data

`asset_images` originally seeded 2 rows for CAT 320 Excavator (asset id 1) and 1 row for every other asset. Since the API now exposes exactly one photo per equipment item, the second CAT 320 row was deleted from `data.sql` rather than kept-but-unused — every asset now has exactly 1 `asset_images` row, 8 rows total (down from 9). See `SPEC-seed-data.md` §6.3 (updated in the same change).

This keeps `AssetImage` a pure persistence mapping (no HTTP-representation logic on the entity, consistent with every other entity in this codebase) and keeps `AssetResponse` a plain, behavior-free record (consistent with `LoginResponse`/`MessageResponse`). The service — which already owns all `Asset → AssetResponse` mapping — is where this one extra line belongs.

### 3.2.2 `AssetImage` write path (added 2026-08-13)

Before this change, `AssetImage` rows only ever came from seed data — there was no endpoint to create, replace, or remove one. `PUT /api/assets/{id}/image` (§7.6) closes that gap: `AssetService.uploadImage(id, request)` deletes any existing `AssetImage` row(s) for that asset (enforcing "at most one image per asset," matching `firstImage()`'s existing "first found" assumption) and inserts the new one. Request body is `{"image": "<raw base64, no data: prefix>"}` — a plain JSON string, consistent with how `AssetImage.image` is already stored, and consistent with this codebase using no multipart uploads anywhere else. Capped at 7,000,000 base64 characters (~5MB raw image) — `413 Payload Too Large` beyond that, enforced in `AssetService` and backed by `server.tomcat.max-http-form-post-size=10MB` in `application.properties` so Tomcat doesn't reject the request body before it reaches the controller.

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
| Neither | No window resolved; `available` is `null` in the response — no default to "today" (see §7.1's note; corrected 2026-08-13, was previously documented as defaulting to `LocalDate.now()`) |
| Both | Uses the given window; `available` is `true`/`false` |
| Only one | `400 Bad Request` — "Both startDate and endDate must be provided together" |

`resolveAvailabilityWindow(startDate, endDate)` (`AssetService.java`) returns `null`, not a two-`LocalDate.now()` window, when both params are omitted — `browse()`/`getById()` then pass `available = null` straight through to `AssetResponse` rather than computing today's overlap. `AssetResponse.available` is `Boolean` (nullable), not `boolean`, to carry that third state.

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

### 7.1 `GET /api/assets`

```http
GET /api/assets?category=Excavator&search=cat&condition=GOOD&startDate=2026-08-10&endDate=2026-08-15 HTTP/1.1
Authorization: Bearer <access-jwt>
```

Roles: `ROLE_USER`, `ROLE_ADMIN` (unrestricted read, unchanged by the 2026-08-13 admin-gating change — see §9).

All query params optional. `category` matches `AssetCategory.name` exactly (400 if unknown); `search` is a case-insensitive substring match on `Asset.name`; `condition` matches `ConditionType` case-insensitively (400 if invalid); `startDate`/`endDate` are ISO dates, must be given together or omitted together (see §4.3).

**Success `200`** — array of `AssetResponse`:

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
    "location": "Tuas",
    "tags": [],
    "serialno": "SN-EXC-2021-0001",
    "lastConditionUpdatedAt": "2026-08-13T09:15:00"
  }
]
```

`available: true` above reflects the example request's `startDate`/`endDate` params. When neither is given, `available` is `null` (see §4.3) rather than defaulting to a computed "today" value.

`img` is `null` for an asset with no image row (e.g. a freshly created one — see §7.3).

`tags` is always `[]` today — `AssetResponse.tags` exists on the DTO but `AssetService` never populates it from anything (`Asset` has no tags column/relation). Present in every response for forward-compatibility; not yet backed by real data.

`serialno` and `lastConditionUpdatedAt` **added 2026-08-13** — both were already columns on the `Asset` entity but were never returned by the response DTO before this change (the frontend's admin "Asset Records" tab had been synthesizing both client-side as a workaround). `lastConditionUpdatedAt` is `null` until `condition` is first set, then auto-stamped server-side (never client-supplied) whenever `condition` actually changes — see §9.

### 7.2 `GET /api/assets/{id}`

Roles: `ROLE_USER`, `ROLE_ADMIN`. Same optional `startDate`/`endDate` params. `404` if the id doesn't exist. Returns a single `AssetResponse`.

### 7.3 `POST /api/assets`

Roles: **`ROLE_ADMIN` only** (changed 2026-08-13 — previously any authenticated user; see §9).

```http
POST /api/assets HTTP/1.1
Authorization: Bearer <admin-access-jwt>
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

**DTO:** `AssetRequest(name, serialno, categoryId, baseDailyRate, minDailyRate, maxDailyRate, capacity, platformHeight, purchaseYear, condition, desc, location)` — `name`/`serialno` (`@NotBlank`) and `categoryId`/`baseDailyRate`/`minDailyRate`/`maxDailyRate` (`@NotNull`) are now bean-validated on `POST`/`PUT` (added 2026-08-13; not on `PATCH` — see §9).

`201 Created` — `AssetResponse` with `img: null` and `available: true` (no image/bookings exist yet for a newly created asset); `lastConditionUpdatedAt` set to the creation time if `condition` was given, else `null`. `400` if `categoryId` is missing/unknown, or if a `@NotBlank`/`@NotNull` field is missing. `409` if `name` collides with an existing asset (added 2026-08-13).

### 7.4 `PUT /api/assets/{id}` / `PATCH /api/assets/{id}`

Roles: **`ROLE_ADMIN` only** (changed 2026-08-13 — previously any authenticated user; see §9).

Same `AssetRequest` body (`PUT` is bean-validated like `POST`; `PATCH` deliberately is not, since its "null means unchanged" contract is incompatible with required-field validation). `PUT` replaces every field unconditionally; `PATCH` only overwrites fields present (non-null) in the request body. Both return `200` with the updated `AssetResponse` (images unchanged, `available` hardcoded `true` — same as `POST`, not date-window-computed; corrected 2026-08-13, was previously documented as "recomputed for today"). `404` if the id doesn't exist; `400` if `categoryId`/`condition` given but invalid; `409` if the (new) `name` collides with a different existing asset (added 2026-08-13 — applies to both a `PUT` rename and a `PATCH` that includes `name`).

Condition-change stamping (added 2026-08-13): if the request's `condition` differs from the asset's current condition, `lastConditionUpdatedAt` is set to the current server time; if it's the same value (or omitted on `PATCH`), the timestamp is left untouched.

### 7.5 `DELETE /api/assets/{id}`

Roles: **`ROLE_ADMIN` only** (changed 2026-08-13 — previously any authenticated user; see §9).

`204 No Content` on success. `404` if the id doesn't exist. `409` if dependent rows block the delete (see §6).

### 7.6 `PUT /api/assets/{id}/image` (added 2026-08-13)

Roles: **`ROLE_ADMIN` only**.

```http
PUT /api/assets/1/image HTTP/1.1
Authorization: Bearer <admin-access-jwt>
Content-Type: application/json

{ "image": "<raw base64, no data: prefix>" }
```

**DTO:** `AssetImageRequest(image)` — `image` is `@NotBlank`.

Replaces any existing photo(s) for the asset (at most one is kept — see §3.2.2) and returns `200` with the updated `AssetResponse`, whose `img` reflects the newly uploaded photo as a `data:image/jpeg;base64,...` URI. `404` if the asset doesn't exist. `400` if `image` is missing/blank. `413 Payload Too Large` if the base64 payload exceeds ~7,000,000 characters (~5MB raw image).

### 7.7 Shared errors

```json
{ "error": "<code>", "message": "<reason>" }
```

| HTTP | Typical `error` |
|------|-----------------|
| `400` | `bad_request` |
| `401` | `unauthorized` (no/invalid Bearer — same posture as every other route) |
| `403` | `forbidden` (non-admin token on an admin-only write route — added 2026-08-13) |
| `404` | `not_found` |
| `409` | `conflict` |
| `413` | `payload_too_large` (image upload only) |

---

## 8. Verification

### 8.1 Checklist

- [ ] `./mvnw compile` (or `spring-boot:run`) builds with no errors.
- [ ] No Bearer → `401` on every route.
- [ ] `GET /api/assets` with valid access token → `200`, 27 seeded assets, each `img` value starts with `data:image/jpeg;base64,`.
- [ ] An `img` value, base64-decoded after stripping the prefix, is a valid JPEG (including asset id 4 — previously the mislabeled PNG).
- [ ] `GET /api/assets/{id}?startDate=...&endDate=...` reflects real booking overlap (seeded `booking_items`/`bookings` data — see `SPEC-seed-data.md` §6.6/§6.7 — should show `available:false` for an asset/date window matching an active seeded booking).
- [ ] `category=<unknown>` → `400`.
- [ ] `startDate` given without `endDate` (or vice versa) → `400`.
- [ ] `DELETE` on an asset referenced by seeded `booking_items` → `409`, not a raw DB error.
- [ ] `DELETE` on an asset with no dependents → `204`, then a subsequent `GET` on that id → `404`.
- [ ] A non-admin (`ROLE_USER`) token: `GET /api/assets` → `200`; `POST`/`PUT`/`PATCH`/`DELETE /api/assets*` and `PUT /api/assets/{id}/image` → `403` (added 2026-08-13).
- [ ] An admin (`ROLE_ADMIN`) token: all six write verbs succeed (added 2026-08-13).
- [ ] Duplicate `name` on `POST`/`PUT` → `409` (added 2026-08-13).
- [ ] Automated coverage for all of the above: `./mvnw test -Dtest=AssetAdminIntegrationTest` (added 2026-08-13, 15 tests).

### 8.2 Manual test with curl

```bash
cd heavy-rental-spring-rest-api
./mvnw spring-boot:run

# no auth -> 401
curl -i http://localhost:8080/api/assets

# auth flow (see SPEC-auth-login-logout.md for the two-step interim -> access flow)
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
ACCESS=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" -H "Content-Type: application/json" \
  -d '{"email":"alex.tan@example.sg","password":"customer123"}' | jq -r .accessToken)

# admin token (writes require ROLE_ADMIN as of 2026-08-13)
ADMIN_INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
ADMIN_ACCESS=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $ADMIN_INTERIM" -H "Content-Type: application/json" \
  -d '{"email":"ravi.kumar@example.sg","password":"admin123"}' | jq -r .accessToken)

# browse -> confirm 27 assets, each img value starts with "data:image/jpeg;base64,"
curl -s http://localhost:8080/api/assets -H "Authorization: Bearer $ACCESS" | jq '.[0]'

# confirm an image actually decodes to a real JPEG
curl -s http://localhost:8080/api/assets -H "Authorization: Bearer $ACCESS" \
  | jq -r '.[0].img[0]' | sed 's/^data:image\/jpeg;base64,//' | base64 -d > /tmp/check.jpg
file /tmp/check.jpg   # expect: JPEG image data

# single asset + availability window
curl -s "http://localhost:8080/api/assets/1?startDate=2026-08-10&endDate=2026-08-15" \
  -H "Authorization: Bearer $ACCESS" | jq .

# non-admin write attempt -> expect 403
curl -i -X POST http://localhost:8080/api/assets -H "Authorization: Bearer $ACCESS" \
  -H "Content-Type: application/json" -d '{"name":"x","serialno":"x","categoryId":1,"baseDailyRate":1,"minDailyRate":1,"maxDailyRate":1}'

# admin image upload
curl -s -X PUT http://localhost:8080/api/assets/1/image -H "Authorization: Bearer $ADMIN_ACCESS" \
  -H "Content-Type: application/json" -d "{\"image\":\"$(base64 -w0 some-photo.jpg)\"}" | jq '.img[0:40]'

# delete-with-dependents -> expect 409
curl -i -X DELETE http://localhost:8080/api/assets/1 -H "Authorization: Bearer $ADMIN_ACCESS"
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
| `available` is `null` (not defaulted to "today") when no date window is given | `AssetResponse.available` is nullable specifically to let the frontend distinguish "no availability computed" from a real `true`/`false` — corrected 2026-08-13, this row previously described a `LocalDate.now()` default that isn't what the code does |
| Batch image/availability lookups instead of per-asset loops | `open-in-view=false` already forces careful transaction-scoped mapping; batching is barely more code and cuts query count from ~17 to 3 |
| Delete asset's own images first, then catch `DataIntegrityViolationException` | No cascade exists anywhere in this schema; this is the only path to a clean `409` instead of a raw DB error |
| **(2026-08-13)** Route family renamed `/api/equipment` → `/api/assets`; `EquipmentController`/`EquipmentRequest`/`EquipmentResponse` → `AssetController`/`AssetRequest`/`AssetResponse` | Unifies naming with the `Asset`/`AssetService`/`AssetRepository` names already used underneath; user-directed rename, done as part of formalizing this as an admin feature rather than leaving the split "equipment" (API) / "asset" (code) terminology in place |
| **(2026-08-13)** Write verbs gated `ROLE_ADMIN`; `GET` stays open to any authenticated user via a new set of `SecurityConfig` per-method matchers | Closes a real authorization gap — previously any authenticated customer could create/edit/delete assets, not just admins (flagged in `SPEC-api-index.md` §2.3); `GET` stays open since it also serves the public customer-facing browse feature |
| **(2026-08-13)** Duplicate-`name` conflict handled via an explicit `existsByName`/`existsByNameAndIdNot` pre-check → `409`, not a caught `DataIntegrityViolationException` | Mirrors `UserAdminService.create`'s `existsByEmail` idiom (the pattern `SPEC-admin-users-api.md` establishes for this codebase) rather than the delete path's catch-the-DB-exception approach — gives a clearer, field-specific error message |
| **(2026-08-13)** `lastConditionUpdatedAt` auto-stamped server-side only when `condition` actually changes, never client-supplied | Keeps the timestamp trustworthy as a real "last inspected/updated" signal; a no-op `PATCH` re-sending the same condition must not look like a fresh inspection |
| **(2026-08-13)** New `PUT /api/assets/{id}/image`, JSON body with a raw base64 string, not multipart | Nothing else in this codebase uses multipart uploads; a small JSON DTO (`AssetImageRequest`) is more consistent and keeps the request shape uniform with every other write endpoint here |
| **(2026-08-13)** `AssetRequest` gets `@NotBlank`/`@NotNull` on `POST`/`PUT` only, not `PATCH` | `PATCH`'s "null means unchanged" contract is incompatible with required-field validation; validating `POST`/`PUT` (which previously had none) closes a real gap where a malformed admin request could NPE deep in the service or silently persist nulls into `NOT NULL` columns |

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
| 1.5.0 | 2026-08-13 | **Doc-only corrections against `AssetService.java`, no code change.** §4.3/§7.1/§9 previously said the availability window defaults to `LocalDate.now()`/"today" when neither `startDate` nor `endDate` is given; `resolveAvailabilityWindow` actually returns `null` in that case and `browse()`/`getById()` pass `available: null` straight through — never a computed "today" value. §7.4 previously said `PUT`/`PATCH` "recompute" `available` for today; they actually hardcode `true`, same as `POST` (§7.3), with no date logic at all. §7.1's example response and DTO discussion updated to note the always-empty `tags` field, present on `EquipmentResponse` but never populated by `AssetService` (no backing column on `Asset`) — previously undocumented. |
| 1.6.0 | 2026-08-13 | **Cross-check requested by a web-portal API audit, re-verified — no divergence found.** `GET /api/equipment`'s `startDate`/`endDate` query params (`EquipmentController.browse`/`getById`, both `@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate`) were read directly against this file's §4.3/§7.1/§7.2 line by line: `resolveAvailabilityWindow` (`AssetService.java:195`) returns `null` on neither param, throws `400 Bad Request` "Both startDate and endDate must be provided together" on exactly one, and returns the real `[startDate, endDate]` window on both — matching §4.3's table exactly, including the two corrections already made in 1.5.0 above. No code change; this entry exists to record that the cross-check happened and passed, closing the "never cross-checked" gap the audit flagged. |
| 2.0.0 | 2026-08-13 | **Admin asset records: route renamed, writes gated `ROLE_ADMIN`, new image endpoint, missing fields returned.** `/api/equipment` → `/api/assets`; `EquipmentController`/`EquipmentRequest`/`EquipmentResponse` → `AssetController`/`AssetRequest`/`AssetResponse`. `SecurityConfig` now restricts `POST`/`PUT`/`PATCH`/`DELETE /api/assets/**` to `ROLE_ADMIN` (previously the unrestricted catch-all — closes the gap `SPEC-api-index.md` §2.3 flagged); `GET` unchanged. New `PUT /api/assets/{id}/image` (§7.6, §3.2.2) persists an admin-uploaded photo as an `AssetImage` row — no write path existed for this before. `AssetResponse` now returns `serialno`/`lastConditionUpdatedAt` (§7.1), both previously on the entity but never surfaced; `lastConditionUpdatedAt` auto-stamps only on a real condition change (§7.4). Duplicate-`name` pre-check added on create/replace/rename-via-patch → `409` (§7.3/§7.4). Bean validation (`@NotBlank`/`@NotNull`) added to `AssetRequest` for `POST`/`PUT` (§7.3/§7.4), backed by a new `MethodArgumentNotValidException` handler in `RestExceptionHandler`. New `AssetAdminIntegrationTest` (15 tests) covers admin-vs-non-admin access on every write verb, the image endpoint, the condition-stamp behavior (including the no-op-patch case), and duplicate-name conflicts — first automated test coverage this route family has ever had. Full narrative in new [`CHANGES-admin-asset-records.md`](./CHANGES-admin-asset-records.md). Frontend (`equipmentApi`, still pointed at `/api/equipment`) intentionally not updated in this change — backend-only pass, per user direction. |
