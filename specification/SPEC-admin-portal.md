# Specification: Admin Portal — Asset Records

| Field | Value |
|-------|--------|
| **Feature** | Admin Portal backend — Asset Records (the first admin-portal feature area documented under this file; future admin-portal features can be added as new sections here) |
| **Status** | Implemented and verified — 15/15 new tests pass, full suite 61/61, no regressions |
| **Module** | `heavy-rental-spring-rest-api` |
| **Primary surface** | `/api/assets` (renamed from `/api/equipment`; write verbs now `ROLE_ADMIN`-only) |
| **Related code** | `controller/AssetController.java`, `service/AssetService.java`, `dto/AssetRequest.java`, `dto/AssetResponse.java`, `dto/AssetImageRequest.java`, `repository/AssetRepository.java`, `config/SecurityConfig.java`, `config/RestExceptionHandler.java` |
| **Full API contract** | [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) §7 (request/response shapes, error codes, curl walkthrough) — this document covers requirements, design, and decisions; it does not restate the wire contract |
| **Change history** | [`CHANGES-admin-asset-records.md`](./CHANGES-admin-asset-records.md) (narrative log this spec was distilled from), [`SPEC-api-index.md`](./SPEC-api-index.md) §2.3/2.5.0 (route index) |
| **Pattern mirrored** | [`SPEC-admin-users-api.md`](./SPEC-admin-users-api.md) — `ROLE_ADMIN`-only route family, duplicate-field 409 idiom |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |

---

## 1. Why this feature exists

The React admin portal (`heavy-rental-react-web-portal`) has an "Asset Records" tab that already called the real `/api/equipment` endpoints, but two gaps stopped it from being a genuine admin feature:

1. **No admin restriction.** `SecurityConfig` had no matcher for the route, so writes fell through to the blanket `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` rule — any authenticated customer could create/edit/delete equipment, not just admins.
2. **Incomplete contract.** `Asset` already had `serialno`, `condition`, and `lastConditionUpdatedAt` columns and an `AssetImage` table for photos, but the API never returned `serialno`/`lastConditionUpdatedAt`, never stamped the timestamp, and had no endpoint to upload a photo. The frontend worked around this by synthesizing all three client-side (`deriveAssetRecord()`).

This feature closes both gaps and, per a user decision, renames the route family from `/api/equipment` to `/api/assets` to match the `Asset`/`AssetService`/`AssetRepository` naming already used underneath.

---

## 2. Outcomes

When this feature is correct:

1. Only an admin can create, edit, delete, or upload a photo for an asset; any authenticated user can still browse (REQ-1, REQ-2).
2. The API returns a real serial number and last-inspection timestamp instead of the frontend inventing them (REQ-3).
3. `lastConditionUpdatedAt` reflects genuine condition changes only — never a client-supplied value, never bumped by a no-op update (REQ-4).
4. An admin can persist a real photo against an asset, replacing any prior one (REQ-5).
5. Two assets can never silently collide on `name` (REQ-6), and a malformed admin write is rejected with a clear `400` instead of corrupting data or throwing deep in the service (REQ-7).

---

## 3. Scope

### 3.1 In scope

- Renaming `/api/equipment` → `/api/assets` (and the backing `EquipmentController`/`EquipmentRequest`/`EquipmentResponse` classes → `AssetController`/`AssetRequest`/`AssetResponse`).
- Gating `POST`/`PUT`/`PATCH`/`DELETE /api/assets/**` to `ROLE_ADMIN`; leaving `GET` open to any authenticated user.
- Returning `serialno`/`lastConditionUpdatedAt` on `AssetResponse`, with server-side timestamp stamping on real condition changes.
- A new `PUT /api/assets/{id}/image` endpoint for admin photo upload.
- Duplicate-`name` conflict handling (`409`) on create/replace/rename-via-patch.
- Bean validation on required `AssetRequest` fields for `POST`/`PUT`.
- First automated test coverage for this route family (`AssetAdminIntegrationTest`).

