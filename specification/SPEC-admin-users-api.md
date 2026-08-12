# Specification: Admin Users — Backend Wiring

| Field | Value |
|-------|--------|
| **Feature** | Admin Users tab — backend wiring (`heavy-rental-react-web-portal`'s Users page) |
| **Status** | Implemented and verified end-to-end on branch `36-link-rest-api-users-to-front-end`. Not yet committed as of this writing. |
| **Module** | `heavy-rental-spring-rest-api` |
| **Primary surface** | New `/api/users` REST surface — did not exist in any form before this branch |
| **Related code** | `dto/UserResponse.java`, `dto/UserUpdateRequest.java`, `dto/UserCreateRequest.java`, `dto/UserCreateResponse.java`, `service/UserAdminService.java`, `controller/UserController.java`, `config/SecurityConfig.java` |
| **Frontend counterpart** | `heavy-rental-react-web-portal`, branch `122-fix-error-admin-login` |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |

Unlike `/api/equipment`, there was no pre-existing controller, service, DTO, or route for user management anywhere in this codebase — `User` entity and `UserRepository` already existed (with `findAll`, `findByRole`, `findByEmail`, `existsByEmail`), but nothing exposed them over HTTP. This spec is the source of truth for that new `/api/users` surface, built directly against the real frontend's live contract (confirmed by reading `heavy-rental-react-web-portal`'s `types.ts`, `UsersTab.tsx`, and `AdminDataContext.tsx`, not guessed from the admin mockup alone).

---

## 1. Outcomes

When this feature is correct:

1. An admin can list, view, update, and remove users, and create new customer accounts, all through `/api/users` (REQ-1–REQ-5).
2. The backend's `UserRole` enum (`USER`/`ADMIN`/`DRIVER`) round-trips correctly with the frontend's role strings (`"customer"`/`"admin"`/`"employee"`), in both directions (REQ-6).
3. Removing a user preserves the row (and all `Booking`/`Payment`/`RentalPlan` history that references it) but makes it disappear from every future `GET /api/users` response, so the frontend's "permanently delete" copy holds true from the admin's point of view (REQ-7).
4. Only `ROLE_ADMIN` can reach any `/api/users/**` route (REQ-8).

---

## 2. Scope

### 2.1 In scope

