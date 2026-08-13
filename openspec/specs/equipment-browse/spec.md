# Equipment Browse & CRUD — Source of Truth

## Purpose

Expose rentable equipment (`Asset` / category / image) for browse and admin-style CRUD, with browser-ready photo data URIs and booking-overlap availability.

**Status:** **As-built**  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** access JWT (`ROLE_USER` or `ROLE_ADMIN`) — blanket SecurityConfig rule

## Requirements

### Requirement: FR-EQ-001 Browse and get equipment

The system MUST provide `GET /api/equipment` (optional filters) and `GET /api/equipment/{id}` returning `EquipmentResponse` including rates, condition, location, and `img` as a JPEG data URI or null.

#### Scenario: List with filters
- GIVEN a valid access Bearer
- WHEN `GET /api/equipment` with optional `category`, `search`, `condition`, `startDate`, `endDate`
- THEN `200` with an array of equipment rows matching filters
- AND unknown category or invalid condition yields `400`
- AND `startDate`/`endDate` must both be present or both omitted (else `400`)

#### Scenario: Missing id
- GIVEN a non-existent equipment id
- WHEN `GET /api/equipment/{id}`
- THEN `404`

### Requirement: FR-EQ-002 Image as data URI

Stored `AssetImage.image` is raw base64. The API MUST expose `EquipmentResponse.img` as `data:image/jpeg;base64,<raw>` (or null if no image). Exactly one primary image per asset is used for the response. The same data-URI convention MUST be used for haystack-recommender portal `items[].equipment.img` when `equipment.id` matches that asset.

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

### Requirement: FR-EQ-004 Create / update / patch

The system MUST support `POST /api/equipment`, `PUT /api/equipment/{id}` (full replace), and `PATCH /api/equipment/{id}` (partial) for authenticated callers.

#### Scenario: Create returns new id
- GIVEN a valid `EquipmentRequest`
- WHEN `POST /api/equipment`
- THEN the asset is persisted and returned

### Requirement: FR-EQ-005 Delete with dependency conflict

`DELETE /api/equipment/{id}` MUST remove the asset's own images first, then delete the asset. If FK dependents remain (bookings, plan records, etc.), the system MUST return `409` with a clear message, not a raw DB stack trace. Missing id → `404`.

#### Scenario: Asset with booking items cannot delete
- GIVEN an asset referenced by booking items
- WHEN `DELETE /api/equipment/{id}`
- THEN `409 Conflict`

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
