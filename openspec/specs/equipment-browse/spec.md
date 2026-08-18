# Equipment Browse & CRUD — Source of Truth

## Purpose

Expose rentable equipment (`Asset` / category / image) for browse and admin-only CRUD, with browser-ready photo data URIs and booking-overlap availability.

**Status:** **As-built**  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** `GET` — access JWT (`ROLE_USER` or `ROLE_ADMIN`), blanket SecurityConfig rule. Writes (`POST`/`PUT`/`PATCH`/`DELETE`/image upload) — `ROLE_ADMIN` only.

## Requirements

### Requirement: FR-EQ-001 Browse and get equipment

The system MUST provide `GET /api/assets` (optional filters) and `GET /api/assets/{id}` returning `AssetResponse` including rates, condition, location, `serialno`, `lastConditionUpdatedAt`, `utilization`, and `img` as a JPEG data URI or null.

#### Scenario: List with filters
- GIVEN a valid access Bearer
- WHEN `GET /api/assets` with optional `category`, `search`, `condition`, `startDate`, `endDate`
- THEN `200` with an array of asset rows matching filters
- AND unknown category or invalid condition yields `400`
- AND `startDate`/`endDate` must both be present or both omitted (else `400`)

#### Scenario: Missing id
- GIVEN a non-existent asset id
- WHEN `GET /api/assets/{id}`
- THEN `404`

### Requirement: FR-EQ-002 Image as data URI

Stored `AssetImage.image` is raw base64. The API MUST expose `AssetResponse.img` as `data:image/jpeg;base64,<raw>` (or null if no image). Exactly one primary image per asset is used for the response. The same data-URI convention MUST be used for haystack-recommender portal `items[].equipment.img` when `equipment.id` matches that asset.

#### Scenario: Renderable img
- GIVEN an asset with a JPEG base64 image row
- WHEN equipment is returned
- THEN `img` starts with `data:image/jpeg;base64,`

### Requirement: FR-EQ-003 Availability from booking overlap

When both `startDate` and `endDate` are provided, `available` MUST be true/false from overlap with active bookings (`PENDING_DEPOSIT`, `PENDING_CONFIRMED`, `CONFIRMED`, `MOBILISED`). When neither date is provided, `available` MUST be `null` (not defaulted to today).

#### Scenario: No window → null available
- GIVEN browse without date params
- WHEN the response is built
- THEN `available` is null

#### Scenario: Overlap blocks availability
- GIVEN an asset with an active booking overlapping the window
- WHEN browse/get uses that window
- THEN `available` is false for that asset

### Requirement: FR-EQ-004 Create / update / patch — admin only

The system MUST support `POST /api/assets`, `PUT /api/assets/{id}` (full replace), and `PATCH /api/assets/{id}` (partial), restricted to `ROLE_ADMIN` (`SecurityConfig` matchers on `/api/assets/**` for `POST`/`PUT`/`PATCH`/`DELETE`). A `ROLE_USER`-only caller MUST receive `403`.

#### Scenario: Create returns new id
- GIVEN a valid `AssetRequest` and an admin caller
- WHEN `POST /api/assets`
- THEN the asset is persisted and returned

#### Scenario: Non-admin write rejected
- GIVEN a `ROLE_USER`-only caller
- WHEN `POST`/`PUT`/`PATCH`/`DELETE /api/assets*`
- THEN `403`

### Requirement: FR-EQ-005 Delete with dependency conflict

`DELETE /api/assets/{id}` MUST remove the asset's own images first, then delete the asset. If FK dependents remain (bookings, plan records, etc.), the system MUST return `409` with a clear message, not a raw DB stack trace. Missing id → `404`.

#### Scenario: Asset with booking items cannot delete
- GIVEN an asset referenced by booking items
- WHEN `DELETE /api/assets/{id}`
- THEN `409 Conflict`

### Requirement: FR-EQ-007 Name uniqueness

`POST`/`PUT`/`PATCH /api/assets*` MUST reject a `name` that collides with a different existing asset with `409`, not a raw DB constraint violation. `PATCH` only checks when `name` is supplied and actually changes.

#### Scenario: Duplicate name on create
- GIVEN an existing asset named "CAT 320 Excavator"
- WHEN `POST /api/assets` with the same `name`
- THEN `409 Conflict`

### Requirement: FR-EQ-008 Condition-change timestamp

`lastConditionUpdatedAt` MUST be stamped with the current server time only when `condition` actually changes (create with a non-null condition, or a real change on replace/patch) — never client-supplied, never bumped by a no-op update.

#### Scenario: No-op update does not bump timestamp
- GIVEN an asset with `condition=GOOD` and a known `lastConditionUpdatedAt`
- WHEN `PATCH /api/assets/{id}` is sent with `condition=GOOD` (unchanged) or without `condition`
- THEN `lastConditionUpdatedAt` is unchanged

### Requirement: FR-EQ-009 Photo upload — admin only

`PUT /api/assets/{id}/image` (`ROLE_ADMIN` only) MUST persist an uploaded photo as an `AssetImage` row, replacing any prior image for that asset. The request body's `image` field MUST be a non-blank base64 string; the system MUST reject blank input (`400`) and oversized payloads (`413`, ~7MB base64 cap).

#### Scenario: Upload replaces existing photo
- GIVEN an asset with an existing image
- WHEN an admin `PUT /api/assets/{id}/image` with a new base64 image
- THEN the prior `AssetImage` row is deleted and the new one persisted, returned via `img` as a data URI

### Requirement: FR-EQ-006 Query efficiency under open-in-view false

With `spring.jpa.open-in-view=false`, browse MUST resolve category, images, and availability inside the transactional service (batched queries preferred), avoiding `LazyInitializationException`.

#### Scenario: Browse does not lazy-load outside TX
- GIVEN open-in-view disabled
- WHEN unfiltered browse runs
- THEN associations needed for the response are loaded in the service transaction

## Out of scope

- Pagination / separate image CDN endpoint  
- `mimeType` column (all current seed images JPEG)  
- Frontend auth wiring
