# Contract: Login & Logout API

| Field | Value |
|-------|--------|
| **Capability** | auth-login-logout |
| **Status** | As-built |

## Process flow

```text
1. GET  /api/auth/getBearerToken     → interim JWT  (auth-interim-token)
2. POST /api/auth/login              → access JWT JSON; interim jti denylisted
3. Protected APIs with access Bearer
4. POST /api/auth/logout             → access jti denylisted
```

## Token tiers

| | Interim | Session (access) |
|--|---------|------------------|
| Issued by | `GET /getBearerToken` | `POST /login` after AuthenticationManager success |
| `sub` | Random UUID | Authenticated **email** |
| `tokenType` | `"interim"` | `"access"` |
| `roles` | `["ROLE_INTERIM"]` | DB roles e.g. `ROLE_USER`, `ROLE_ADMIN` |
| May call | Login only | Logout + USER/ADMIN APIs |

Signing: HS256; same `app.jwt.*` config as project constitution.

## Denylist

| Event | Action |
|-------|--------|
| Successful login | Denylist interim `jti` until original `exp` |
| Successful logout | Denylist access `jti` until original `exp` |
| JwtDecoder | Reject denylisted `jti` |

## `POST /api/auth/login`

```http
POST /api/auth/login HTTP/1.1
Authorization: Bearer <interim-jwt>
Content-Type: application/json

{ "email": "admin@localhost", "password": "admin1234" }
```

**DTO:** `LoginRequest(email, password)`

**Success `200` — `LoginResponse`:**

| Field | Type | Description |
|-------|------|-------------|
| `accessToken` | string | Session JWT |
| `tokenType` | string | `"Bearer"` |
| `expiresIn` | long | Seconds |
| `username` | string | Authenticated **email** (legacy field name) |

## `POST /api/auth/logout`

```http
POST /api/auth/logout HTTP/1.1
Authorization: Bearer <access-jwt>
```

**Success `200`:**

```json
{ "message": "Logged out successfully" }
```

## Errors

```json
{ "error": "<code>", "message": "<reason>" }
```

| HTTP | Typical `error` |
|------|-----------------|
| `400` | `bad_request` |
| `401` | `unauthorized` / `invalid_credentials` |
| `403` | `forbidden` |

## Login processing (normative steps)

```text
login(LoginRequest, Jwt interimJwt):
  1. Assert interimJwt present and tokenType == interim
  2. Validate email/password non-blank → 400
  3. AuthenticationManager.authenticate(email, plainPassword)
  4. Issue access JWT: sub=email, roles=ROLE_* (excluding ROLE_INTERIM), tokenType=access
  5. tokenDenylist.deny(interimJwt.jti, interimJwt.exp)
  6. Return LoginResponse
```

**Do not** call `passwordEncoder.encode(request.password())` before `authenticate()`.

## Verification

```bash
cd heavy-rental-spring-rest-api
./mvnw test -Dtest=AuthenticationIntegrationTest
```
