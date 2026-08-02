# Specification: Request Bearer Token REST Endpoint

| Field | Value |
|-------|--------|
| **Feature** | Request Bearer Token |
| **Status** | As-built (UUID + date/time issuance) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Primary path** | `GET /api/auth/getBearerToken` |
| **Method** | Specification Driven Design (SDD) |
| **Related code** | `Authentication`, `AuthService`, `JwtService`, `SecurityConfig` |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |

This document is the **single source of truth** for obtaining a Bearer access token. The only public auth endpoint is **`GET /api/auth/getBearerToken`**. When code and this document diverge, update them deliberately.

---

## 1. Outcomes

When this feature is correct:

1. Any client can call `GET /api/auth/getBearerToken` **without credentials** and receive a **raw JWT** (`text/plain`).
2. Each token is minted from a **random UUID** (subject) and the **current date/time** (`generatedAt` claim / `iat`).
3. The client can call protected REST endpoints with `Authorization: Bearer <token>`.
4. Token signing, issuer, and lifetime follow project JWT configuration.
5. Integration tests cover issuance and unauthenticated protected access.

---

## 2. Scope

### 2.1 In scope

- Public `GET /api/auth/getBearerToken` with **no authentication header required**
- JWT issuance (HS256) via `JwtService` using:
  - random UUID as `sub`
  - current instant as `iat` / `generatedAt`
  - default role `ROLE_USER`
- Security `permitAll` for this path
- Verification and client Bearer usage

### 2.2 Out of scope

- HTTP Basic / username-password validation for this endpoint
- `POST /api/auth/login`, register, logout, `/user`
- Binding tokens to the `users` table
- Persisting issued UUIDs
- Rate limiting of token minting
- OAuth2 authorization server grants

### 2.3 Related surface

| Method | Path | Auth | Role |
|--------|------|------|------|
| `GET` | `/actuator/health`, `/info` | Public | Ops |
| *(future protected APIs)* | `/api/**` | Bearer JWT | Consumers of issued tokens |

---

## 3. Requirements

### Requirement 1: Mint a token from UUID + date/time

**User story:** As an API client, I want a Bearer JWT without logging in so I can call protected APIs.

#### Acceptance criteria

1. **GIVEN** any unauthenticated client  
   **WHEN** they send `GET /api/auth/getBearerToken` with no `Authorization` header  
   **THEN** the response is `200 OK`, `Content-Type` is `text/plain`, and the body is a raw JWT compact string (no JSON, no `Bearer ` prefix).

2. **GIVEN** a successful response body `T`  
   **WHEN** `T` is decoded with the application `JwtDecoder`  
   **THEN**:
   - `sub` is a valid UUID string  
   - claim `generatedAt` is an ISO-8601 instant at issuance time  
   - `roles` contains `ROLE_USER`  
   - `jti`, `iss`, `iat`, and `exp` are present per JWT design  

3. **GIVEN** two successive successful calls  
   **WHEN** both tokens are decoded  
   **THEN** their `sub` values are different.

### Requirement 2: Client attaches Bearer token on protected calls

1. **GIVEN** raw token string `T`  
   **WHEN** the client calls a protected endpoint  
   **THEN** the request includes `Authorization: Bearer T`.

2. **GIVEN** no Bearer token on a protected route  
   **WHEN** the client calls that route  
   **THEN** the response is `401` with `error` of `"unauthorized"`.

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
  │  JwtService.generateToken(uuid, [ROLE_USER], generatedAt)
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

### 4.3 JWT claims

| Claim | Value |
|-------|--------|
| `jti` | New random UUID (token id) |
| `sub` | Random UUID string (identity for this mint) |
| `generatedAt` | ISO-8601 string of mint time (UTC instant) |
| `iat` | Same mint instant |
| `exp` | `iat` + `app.jwt.expirationMinutes` |
| `iss` | `app.jwt.issuer` |
| `roles` | `["ROLE_USER"]` |

Algorithm: **HS256** with `app.jwt.secret` (≥ 32 characters).

### 4.4 Components

| Concern | Location |
|---------|----------|
| HTTP mapping | `controller/Authentication.java` |
| UUID + time mint | `service/AuthService.java` |
| JWT encode | `security/JwtService.java` |
| Public route | `config/SecurityConfig.java` |

### 4.5 Security note

**Anyone who can reach this endpoint receives a valid JWT** that authenticates as `ROLE_USER` on protected routes. This is intentional anonymous minting. Do not use in production without additional controls (network restriction, rate limits, or reintroducing identity checks) without a new SDD.

---

## 5. Verification

### 5.1 Checklist

- [ ] No credentials → `200` + plain JWT  
- [ ] `sub` is UUID; `generatedAt` near now  
- [ ] Distinct subjects across calls  
- [ ] Protected route without Bearer → `401`  

### 5.2 Automated tests

| Test | Covers |
|------|--------|
| `getBearerTokenReturnsPlainTokenWithUuidAndGeneratedAt` | Success + claims |
| `getBearerTokenIssuesUniqueSubjects` | Uniqueness |
| `protectedEndpointWithoutTokenReturns401` | Resource server |

### 5.3 Manual smoke

```bash
TOKEN=$(curl -s http://localhost:8080/api/auth/getBearerToken)
echo "$TOKEN"

curl -s http://localhost:8080/<protected-path> \
  -H "Authorization: Bearer $TOKEN"
```

---

## 6. Key decisions

| Decision | Rationale |
|----------|-----------|
| No Basic auth | Caller requested UUID + date/time generation instead of user authentication |
| `sub` = UUID, `generatedAt` = ISO time | Clear separation of identity vs mint timestamp |
| Default `ROLE_USER` | Issued tokens remain usable with standard role checks |
| Anonymous minting | Simplest match to “generate bearer token” without credentials |

---

## 7. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.x / 2.0.0 | 2026-08-02 | Earlier Basic-auth and multi-endpoint designs |
| **3.0.0** | 2026-08-02 | **Breaking:** mint token from random UUID + current date/time; no Basic credentials |
