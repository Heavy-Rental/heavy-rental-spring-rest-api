# Specification: Auth Login & Logout (Interim → Access Session)

| Field | Value |
|-------|--------|
| **Feature** | Login and logout with multi-step Bearer JWT |
| **Status** | As-built (implemented on branch) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Endpoints** | `POST /api/auth/login`, `POST /api/auth/logout` |
| **Depends on** | [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md) (interim mint) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |
| **Related code** | `Authentication`, `AuthService`, `JwtService`, `SecurityConfig`, `TokenDenylist`, DTOs |

This document is the **single source of truth** for upgrading an **interim** Bearer token to a **session (access)** token via login, and for revoking session tokens via logout.

Interim minting (`GET /api/auth/getBearerToken`) is defined only in **SPEC-request-bearer-token.md**.

---

## 1. Outcomes

When this feature is correct:

1. A client holding a valid **interim** JWT can call `POST /api/auth/login` with username/password and receive a **session access JWT** after Spring Security authentication.
2. Interim tokens **cannot** authorize general protected APIs or logout; only access tokens with user roles can.
3. After successful login, the interim token’s `jti` is denylisted (single-use interim).
4. `POST /api/auth/logout` denylists the access token’s `jti` so it can no longer be used.
5. Integration tests cover login upgrade, tier separation, interim single-use, and logout revocation.

---

## 2. Process flow

```text
Client                              API
  │
  │  1. GET /api/auth/getBearerToken
  │     (see SPEC-request-bearer-token)
  │◄──── interim JWT ───────────────────────────────│
  │
  │  2. POST /api/auth/login
  │     Authorization: Bearer <interim>
  │     { "username", "password" }
  │◄──── LoginResponse (access JWT JSON) ───────────│
  │      (interim jti denylisted)
  │
  │  3. Protected APIs
  │     Authorization: Bearer <access>
  │◄──── 200 ───────────────────────────────────────│
  │
  │  4. POST /api/auth/logout
  │     Authorization: Bearer <access>
  │◄──── 200 + access jti denylisted ───────────────│
```

---

## 3. Scope

### 3.1 In scope

- `POST /api/auth/login` (interim Bearer + credentials → access JWT)
- `POST /api/auth/logout` (access Bearer → denylist)
- Token tier rules (`interim` vs `access`) and SecurityConfig matchers
- Denylist: interim after login; access after logout
- DTOs: `LoginRequest`, `LoginResponse`, `MessageResponse`
- Tests and verification for login/logout behavior

### 3.2 Out of scope

- Contract for minting interim tokens → SPEC-request-bearer-token  
- User registration REST API  
- Refresh tokens / OAuth2-OIDC provider  
- Rate limiting / multi-instance denylist store  

---

## 4. Token tiers (session model)

| | Interim (prerequisite) | Session (access) |
|--|------------------------|------------------|
| **Issued by** | `GET /getBearerToken` | `POST /login` after `AuthenticationManager` success |
| **Prerequisite** | None | Valid interim Bearer + credentials |
| **`sub`** | Random UUID | Authenticated **username** |
| **`tokenType`** | `"interim"` | `"access"` |
| **`roles`** | `["ROLE_INTERIM"]` | DB roles, e.g. `["ROLE_USER"]`, `["ROLE_ADMIN"]` |
| **May call** | Login only | Logout + business APIs requiring USER/ADMIN |

Signing: HS256, same issuer/secret/TTL config as environment SPEC.

### 4.1 Security authorization rules

| Matcher | Rule |
|---------|------|
| `GET /api/auth/getBearerToken` | `permitAll` (see request-bearer SPEC) |
| `POST /api/auth/login` | `hasAuthority("ROLE_INTERIM")` |
| `POST /api/auth/logout` | `hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")` |
| Any other API request | `hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")` |

### 4.2 Denylist rules

| Event | Action |
|-------|--------|
| Successful login | Denylist interim `jti` until original `exp` |
| Successful logout | Denylist access `jti` until original `exp` |
| JwtDecoder | Reject denylisted `jti` |

---

## 5. Requirements

### Requirement 1: Login upgrades interim to access

**User story:** As a client with an interim token, I want to authenticate with username/password and receive a higher-privilege session JWT.

#### Acceptance criteria

1. **GIVEN** a valid interim Bearer and an enabled user with correct password  
   **WHEN** `POST /api/auth/login` with JSON `{ "username", "password" }` and `Authorization: Bearer <interim>`  
   **THEN** response is `200 OK` with:

   ```json
   {
     "accessToken": "<jwt>",
     "tokenType": "Bearer",
     "expiresIn": <seconds>,
     "username": "<username>"
   }
   ```

