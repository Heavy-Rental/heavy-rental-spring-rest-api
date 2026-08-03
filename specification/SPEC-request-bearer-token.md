# Specification: Request Bearer Token REST Endpoint

| Field | Value |
|-------|--------|
| **Feature** | Request interim Bearer token |
| **Status** | As-built |
| **Module** | `heavy-rental-spring-rest-api` |
| **Primary path** | `GET /api/auth/getBearerToken` |
| **Method** | Specification Driven Design (SDD) |
| **Related code** | `Authentication`, `AuthService`, `JwtService`, `SecurityConfig` |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |
| **Related feature** | Login/logout upgrade flow: [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) |

This document is the **single source of truth** for **`GET /api/auth/getBearerToken` only** — minting an interim JWT. Login, session tokens, and logout are specified in **SPEC-auth-login-logout.md**, not here.

When code and this document diverge, update them deliberately.

---

## 1. Outcomes

When this feature is correct:

1. Any client can call `GET /api/auth/getBearerToken` **without credentials** and receive a **raw JWT** (`text/plain`).
2. Each token is minted from a **random UUID** (`sub`) and the **current date/time** (`generatedAt` / `iat`).
3. The token is an **interim** tier token (`tokenType=interim`, `roles=["ROLE_INTERIM"]`) suitable for the login step defined in SPEC-auth-login-logout.
4. Token signing, issuer, and lifetime follow project JWT configuration.
5. Integration tests cover issuance and claim shape for this endpoint.

---

## 2. Scope

### 2.1 In scope

- Public `GET /api/auth/getBearerToken` (no auth header required)
- JWT issuance (HS256) via `JwtService` with:
  - random UUID as `sub`
  - current instant as `iat` / `generatedAt`
  - `tokenType` = `"interim"`
  - `roles` = `["ROLE_INTERIM"]`
- Security `permitAll` for this path
- Verification for this endpoint only

### 2.2 Out of scope (documented elsewhere)

- `POST /api/auth/login` and `POST /api/auth/logout` → see [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md)
- Session (access) tokens and user-bound roles
- Register API, refresh tokens, OAuth2/OIDC, rate limiting

### 2.3 Related surface

| Method | Path | Auth | Role relative to this SPEC |
|--------|------|------|----------------------------|
| `POST` | `/api/auth/login` | Interim Bearer | Consumes interim token; **not** defined here |
| `POST` | `/api/auth/logout` | Access Bearer | Session lifecycle; **not** defined here |
| `GET` | `/actuator/health`, `/info` | Public | Ops |

---

## 3. Requirements

### Requirement 1: Mint interim token from UUID + date/time

**User story:** As an API client, I want an interim Bearer JWT without logging in so I can proceed to authenticated steps (e.g. login).

#### Acceptance criteria

1. **GIVEN** any unauthenticated client  
   **WHEN** they send `GET /api/auth/getBearerToken` with no `Authorization` header  
   **THEN** the response is `200 OK`, `Content-Type` is `text/plain`, and the body is a raw JWT compact string (no JSON, no `Bearer ` prefix).

2. **GIVEN** a successful response body `T`  
   **WHEN** `T` is decoded with the application `JwtDecoder`  
   **THEN**:
   - `sub` is a valid UUID string  
   - claim `generatedAt` is an ISO-8601 instant at issuance time  
   - claim `tokenType` is `"interim"`  
   - `roles` contains `ROLE_INTERIM` (and not application user roles such as `ROLE_USER` solely from this mint)  
   - `jti`, `iss`, `iat`, and `exp` are present per JWT design  

3. **GIVEN** two successive successful calls  
   **WHEN** both tokens are decoded  
   **THEN** their `sub` values are different.

### Requirement 2: Client can use the token as a Bearer header

1. **GIVEN** raw token string `T` from getBearerToken  
   **WHEN** the client attaches it to a request  
   **THEN** the header form is `Authorization: Bearer T` (single space after `Bearer`).

> **Note:** What routes accept an interim token (e.g. login only) is defined in SPEC-auth-login-logout and SecurityConfig, not in this document.

---

## 4. Design

### 4.1 Flow

```
Client
  │  GET /api/auth/getBearerToken
  ▼
Authentication.getBearerToken()
  ▼
AuthService.getBearerToken()
  │  uuid = UUID.randomUUID()
  │  generatedAt = Instant.now()
  │  JwtService.generateToken(uuid, [ROLE_INTERIM], generatedAt, "interim")
  ▼
200 text/plain → jwt.getTokenValue()
```

### 4.2 API contract

#### Request

```http
GET /api/auth/getBearerToken HTTP/1.1
```

No body. No `Authorization` header required. Endpoint is **public** (`permitAll`).

#### Success — `200 OK`

| Aspect | Value |
|--------|--------|
| Content-Type | `text/plain` |
| Body | Raw JWT compact serialization only |

### 4.3 JWT claims (interim)

| Claim | Value |
|-------|--------|
| `jti` | New random UUID (token id) |
| `sub` | Random UUID string |
| `tokenType` | `"interim"` |
| `generatedAt` | ISO-8601 string of mint time (UTC instant) |
| `iat` | Same mint instant |
| `exp` | `iat` + `app.jwt.expirationMinutes` |
| `iss` | `app.jwt.issuer` |
| `roles` | `["ROLE_INTERIM"]` |

Algorithm: **HS256** with `app.jwt.secret` (≥ 32 characters).

### 4.4 Components

| Concern | Location |
|---------|----------|
| HTTP mapping | `controller/Authentication.java` (`getBearerToken`) |
| UUID + time mint | `service/AuthService.getBearerToken` |
| JWT encode | `security/JwtService.java` |
| Public route | `config/SecurityConfig.java` (`GET /api/auth/getBearerToken` → `permitAll`) |

### 4.5 Security note

Anyone who can reach this endpoint receives a valid **interim** JWT. It is not a full user session token. How interim vs access tokens are authorized is specified in **SPEC-auth-login-logout.md**.

---

## 5. Verification

### 5.1 Checklist

- [ ] No credentials → `200` + plain JWT  
- [ ] `sub` is UUID; `generatedAt` near now  
- [ ] `tokenType=interim`; `roles` includes `ROLE_INTERIM`  
- [ ] Distinct subjects across calls  

### 5.2 Manual smoke

```bash
TOKEN=$(curl -s http://localhost:8080/api/auth/getBearerToken)
echo "$TOKEN"
# Continue with login using this token — see SPEC-auth-login-logout.md
```

---

## 6. Key decisions

| Decision | Rationale |
|----------|-----------|
| Public mint without credentials | First hop of multi-step auth; simple for clients/tools |
| `sub` = UUID, `generatedAt` = ISO time | Clear identity vs mint timestamp |
| `ROLE_INTERIM` only on this token | Separates interim mint from user session (login SPEC) |
| This SPEC scoped to getBearerToken only | Keeps request-token contract independent of login/logout |

---

## 7. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.x / 2.0.0 | 2026-08-02 | Earlier Basic-auth and multi-endpoint designs |
| 3.0.0 | 2026-08-02 | Anonymous UUID + date/time mint |
| 4.0.0 | 2026-08-02 | Multi-step auth temporarily combined into this file |
| **5.0.0** | 2026-08-02 | **Split:** this file = getBearerToken only; login/logout moved to SPEC-auth-login-logout.md |