- Five routes: `GET /api/users`, `GET /api/users/{id}`, `POST /api/users`, `PATCH /api/users/{id}`, `DELETE /api/users/{id}`.
- Role-string mapping between the backend enum and the frontend's literal values.
- Soft-delete via the existing `User.enabled` column, with `GET /api/users` filtering to `enabled=true` only.
- Server-side password generation on create (the `password` column is `NOT NULL`; the frontend's Add Customer form never collects one).
- Gating all five routes to `ROLE_ADMIN` in `SecurityConfig.java`.

### 2.2 Out of scope

- **A UI path for the generated password.** The frontend's create-success handler only reads `id/name/email/role` off the response and never looks for a password field anywhere (confirmed by reading the actual handler in `UsersTab.tsx`) — `temporaryPassword` is returned as an additive field the frontend can pick up later, but there's currently nowhere for an admin to actually see it. Not something this spec can fix, since it requires a frontend change.
- **Rentals-count / Total-spent columns.** The frontend's `UsersTab.tsx` fabricates `rentals: 0` and `spent: 0` locally on every user row — not derived from any backend field, not part of the `User` type, not something `GET /api/users` needs to compute. Confirmed by reading the create handler directly; matches a prior decision already on record to defer this until `Booking`/`Payment` join work is planned.
- **A `status` ("Active"/"Inactive") field on the response.** Confirmed by reading the frontend directly: `status` isn't part of the `User` type, isn't derived from any backend field, is hardcoded to `"Inactive"` on every newly created user, and has no UI path to ever change (no Reactivate button, no status editor in the Edit modal). The backend doesn't need to expose `enabled` at all — see REQ-7 for how soft-delete stays consistent with the frontend without it.
- **Role selection on create.** The Add Customer modal has no role selector — every created user is hardcoded `role: "customer"` frontend-side, so the backend does the same (`User.UserRole.USER`) rather than accepting a `role` field on `POST`.

---

## 3. Requirements

### REQ-1: List users

**GIVEN** an admin token
**WHEN** `GET /api/users` is called
**THEN** every `enabled=true` user is returned as `{id, name, email, role}` — never the password.

### REQ-2: Get one user

**GIVEN** an admin token and an existing, enabled user's id
**WHEN** `GET /api/users/{id}` is called
**THEN** that user is returned in the same shape as REQ-1.

**GIVEN** an id that doesn't exist, or belongs to a soft-deleted (`enabled=false`) user
**WHEN** `GET /api/users/{id}` is called
**THEN** `404` — a removed user is indistinguishable from one that never existed, matching the frontend's "permanently deleted" framing.

### REQ-3: Update a user

**GIVEN** an admin token and a partial body (`name`/`email`/`role`, any subset)
**WHEN** `PATCH /api/users/{id}` is called
**THEN** only the provided fields change; an invalid `role` string returns `400`.

### REQ-4: Remove a user (soft-delete)

**GIVEN** an admin token
**WHEN** `DELETE /api/users/{id}` is called
**THEN** the user's `enabled` flag is set to `false` — the row, and everything that references it (`Booking`, `Payment`, `RentalPlan`, etc.), is left intact. Returns `204`.

### REQ-5: Create a user

**GIVEN** an admin token and `{name, email}` (no password, no role — the Add Customer modal collects neither)
**WHEN** `POST /api/users` is called
**THEN** a new user is created with a random generated password (hashed via the existing `PasswordEncoder` bean), `role=USER`, `enabled=true`, and the response includes the plaintext password once, as `temporaryPassword`. Returns `201`.

**GIVEN** an email that's already in use
**WHEN** `POST /api/users` is called
**THEN** `409 CONFLICT`.

### REQ-6: Role mapping

**GIVEN** any `User` with `role = USER`, `DRIVER`, or `ADMIN`
**WHEN** it's serialized into any response
**THEN** it appears as `"customer"`, `"employee"`, or `"admin"` respectively — confirmed against the frontend's actual `<select>` options in the Edit User modal (`UsersTab.tsx`, lines ~100–113), not assumed.

**GIVEN** a `PATCH` or would-be `POST` body containing `role: "customer" | "employee" | "admin"`
**WHEN** it's parsed
**THEN** it maps back to `USER`/`DRIVER`/`ADMIN` respectively; any other string is `400`.

### REQ-7: Removed users disappear from the list, permanently, from the admin's perspective

**GIVEN** a user that's been soft-deleted
**WHEN** `GET /api/users` is called again later, even after the frontend's own local-state filtering would have reset (e.g. a page refresh)
**THEN** that user does not reappear — this is why `listAll()` filters on `enabled=true` rather than just relying on the frontend's client-side `.filter()` after a successful `DELETE`.

### REQ-8: Admin-only access

**GIVEN** a `ROLE_USER`-only token
**WHEN** any `/api/users/**` route is called, read or write
**THEN** `403`. This route family is the one place in `SecurityConfig.java` gated to `ROLE_ADMIN` alone, not `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` like most routes — matching the existing `/api/monthly-utilization` precedent and the original plan's locked decision that user/role management is admin-exclusive.

---

## 4. Design

- **`dto/UserResponse.java`** — record `{Long id, String name, String email, String role}`. No password field, ever.
- **`dto/UserUpdateRequest.java`** — record `{String name, String email, String role}`, all nullable/optional (true `PATCH` semantics — only non-null fields are applied), matching `AssetService.patch()`'s existing convention.
- **`dto/UserCreateRequest.java`** — record `{String name, String email}` only.
- **`dto/UserCreateResponse.java`** — same 4 fields as `UserResponse` plus `String temporaryPassword`.
- **`service/UserAdminService.java`**:
  - `listAll()` / `getById(id)` — filter/check `enabled=true`; `getById` throws `404` for a disabled or missing id via a shared `findEnabledOrThrow` helper.
  - `update(id, request)` — partial apply, same null-check pattern as `AssetService`.
  - `remove(id)` — `user.setEnabled(false)`, saved — never an actual `deleteById`.
  - `create(request)` — `existsByEmail` check (`409` if taken), generates a 16-character random password (`SecureRandom`, mixed-case letters/digits/symbols, ambiguous characters like `0/O/1/l/I` excluded), hashes it via the injected `PasswordEncoder` bean, saves with `role=USER`/`enabled=true`, returns the plaintext once.
  - Two private mapping methods, `roleToFrontend(UserRole) -> String` and `parseRole(String) -> UserRole`, implementing REQ-6's table in both directions; `parseRole` throws `400 BAD_REQUEST` on an unrecognized string.
- **`controller/UserController.java`** — thin `@RestController` at `/api/users`, one line per method delegating to `UserAdminService`, matching `EquipmentController`'s existing convention exactly (constructor-injected service, `@ResponseStatus(CREATED)` on `POST`, `@ResponseStatus(NO_CONTENT)` on `DELETE`).
- **`config/SecurityConfig.java`** — one new line, `.requestMatchers("/api/users/**").hasAuthority("ROLE_ADMIN")`, added next to the existing `/api/monthly-utilization` line, ahead of the final `anyRequest()` catch-all.

### 4.1 Role-mapping table

| Backend `UserRole` | Frontend string |
|---|---|
| `USER` | `"customer"` |
| `DRIVER` | `"employee"` |
| `ADMIN` | `"admin"` |

Not a guess — confirmed directly against the live frontend's `Role` type and the Edit User modal's `<select>` options before writing any code.

---

## 5. Open questions (need your decision before implementation)

All resolved during spec drafting, by reading the actual frontend code rather than assuming — kept here for traceability per this project's convention:

1. **Role-string mapping**: what do `USER`/`DRIVER`/`ADMIN` correspond to in the frontend's `"customer"`/`"employee"`/`"admin"`? **Resolved** by reading the Edit User modal's role `<select>` directly — see §4.1.
2. **Soft-delete visibility**: since the frontend's `User` type has no `enabled`/`status` field at all, how does a removed user avoid reappearing after a refresh? **Resolved:** `GET /api/users` filters to `enabled=true` server-side, so the row is genuinely gone from every future list call even though it's never actually deleted — confirmed necessary by reading `handleUserDelete`, which shows the frontend's own removal is only a local, one-time `.filter()` with no re-fetch-aware persistence of its own.
3. **Password delivery on create**: the frontend's `POST` response type is bare `User`, no password field — does the backend still need to generate one? **Resolved:** yes (the `password` column is `NOT NULL`), returned as an additive `temporaryPassword` field the current frontend simply ignores; confirmed by reading the create-success handler directly — there's no password-related code anywhere in it.

---

## 6. Verification

### 6.1 Checklist

- [x] Admin `GET /api/users` → `200`, 7 seeded users, roles mapped correctly (`admin@localhost`→`"admin"`, Alex Tan (`USER`)→`"customer"`, Ah Tan (`DRIVER`)→`"employee"`)
- [x] Admin `POST /api/users` with `{name, email}` → `201`, `role:"customer"`, `temporaryPassword` present
- [x] Admin `PATCH /api/users/{id}` with `{name, role}` → `200`, both fields updated
- [x] Admin `DELETE /api/users/{id}` → `204`
- [x] `GET /api/users` immediately after delete → back to the original count, deleted id absent
- [x] `GET /api/users/{id}` on the deleted id → `404`
- [x] `ROLE_USER` token, `GET /api/users` → `403`
- [x] Invalid role string on `PATCH` → `400`, `"Unknown role: <value>"`
- [x] Duplicate email on `POST` → `409`, `"Email already in use: <email>"`

### 6.2 Manual smoke test (run live, 2026-08-12, against a real running instance + Postgres)

1. Logged in as `ravi.kumar@example.sg` (admin) — `GET /api/users` returned all 7 seeded users with correctly mapped roles.
2. `POST /api/users` with a new name/email — `201`, got back a real generated `temporaryPassword`.
3. `PATCH` that new user's `name` and `role` (→ `"employee"`) — `200`, both changed.
4. `DELETE` that new user — `204`; a follow-up `GET /api/users` showed 7 users again (back to the original seeded count); `GET /api/users/{that id}` → `404`.
5. Logged in as `alex.tan@example.sg` (customer) — `GET /api/users` → `403`.
6. `PATCH /api/users/2` with `{"role":"superadmin"}` — `400`.
7. `POST /api/users` with `alex.tan@example.sg` (already in use) — `409`.

---

## 7. Key decisions

| Decision | Rationale |
|----------|-----------|
| Built directly against the live frontend contract, not the admin mockup alone | The mockup-derived plan and the actual shipped frontend code disagreed on several details (role strings, no status field, no password UI) — reading `types.ts`/`UsersTab.tsx`/`AdminDataContext.tsx` directly caught all of them before any code was written. |
| `enabled=true` filtering on `GET /api/users`, not just relying on the frontend's own local-state removal | The frontend's delete handler only filters its in-memory list once; without server-side filtering, a soft-deleted user would resurface after any full re-fetch, contradicting the "permanently delete" confirmation copy the admin sees. |
| `temporaryPassword` returned as an additive field, not withheld | The `password` column is `NOT NULL` regardless of what the frontend's `POST` response type declares — a real password has to exist either way; returning it (even though nothing displays it yet) means the account is actually usable via the existing login endpoint. |
| Role hardcoded to `USER` on create, no `role` field accepted on `POST` | Matches the Add Customer modal exactly — it has no role selector and always sends `role: "customer"` itself. |
| `/api/users/**` gated to `ROLE_ADMIN` alone | Matches the original locked decision (user/role management is admin-exclusive) and the one existing precedent in this codebase, `/api/monthly-utilization`. |

---

## 8. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-12 | Initial draft, created after implementation and full live verification on branch `36-link-rest-api-users-to-front-end`. REQ-1 through REQ-8 captured; all three open questions resolved by reading the actual frontend source rather than the original mockup-based plan. Not yet committed. |
| 0.1.1 | 2026-08-12 | Added **Frontend counterpart** to the header table — this backend branch pairs with `heavy-rental-react-web-portal`'s `122-fix-error-admin-login` branch. |