2. **GIVEN** the returned `accessToken` decoded  
   **THEN** `sub` is the username, `tokenType` is `"access"`, and `roles` include the user’s DB role(s); credentials were verified via Spring Security `AuthenticationManager`.

3. **GIVEN** no Bearer or invalid/expired/revoked interim  
   **WHEN** login is called  
   **THEN** `401`.

4. **GIVEN** valid interim but wrong password / unknown user  
   **WHEN** login is called  
   **THEN** `401` with `error` of `"invalid_credentials"` (or mapped auth failure).

5. **GIVEN** an access token used as Bearer on login  
   **WHEN** login is called  
   **THEN** `403`.

6. **GIVEN** successful login  
   **WHEN** the same interim token is reused for login  
   **THEN** `401` (interim denylisted).

7. **GIVEN** blank username or password with valid interim  
   **WHEN** login is called  
   **THEN** `400` with `error` of `"bad_request"`.

### Requirement 2: Interim cannot access session-only routes

1. **GIVEN** only an interim JWT  
   **WHEN** the client calls a route requiring `ROLE_USER` / `ROLE_ADMIN` (including logout)  
   **THEN** response is `403`.

2. **GIVEN** a valid access JWT  
   **WHEN** the client calls such a route  
   **THEN** authentication succeeds (subject to further business rules).

### Requirement 3: Logout revokes access token

1. **GIVEN** a valid access Bearer  
   **WHEN** `POST /api/auth/logout`  
   **THEN** `200` with `{ "message": "Logged out successfully" }` and `jti` denylisted.

2. **GIVEN** that access token after logout  
   **WHEN** used again  
   **THEN** `401`.

3. **GIVEN** only an interim token  
   **WHEN** logout is called  
   **THEN** `403`.

---

## 6. API contracts

### 6.1 `POST /api/auth/login`

```http
POST /api/auth/login HTTP/1.1
Authorization: Bearer <interim-jwt>
Content-Type: application/json

{
  "username": "admin",
  "password": "admin1234"
}
```

**DTO:** `LoginRequest(username, password)`

**Success `200` — `LoginResponse`:**

| Field | Type | Description |
|-------|------|-------------|
| `accessToken` | string | Session JWT |
| `tokenType` | string | `"Bearer"` |
| `expiresIn` | long | Seconds |
| `username` | string | Authenticated subject |

### 6.2 `POST /api/auth/logout`

```http
POST /api/auth/logout HTTP/1.1
Authorization: Bearer <access-jwt>
```

**Success `200` — `MessageResponse`:**

```json
{ "message": "Logged out successfully" }
```

### 6.3 Shared errors

```json
{ "error": "<code>", "message": "<reason>" }
```

| HTTP | Typical `error` |
|------|-----------------|
| `400` | `bad_request` |
| `401` | `unauthorized` / `invalid_credentials` |
| `403` | `forbidden` |

---

## 7. Design

### 7.1 Components

| Concern | Location |
|---------|----------|
| HTTP | `controller/Authentication.java` (`login`, `logout`) |
| Orchestration | `service/AuthService.java` |
| JWT claims | `security/JwtService.java` (`tokenType` access) |
| Revocation | `security/TokenDenylist.java` |
| Rules | `config/SecurityConfig.java` |
| DTOs | `dto/LoginRequest`, `LoginResponse`, `MessageResponse` |
| Users | `CustomUserDetailsService`, `User`, `UserRepository` |

### 7.2 Login processing

```text
login(LoginRequest, Jwt interimJwt):
  1. Assert interimJwt present and tokenType == interim (defense in depth)
  2. Validate username/password non-blank → 400
  3. AuthenticationManager.authenticate(...)
  4. Issue access JWT: sub=username, roles=ROLE_* (excluding ROLE_INTERIM), tokenType=access
  5. tokenDenylist.deny(interimJwt.jti, interimJwt.exp)
  6. Return LoginResponse
```

### 7.3 Logout processing

```text
logout(Jwt accessJwt):
  1. Assert accessJwt present and tokenType == access
  2. tokenDenylist.deny(accessJwt.jti, accessJwt.exp)
  3. Return MessageResponse("Logged out successfully")
```

### 7.4 Password handling at login (Spring Security)

Login authenticates with:

```java
authenticationManager.authenticate(
    new UsernamePasswordAuthenticationToken(
        request.username().trim(),
        request.password()));  // plain password from the client — do NOT encode here
```

