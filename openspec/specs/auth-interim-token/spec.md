# Auth Interim Token — Source of Truth

## Purpose

Mint a public **interim** Bearer JWT via `GET /api/auth/getBearerToken` so clients can proceed to login.

**Status:** **As-built**  
**Keep split from** [`auth-login-logout`](../auth-login-logout/spec.md) (independent contract ownership).  
**HTTP detail:** [`contracts/api.md`](./contracts/api.md)

## Requirements

### Requirement: FR-AUTH-I-001 Public interim mint

The system MUST allow any unauthenticated client to call `GET /api/auth/getBearerToken` without an `Authorization` header and receive `200 OK` with `Content-Type: text/plain` and a raw JWT compact string (no JSON envelope, no `Bearer ` prefix).

#### Scenario: Unauthenticated mint succeeds
- GIVEN any client with no credentials
- WHEN they `GET /api/auth/getBearerToken`
- THEN the response is `200` with `text/plain` body equal to a JWT compact serialization

### Requirement: FR-AUTH-I-002 Interim claim shape

Each minted interim token MUST be HS256-signed with project JWT config and MUST include: random UUID `sub`, ISO-8601 `generatedAt` at issuance, `tokenType` = `"interim"`, `roles` containing `ROLE_INTERIM` (and not application user roles solely from this mint), plus `jti`, `iss`, `iat`, and `exp`.

#### Scenario: Decoded claims are interim-tier
- GIVEN a successful response body `T`
- WHEN `T` is decoded with the application `JwtDecoder`
- THEN `sub` is a UUID string
- AND `tokenType` is `"interim"`
- AND `roles` contains `ROLE_INTERIM`
- AND `generatedAt`, `jti`, `iss`, `iat`, `exp` are present

### Requirement: FR-AUTH-I-003 Distinct subjects per mint

Successive successful mints MUST produce distinct `sub` values.

#### Scenario: Two calls differ in sub
- GIVEN two successive successful getBearerToken calls
- WHEN both tokens are decoded
- THEN their `sub` values are different

### Requirement: FR-AUTH-I-004 Bearer header form

Clients MUST send the raw token as `Authorization: Bearer <token>` (single space after `Bearer`) on subsequent calls. Which routes accept interim tokens is defined by auth-login-logout and SecurityConfig — not this capability.

#### Scenario: Header form for login hop
- GIVEN raw token string `T` from getBearerToken
- WHEN the client attaches it to a request
- THEN the header is `Authorization: Bearer T`

## Out of scope

- Login / logout / access tokens → [`auth-login-logout`](../auth-login-logout/spec.md)
- Register API, refresh tokens, OAuth2/OIDC provider, rate limiting