### 3.2 Out of scope

- **Frontend changes.** `heavy-rental-react-web-portal`'s `equipmentApi` still calls `/api/equipment`, which no longer exists — it will 404 until repointed at `/api/assets` in a follow-up change that also drops `deriveAssetRecord()`'s client-side synthesis now that the backend returns real data.
- **`AssetCategory` admin CRUD.** Only reads exist today; category management wasn't part of this feature.
- **Pagination or thumbnails** on the browse endpoint — unchanged from the prior `/api/equipment` behavior.

---

## 4. Requirements

### REQ-1: Admin-only writes

**GIVEN** a `ROLE_USER`-only access token
**WHEN** `POST`/`PUT`/`PATCH`/`DELETE /api/assets` (or `PUT /api/assets/{id}/image`) is called
**THEN** `403 Forbidden`.

### REQ-2: Reads stay open

**GIVEN** any authenticated token (`ROLE_USER` or `ROLE_ADMIN`)
**WHEN** `GET /api/assets` or `GET /api/assets/{id}` is called
**THEN** `200` — unchanged from before this feature, since `/api/assets` also serves the public customer-facing browse page.

### REQ-3: Serial number and inspection timestamp are real

**GIVEN** an asset with a `serialno` and a `condition` set
**WHEN** it's returned by any `/api/assets` route
**THEN** the response includes the real `serialno` and `lastConditionUpdatedAt` — both previously present on the entity but never surfaced.

### REQ-4: Condition-change timestamp stamping

**GIVEN** an admin `PATCH`/`PUT` that changes `condition` to a genuinely different value
**WHEN** the request is processed
**THEN** `lastConditionUpdatedAt` is set to the current server time (never client-supplied).

**GIVEN** an admin `PATCH` that re-sends the *same* `condition` value the asset already has
**WHEN** the request is processed
**THEN** `lastConditionUpdatedAt` is left unchanged — a no-op condition patch must not look like a fresh inspection.

### REQ-5: Photo upload

**GIVEN** an admin token and a base64-encoded image (no `data:` prefix) under ~7,000,000 characters
**WHEN** `PUT /api/assets/{id}/image` is called
**THEN** any existing photo for that asset is replaced (at most one photo per asset) and the response's `img` reflects the new photo as a `data:image/jpeg;base64,...` URI.

**GIVEN** a payload over the size cap
**WHEN** `PUT /api/assets/{id}/image` is called
**THEN** `413 Payload Too Large`.

### REQ-6: Duplicate name rejected

**GIVEN** an asset `name` that's already in use by a different asset
**WHEN** `POST /api/assets`, `PUT /api/assets/{id}`, or a `PATCH` that renames is called
**THEN** `409 Conflict`.

### REQ-7: Required-field validation on full writes

**GIVEN** a `POST`/`PUT` body missing a required field (`name`, `serialno`, `categoryId`, or any of the three rate fields)
**WHEN** the request is processed
**THEN** `400 Bad Request` — validated via `@Valid`/`jakarta.validation`, not left to NPE deep in the service or silently persist a `null` into a `NOT NULL` column. (Deliberately **not** enforced on `PATCH`, whose "null means unchanged" contract is incompatible with required-field checks.)

---

## 5. Design summary