#### What `UsernamePasswordAuthenticationToken` does **not** do

- It does **not** encrypt or BCrypt-hash the password.
- It only holds **principal** (username) and **credentials** (raw password string from the JSON body).

#### What actually verifies the password

| Step | Component | Behavior |
|------|-----------|----------|
| 1 | `AuthenticationManager` / `DaoAuthenticationProvider` | Orchestrates authentication |
| 2 | `CustomUserDetailsService` | Loads user from `users` by username; password field is the **stored BCrypt hash** |
| 3 | `PasswordEncoder` (`BCryptPasswordEncoder` bean) | Calls **`matches(rawPasswordFromRequest, hashFromDb)`** |

| Call | When to use |
|------|-------------|
| `passwordEncoder.encode(plain)` | **Creating/updating** a user row (store hash in DB) |
| `passwordEncoder.matches(plain, hash)` | **Login** (used internally by Spring after `authenticate`) |

**Do not** call `encode(request.password())` before `authenticate()`. BCrypt produces a new salt on every `encode()`, so the result will not equal the DB hash and login will fail with `invalid_credentials`.

#### Where DB password hashes come from

There is **no** public register endpoint in this design. Hashes are written when a user row is created, for example:

1. **`DefaultUserInitializer`** (startup): if `users` is **empty**, seeds admin with  
   `passwordEncoder.encode(app.security.default-password)`  
   Defaults in config: username `admin`, password `admin1234` (overridable via env).
2. **Tests** (or future admin/register code): same pattern — `encode` then `userRepository.save`.
3. **Manual SQL / ops** (if used): must store a BCrypt hash, not plain text.

**Important:** `DefaultUserInitializer` runs **only when the table is empty**. Changing `app.security.default-password` later does **not** update an existing `admin` row. If login fails with `invalid_credentials` for `admin` / `admin1234`, the DB may still hold an older hash (e.g. seeded earlier as `admin123`). Fix by updating the hash, deleting users and restarting to re-seed, or logging in with the password that was used when the row was created.

#### Login password path (summary)

```text
Client JSON password (plain)
  → UsernamePasswordAuthenticationToken(username, plainPassword)
  → AuthenticationManager.authenticate
  → load UserDetails (password = BCrypt hash from users table)
  → BCryptPasswordEncoder.matches(plainPassword, hashFromDb)
  → on success: issue access JWT (password never placed in JWT)
```

---

## 8. Verification

### 8.1 Checklist

- [ ] Login without Bearer → 401  
- [ ] Login with interim + bad password → 401  
- [ ] Login with interim + good credentials → 200 + access JWT  
- [ ] Access JWT has `tokenType=access`, user `sub` and roles  
- [ ] Interim blocked from logout / business paths → 403  
- [ ] Interim after login → 401  
- [ ] Logout → 200; reuse access → 401  

### 8.2 Automated tests

From the application module:

```bash
cd heavy-rental-spring-rest-api
./mvnw test -Dtest=AuthenticationIntegrationTest
```

`AuthenticationIntegrationTest` covers interim claims, login success/failures, tier separation, interim single-use, and logout revocation. Requires reachable Postgres (host `db` by default).

### 8.3 Prerequisites for manual testing

1. Application running (`./mvnw spring-boot:run` from `heavy-rental-spring-rest-api/`).
2. PostgreSQL reachable (e.g. `ping db` / `db:5432`).
3. A user in the `users` table whose **plain password you know**.

If the table was **empty** at first startup, `DefaultUserInitializer` seeds:

| Property | Default (dev) |
|----------|----------------|
| Username | `admin` (`app.security.default-username` / `APP_DEFAULT_USERNAME`) |
| Password | `admin1234` (`app.security.default-password` / `APP_DEFAULT_PASSWORD`) |

**Seed caveat:** initializer does **not** re-run if any users already exist. An older environment may have seeded a different default password (e.g. `admin123`). If `admin` / `admin1234` returns `invalid_credentials`, try the password used when the row was first created, or reset the admin hash / empty the table and restart (see §7.4).

### 8.4 Manual test with curl (recommended)

Login is a **two-step** flow: mint interim token, then call login with that Bearer.

```bash
# Step A — interim token (no credentials)
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
echo "Interim: $INTERIM"

# Step B — login (must send interim Bearer)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}'
```

