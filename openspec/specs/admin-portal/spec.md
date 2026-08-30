# Admin Portal — Asset Records — Source of Truth

## Purpose

Admin-only gating and admin-facing capabilities for the asset-records surface used by the React
admin portal's "Asset Records" tab: `ROLE_ADMIN`-only writes, a real serial number and
last-inspection timestamp, and a photo-upload endpoint. This is the first admin-portal feature
area documented under this capability; future admin-portal features can be added here as new
requirements.

**Status:** **As-built**. Automated evidence: `AssetAdminIntegrationTest` (see [`testing/contracts/test-inventory.md`](../testing/contracts/test-inventory.md)).
**HTTP shapes:** [`equipment-browse/contracts/api.md`](../equipment-browse/contracts/api.md) — this
capability governs *who* may call `/api/assets` writes and the admin-only fields/endpoint added to
that surface; it does not restate the wire contract, which lives with equipment-browse since it's
the same route family.
**Auth:** `ROLE_ADMIN` only for writes; `GET` stays open to any authenticated user (`ROLE_USER` or `ROLE_ADMIN`).
**Pattern mirrored:** [`admin-users/spec.md`](../admin-users/spec.md) — `ROLE_ADMIN`-only route family, duplicate-field `409` idiom.

## Why this feature exists

The living route family is `/api/assets` (`AssetController` / `AssetRequest` / `AssetResponse`).
It was renamed from `/api/equipment`. Admin-only writes, real `serialno` / `lastConditionUpdatedAt`,
and JSON base64 photo upload close the earlier gap where any authenticated user could mutate the
catalog and the API never returned those fields.

## Requirements

### Requirement: FR-AP-001 Admin-only writes, reads stay open

`POST`/`PUT`/`PATCH`/`DELETE /api/assets*` and `PUT /api/assets/{id}/image` MUST be restricted to
`ROLE_ADMIN`. `GET /api/assets` and `GET /api/assets/{id}` MUST remain open to any authenticated
user, since `/api/assets` also serves the public customer-facing browse feature.

#### Scenario: Non-admin write rejected
- GIVEN a `ROLE_USER`-only access token
- WHEN `POST`/`PUT`/`PATCH`/`DELETE /api/assets` (or `PUT /api/assets/{id}/image`) is called
- THEN `403 Forbidden`

#### Scenario: Reads unaffected
- GIVEN any authenticated token (`ROLE_USER` or `ROLE_ADMIN`)
- WHEN `GET /api/assets` or `GET /api/assets/{id}` is called
- THEN `200`, unchanged from before this feature

### Requirement: FR-AP-002 Real serial number and inspection timestamp

`AssetResponse` MUST return the asset's real `serialno` and `lastConditionUpdatedAt` (both
previously on the entity but never surfaced), and `lastConditionUpdatedAt` MUST be stamped with the
current server time only when `condition` actually changes — never client-supplied, never bumped by
a no-op update.

#### Scenario: Real condition change stamps the timestamp
- GIVEN an admin `PATCH`/`PUT` that changes `condition` to a genuinely different value
- WHEN the request is processed
- THEN `lastConditionUpdatedAt` is set to the current server time

#### Scenario: No-op condition patch does not stamp
- GIVEN an admin `PATCH` that re-sends the same `condition` value the asset already has, or omits `condition`
- WHEN the request is processed
- THEN `lastConditionUpdatedAt` is left unchanged

### Requirement: FR-AP-003 Photo upload

`PUT /api/assets/{id}/image` (admin only) MUST persist an uploaded photo as an `AssetImage` row,
replacing any prior photo for that asset (at most one photo per asset), given a non-blank base64
`image` string under the size cap (~7,000,000 characters).

#### Scenario: Upload replaces existing photo
- GIVEN an admin token and a base64-encoded image under the size cap
- WHEN `PUT /api/assets/{id}/image` is called
- THEN any existing photo for that asset is replaced and the response's `img` reflects the new photo as a `data:image/jpeg;base64,...` URI

#### Scenario: Oversized payload rejected
- GIVEN a payload over the size cap
- WHEN `PUT /api/assets/{id}/image` is called
- THEN `413 Payload Too Large`

### Requirement: FR-AP-004 Duplicate name rejected

`POST /api/assets`, `PUT /api/assets/{id}`, and a `PATCH` that renames MUST reject a `name` already
in use by a different asset with `409`, via an explicit pre-check (`AssetRepository.existsByName`/
`existsByNameAndIdNot`, mirroring `UserAdminService.create`'s `existsByEmail` idiom), not a raw DB
constraint violation.

#### Scenario: Duplicate name on create
- GIVEN an existing asset named "CAT 320 Excavator"
- WHEN `POST /api/assets` is called with the same `name`
- THEN `409 Conflict`

### Requirement: FR-AP-005 Required-field validation on full writes

A `POST`/`PUT` body missing a required field (`name`, `serialno`, `categoryId`, or any of the three
rate fields) MUST be rejected with `400 Bad Request` via `@Valid`/`jakarta.validation`, not left to
NPE deep in the service or silently persist `null` into a `NOT NULL` column. `PATCH` is deliberately
exempt — its "null means unchanged" contract is incompatible with required-field checks.

#### Scenario: Missing required field on create
- GIVEN a `POST /api/assets` body missing `serialno`
- WHEN the request is processed
- THEN `400 Bad Request`

## Design notes

- Route rename: `EquipmentController` → `AssetController` (`@RequestMapping("/api/assets")`);
  `EquipmentRequest`/`EquipmentResponse` → `AssetRequest`/`AssetResponse`. `AssetService` itself
  needed no renaming — only its DTO imports/signatures changed.
- `SecurityConfig`: per-`HttpMethod` matchers (`POST`/`PUT`/`PATCH`/`DELETE` on `/api/assets`/
  `/api/assets/**`) → `hasAuthority("ROLE_ADMIN")`, next to the existing `/api/users/**` line. `GET`
  is deliberately left off, falling through to the existing catch-all.
- Image upload is a JSON base64 body (`AssetImageRequest(image)`), not multipart — consistent with
  how `AssetImage.image` is already stored and with the rest of this codebase using no multipart
  uploads anywhere else. Backed by `server.tomcat.max-http-form-post-size=10MB`.

## Out of scope

- **Frontend clients** — this module owns `/api/assets` only. A client still calling `/api/equipment` will `404`.
- **`AssetCategory` admin CRUD** — only reads exist today.
- **Pagination or thumbnails** on the browse endpoint.

## Related

- [`equipment-browse/spec.md`](../equipment-browse/spec.md) + [`contracts/api.md`](../equipment-browse/contracts/api.md) — the underlying route family and full wire contract
- [`admin-users/spec.md`](../admin-users/spec.md) — mirrored `ROLE_ADMIN`-only / duplicate-field pattern
- [`../../AGENTS.md`](../../AGENTS.md)
