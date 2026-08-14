# Contract: `GET /api/auth/getBearerToken`

| Field | Value |
|-------|--------|
| **Capability** | auth-interim-token |
| **Status** | As-built |
| **Related code** | `Authentication`, `AuthService`, `JwtService`, `SecurityConfig` |

## Request

```http
GET /api/auth/getBearerToken HTTP/1.1
```

No body. No `Authorization` required. Security: `permitAll`.

## Success — `200 OK`

| Aspect | Value |
|--------|--------|
| Content-Type | `text/plain` |
| Body | Raw JWT compact serialization only |

## Interim JWT claims

| Claim | Value |
|-------|--------|
| `jti` | New random UUID |
| `sub` | Random UUID string |
| `tokenType` | `"interim"` |
| `generatedAt` | ISO-8601 mint time (UTC) |
| `iat` | Same mint instant |
| `exp` | `iat` + `app.jwt.expirationMinutes` |
| `iss` | `app.jwt.issuer` |
| `roles` | `["ROLE_INTERIM"]` |

Algorithm: **HS256** with `app.jwt.secret` (≥ 32 characters).

## Flow

```text
Client → Authentication.getBearerToken → AuthService
  uuid = UUID.randomUUID(); generatedAt = Instant.now()
  JwtService.generateToken(uuid, [ROLE_INTERIM], generatedAt, "interim")
→ 200 text/plain jwt
```

## Smoke

```bash
TOKEN=$(curl -s http://localhost:8080/api/auth/getBearerToken)
echo "$TOKEN"
# Continue with login — auth-login-logout
```
