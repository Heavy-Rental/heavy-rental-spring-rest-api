# Auth Login & Logout — Source of Truth

## Purpose

Upgrade an **interim** Bearer JWT to a **session (access)** token via login, and revoke access tokens via logout (jti denylist).

**Status:** **As-built**  
**Depends on:** [`auth-interim-token`](../auth-interim-token/spec.md)  
**HTTP detail:** [`contracts/api.md`](./contracts/api.md)

## Requirements

### Requirement: FR-AUTH-L-001 Login upgrades interim to access

The system MUST accept `POST /api/auth/login` with a valid interim Bearer and JSON `{ "email", "password" }`, authenticate via Spring Security `AuthenticationManager`, and return `200` with a session access JWT in `LoginResponse` form. After success, the interim token’s `jti` MUST be denylisted until its original `exp`.

#### Scenario: Successful login
- GIVEN a valid interim Bearer and an enabled user with correct password
- WHEN `POST /api/auth/login` with credentials and interim Authorization
- THEN response is `200` with `accessToken`, `tokenType` = `"Bearer"`, `expiresIn`, and `username` equal to the authenticated **email**
- AND the access JWT has `sub` = email, `tokenType` = `"access"`, and user DB roles
- AND the interim `jti` is denylisted

#### Scenario: Missing or bad interim
- GIVEN no Bearer or invalid/expired/revoked interim
- WHEN login is called
- THEN response is `401`

#### Scenario: Bad credentials
- GIVEN valid interim but wrong password or unknown user
- WHEN login is called
- THEN response is `401` with `error` of `invalid_credentials` (or mapped auth failure)

#### Scenario: Access token cannot login
- GIVEN an access token used as Bearer on login
- WHEN login is called
- THEN response is `403`

#### Scenario: Interim single-use after login
- GIVEN successful login with interim token T
- WHEN the same interim T is reused for login
- THEN response is `401`

#### Scenario: Blank credentials
- GIVEN valid interim and blank email or password
- WHEN login is called
- THEN response is `400` with `error` of `bad_request`

### Requirement: FR-AUTH-L-002 Token tier authorization

Interim tokens MUST only authorize login. Access tokens with `ROLE_USER` / `ROLE_ADMIN` MUST authorize logout and business APIs. Interim alone MUST receive `403` on session-only routes (including logout).

#### Scenario: Interim blocked from protected routes
- GIVEN only an interim JWT
- WHEN the client calls a route requiring USER/ADMIN (including logout)
- THEN response is `403`

#### Scenario: Access allowed on protected routes
- GIVEN a valid access JWT
- WHEN the client calls such a route
- THEN authentication succeeds (subject to further business rules)

### Requirement: FR-AUTH-L-003 Logout revokes access token

`POST /api/auth/logout` with a valid access Bearer MUST return `200` with a success message and denylist the access `jti` until original `exp`. Reuse of that token MUST yield `401`. Interim on logout MUST yield `403`.

#### Scenario: Logout denylists access
- GIVEN a valid access Bearer
- WHEN `POST /api/auth/logout`
- THEN response is `200` with `{ "message": "Logged out successfully" }`
- AND subsequent use of that access token returns `401`

#### Scenario: Interim cannot logout
- GIVEN only an interim token
- WHEN logout is called
- THEN response is `403`

### Requirement: FR-AUTH-L-004 Password verification path

Login MUST pass the **plain** password from the request into `AuthenticationManager` and MUST NOT BCrypt-encode the password before `authenticate()`. Verification MUST use `PasswordEncoder.matches` against the stored BCrypt hash from `users`.

#### Scenario: Encode-before-authenticate is forbidden
- GIVEN a correct password for a seeded user
- WHEN login is implemented with `encode(password)` before `authenticate`
- THEN login would fail incorrectly
- AND the correct path uses `matches(plain, hashFromDb)` only

### Requirement: FR-AUTH-L-005 Security matchers

| Matcher | Rule |
|---------|------|
| `GET /api/auth/getBearerToken` | `permitAll` (auth-interim-token) |
| `POST /api/auth/login` | `hasAuthority("ROLE_INTERIM")` |
| `POST /api/auth/logout` | `hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")` |
| Other API requests | `hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")` |

#### Scenario: Matcher matrix holds
- GIVEN SecurityConfig as-built
- WHEN each auth path is exercised
- THEN the matcher rules above apply

## Out of scope

- Interim mint contract → auth-interim-token  
- User registration REST API  
- Refresh tokens / OAuth2-OIDC provider  
- Multi-instance shared denylist store (process-local `TokenDenylist` only)
