# Admin Users API — Source of Truth

## Purpose

Admin-only `/api/users` CRUD surface for the portal Users tab: list/get/create/patch/soft-delete with frontend role-string mapping.

**Status:** **As-built** (merged via PR #37)  
**HTTP shapes:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** `ROLE_ADMIN` only (`SecurityConfig` matcher `/api/users/**`)

## Requirements

### Requirement: FR-USR-001 List enabled users

`GET /api/users` MUST return all `enabled=true` users as `{id, name, email, role}` with frontend role strings, never passwords.

### Requirement: FR-USR-002 Get enabled user

`GET /api/users/{id}` MUST return the same shape for an enabled user; missing or soft-deleted → `404`.

### Requirement: FR-USR-003 Partial update

`PATCH /api/users/{id}` MUST apply only non-null fields (`name`/`email`/`role`). Invalid role string → `400`.

### Requirement: FR-USR-004 Soft-delete

`DELETE /api/users/{id}` MUST set `enabled=false` (not hard delete), return `204`, preserve historical FKs, and hide the user from subsequent lists.

### Requirement: FR-USR-005 Create customer with generated password

`POST /api/users` with `{name, email}` MUST create `role=USER`, `enabled=true`, random hashed password, return `201` including one-time `temporaryPassword`. Duplicate email → `409`.

### Requirement: FR-USR-006 Role mapping

| Backend `UserRole` | Frontend string |
|--------------------|-----------------|
| `USER` | `"customer"` |
| `DRIVER` | `"employee"` |
| `ADMIN` | `"admin"` |

Serialization and parse MUST use this table both ways.

### Requirement: FR-USR-007 Admin-only access

Any `/api/users/**` call with only `ROLE_USER` MUST return `403`.

## Out of scope

- UI for displaying `temporaryPassword`  
- Rentals-count / total-spent aggregates  
- Role selection on create (always customer/USER)