- **Route rename**: `EquipmentController` → `AssetController` (`@RequestMapping("/api/assets")`); `EquipmentRequest`/`EquipmentResponse` → `AssetRequest`/`AssetResponse`. `AssetService` itself needed no renaming — it already used `Asset` naming; only its DTO imports/signatures changed.
- **`SecurityConfig`**: four new per-`HttpMethod` matchers (`POST`/`PUT`/`PATCH`/`DELETE` on `/api/assets`/`/api/assets/**`) → `hasAuthority("ROLE_ADMIN")`, placed next to the existing `/api/users/**` admin-only line. `GET` is deliberately left off, falling through to the existing catch-all.
- **Timestamp stamping** lives in `AssetService.create`/`replace`/`patch`, comparing old vs. new `condition` before deciding whether to stamp — see REQ-4 for exact semantics per method.
- **Image upload**: new `AssetImageRequest(image)` DTO — a plain JSON body with a raw base64 string, consistent with how `AssetImage.image` is already stored and with this codebase using no multipart uploads anywhere else. `AssetService.uploadImage` deletes any existing `AssetImage` row(s) for the asset before inserting the new one, enforcing "at most one image per asset." Backed by `server.tomcat.max-http-form-post-size=10MB` in `application.properties` so Tomcat doesn't reject the body before it reaches the controller.
- **Duplicate-name check**: explicit `AssetRepository.existsByName`/`existsByNameAndIdNot` pre-checks → `409`, mirroring `UserAdminService.create`'s `existsByEmail` idiom rather than catching the DB constraint violation.
- **Validation**: `spring-boot-starter-validation` added to `pom.xml` (first use of `jakarta.validation` in this codebase); a new `RestExceptionHandler.handleValidation` maps `MethodArgumentNotValidException` to the existing `{"error":"bad_request","message":"..."}` envelope.

Full request/response shapes, status codes, and curl examples: [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) §7–§8.

---

## 6. Verification

- [x] `AssetAdminIntegrationTest` (15 tests): admin create/replace/patch/delete/upload-image all succeed; the same five operations return `403` for a non-admin; `GET` stays `200` for a non-admin; a real condition change stamps the timestamp and a no-op patch doesn't; duplicate name → `409` on create and replace.
- [x] Full suite: 61/61 passing, no regressions (`./mvnw test`).
- [x] Manual curl walkthrough — see `SPEC-equipment-browse-api.md` §8.2 (admin login via `ravi.kumar@example.sg`/`admin123`, non-admin write attempt → `403`, image upload, duplicate-name → `409`).

```bash
cd heavy-rental-spring-rest-api
./mvnw test -Dtest=AssetAdminIntegrationTest   # 15 tests
./mvnw test                                    # full suite
```

---

## 7. Key decisions

| Decision | Rationale |
|----------|-----------|
| Renamed `/api/equipment` → `/api/assets` rather than adding a parallel admin route | Unifies naming with the `Asset`/`AssetService`/`AssetRepository` code already underneath; avoids two routes for one resource |
| `GET` stays open to any authenticated user; only writes gated `ROLE_ADMIN` | `/api/assets` also serves the public customer-facing browse feature — locking down `GET` would break that |
| Duplicate-name handled via explicit pre-check, not a caught `DataIntegrityViolationException` | Mirrors the existing `UserAdminService` idiom; gives a clearer, field-specific `409` message |
| `lastConditionUpdatedAt` stamped server-side only, never client-supplied | Keeps the field trustworthy as a real "last inspected" signal |
| Image upload is a JSON base64 body, not multipart | Nothing else in this codebase uses multipart; keeps the request shape uniform with every other write endpoint |
| Validation added to `POST`/`PUT` only, not `PATCH` | `PATCH`'s partial-update contract is incompatible with required-field checks |

---

## 8. Known follow-ups (not built)

- **Frontend not yet updated** — `equipmentApi` still points at the now-removed `/api/equipment`; this is the next piece of work.
- **`AssetCategory` admin CRUD** — only reads exist; out of scope here.

---

## 9. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-13 | Initial spec, distilled and refactored from the narrative change log [`CHANGES-admin-asset-records.md`](./CHANGES-admin-asset-records.md) into this project's standard SPEC format (outcomes/scope/requirements/design/verification/decisions), condensing the step-by-step implementation narrative into GIVEN/WHEN/THEN requirements and a design summary. No new code or behavior — documentation only. |
