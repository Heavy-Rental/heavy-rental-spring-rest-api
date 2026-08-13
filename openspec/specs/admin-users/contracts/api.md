# Contract: `/api/users`

| Field | Value |
|-------|--------|
| **Capability** | admin-users |
| **Status** | As-built |

## Routes

| Method | Path | Body | Success |
|--------|------|------|---------|
| `GET` | `/api/users` | — | `UserResponse[]` |
| `GET` | `/api/users/{id}` | — | `UserResponse` |
| `POST` | `/api/users` | `{ name, email }` | `201` + `UserCreateResponse` (+ `temporaryPassword`) |
| `PATCH` | `/api/users/{id}` | partial `{ name?, email?, role? }` | `UserResponse` |
| `DELETE` | `/api/users/{id}` | — | `204` |

`UserResponse`: `{ id, name, email, role }` where `role` is frontend string.

Auth: `ROLE_ADMIN` Bearer only.