**Success (`200`)** example:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "admin"
}
```

Use `accessToken` on subsequent calls (protected APIs and logout):

```bash
# Logout
curl -s -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <accessToken>"
```

### 8.5 Manual test with Postman

Login is **two requests**. Do not put username/password only on a single unauthenticated call.

| Step | Method | URL | Authorization | Body |
|------|--------|-----|---------------|------|
| 1 | `GET` | `http://localhost:8080/api/auth/getBearerToken` | **No Auth** | none |
| 2 | `POST` | `http://localhost:8080/api/auth/login` | Type: **Bearer Token** → paste **entire response body** from step 1 (raw JWT only) | **raw → JSON**: `{"username":"admin","password":"<plain-password-for-db-user>"}` |
| 3 | `POST` | `http://localhost:8080/api/auth/logout` | Type: **Bearer Token** → paste `accessToken` from step 2 JSON | none |

**Postman setup details**

- Step 1: Auth tab = **No Auth**. Body = none. Response is plain text JWT (not JSON).
- Step 2: Auth tab = **Bearer Token** (not Basic Auth). Token value = full interim JWT from step 1 (no surrounding quotes, no `Bearer ` prefix in the token field — Postman adds the scheme).
- Step 2: Body = **raw**, type **JSON**. Password must be the **plain** password that matches the BCrypt hash in `users` (see §7.4). Config default after a fresh seed is `admin1234`; existing DBs may differ.
- Do **not** pre-hash the password in Postman or call `encode` client-side.

**Optional Postman automation**

On step 1 (getBearerToken) — Tests tab:

```javascript
pm.collectionVariables.set("interimToken", pm.response.text());
```

On step 2, set Authorization Bearer Token to `{{interimToken}}`.

On step 2 — Tests tab (to capture access token):

```javascript
const json = pm.response.json();
pm.collectionVariables.set("accessToken", json.accessToken);
```

On step 3, Bearer Token: `{{accessToken}}`.

### 8.6 Common failures when testing login

| Result | Likely cause |
|--------|----------------|
| `401` on login, no/empty Bearer | Forgot interim `Authorization: Bearer …` header |
| `401` with `error: "invalid_credentials"` | Wrong plain password, user missing, or **stale seed password** (admin hash created under an older default; see §7.4) |
| `403` on login | Used an **access** token as Bearer instead of an **interim** token |
| `401` on second login with the same interim | Interim is **single-use** after a successful login — call getBearerToken again |
| `400` with `error: "bad_request"` | Missing/blank `username` or `password` in JSON body |
| Still failing after “hashing password in client” | Password must stay **plain** in the JSON body; BCrypt `encode` is only for DB storage, not for the login request |
| Connection refused | App not running on port `8080` |
| DB/startup errors | Postgres not reachable; check `POSTGRES_*` / host `db` |

### 8.7 Quick smoke (short form)

```bash
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)

curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin1234"}'

curl -s -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <accessToken>"
```

---

## 9. Key decisions

| Decision | Rationale |
|----------|-----------|
| Separate SPEC from getBearerToken | Keeps interim mint contract stable; this file owns session lifecycle |
| Login requires interim Bearer | Product flow: mint first, then authenticate |
| JSON LoginResponse | Session step returns metadata for clients |
| ROLE_INTERIM vs ROLE_USER/ADMIN | Makes “higher security” access token meaningful |
| Denylist interim on login | Single-use upgrade path |
| Denylist access on logout | Stateless revoke until `exp` |
| Pass plain password into `UsernamePasswordAuthenticationToken` | Spring compares with `PasswordEncoder.matches`; never `encode` on login |
| Hash only at user creation (`encode`) | BCrypt salts make `encode` non-deterministic; storage-only |

---

## 10. Security notes

1. Interim mint remains public — rate limiting is future work.  
2. Access tokens are secrets; use HTTPS in production.  
3. In-memory denylist is process-local (not multi-instance).  
4. Override `app.jwt.secret` and default admin password outside dev.  
5. Login body carries the **plain** password over the wire — HTTPS required in non-local environments.  
6. Stored passwords must always be BCrypt hashes; never store plain text in `users.password`.

---

## 11. Change control

| Version | Date | Notes |
|---------|------|--------|
| **1.0.0** | 2026-08-02 | Initial SPEC: multi-step login/logout extracted from SPEC-request-bearer-token; documents as-built branch implementation |
| 1.1.0 | 2026-08-02 | Expanded §8 verification: curl, Postman, prerequisites, common failures, automated test command |
| 1.2.0 | 2026-08-02 | Document password verification (`authenticate` + `matches` vs `encode`); seed caveat; Postman pitfalls for invalid_credentials |
