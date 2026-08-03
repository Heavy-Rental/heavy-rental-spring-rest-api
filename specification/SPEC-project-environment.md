# Specification: Project Environment & Setup

| Field | Value |
|-------|--------|
| **Document type** | SDD baseline / constitution (not a feature spec) |
| **Status** | As-built living context |
| **Workspace** | `/workspaces/heavy-rental-rest-api` |
| **Application module** | `heavy-rental-spring-rest-api` |
| **Base package** | `com.heavy_rental.rest_api` |
| **Audience** | Engineers and agents writing subsequent feature specs |

**Read this document first** before implementing any new feature under Specification Driven Development (SDD). Feature specs assume the environment, stack, and conventions described here.

Related feature specs:

- [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md) — login / Bearer JWT issuance and usage

---

## 1. Purpose

This specification captures the **current project environment and setup** so later SDD work:

1. Knows where the code lives and how packages are organized.
2. Uses the **existing PostgreSQL** service (host `db`) and does not reintroduce Compose/H2.
3. Reuses established **security, JWT, error-handling, and layering** patterns.
4. Respects **configuration via environment variables** and `application.properties`.
5. Knows how to **build, test, and smoke** the API.

When this document and the codebase diverge, update them in the same change set.

---

## 2. Outcomes

When this context is followed:

- New feature SDDs do not restate the full stack unless they intentionally change it.
- Implementers do not add alternate databases or Docker Compose as the primary DB path for this workspace.
- Auth-protected endpoints continue to work with the existing Bearer JWT resource-server model.
- Controllers stay thin; services own business rules; shared error JSON remains consistent.

---

## 3. Repository layout

```text
heavy-rental-rest-api/                         # workspace root
├── SPEC-project-environment.md                # this file (SDD baseline)
├── SPEC-request-bearer-token.md               # feature SDD
└── heavy-rental-spring-rest-api/              # Spring Boot application
    ├── pom.xml
    ├── mvnw / mvnw.cmd
    ├── HELP.md
    ├── LICENSE.txt
    ├── README.md / BLANK_README.md / CHANGELOG.md
    ├── images/
    └── src/
        ├── main/
        │   ├── java/com/heavy_rental/rest_api/
        │   │   ├── RestApiApplication.java
        │   │   ├── ServletInitializer.java      # WAR deployment support
        │   │   ├── config/                      # security, JWT props, errors, seed user
        │   │   ├── controller/                  # REST controllers
        │   │   ├── dto/                         # request/response records
        │   │   ├── entity/                      # JPA entities
        │   │   ├── repository/                  # Spring Data repositories
        │   │   ├── security/                    # JwtService, TokenDenylist
        │   │   └── service/                     # business services
        │   └── resources/
        │       ├── application.properties
        │       ├── static/
        │       └── templates/
        └── test/
            └── java/com/heavy_rental/rest_api/
                ├── RestApiApplicationTests.java
                └── controller/AuthenticationIntegrationTest.java
```

**There is no `compose.yaml`.** Docker Compose was removed; the database is an external shared PostgreSQL instance.

---

## 4. Technology stack (normative)

| Layer | Choice |
|-------|--------|
| Language | Java **21** |
| Framework | Spring Boot **4.1.0** |
| Maven coordinates | `com.heavy_rental:rest_api:0.0.1-SNAPSHOT` |
| Packaging | **WAR** |
| Web | Spring WebMVC (`spring-boot-starter-webmvc`) |
| Security | Spring Security + **OAuth2 Resource Server** (JWT) |
| Persistence | Spring Data JPA + Hibernate |
| Database driver | PostgreSQL JDBC (`postgresql`, runtime) |
| Observability | Spring Boot Actuator |
| Build | Maven Wrapper (`./mvnw`) |
| Utilities | Lombok (optional), DevTools (runtime optional) |
| Embedded container (dev) | Tomcat (starter provided scope for WAR) |

### 4.1 Key Maven dependencies

**Runtime / main**

- `spring-boot-starter-actuator`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-restclient`
- `spring-boot-starter-security`
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-webmvc`
- `postgresql`
- `lombok` (optional)
- `spring-boot-starter-tomcat` (provided)

**Test**

- `spring-boot-starter-data-jpa-test`
- `spring-boot-starter-restclient-test`
- `spring-boot-starter-security-test`
- `spring-boot-starter-webmvc-test`

---

## 5. Runtime environment

### 5.1 Application process

| Setting | Value |
|---------|--------|
| `spring.application.name` | `rest_api` |
| HTTP port | `8080` |
| Config file | `src/main/resources/application.properties` |

### 5.2 PostgreSQL (existing shared service)

The API **must** use the project’s existing PostgreSQL. Connectivity is expected on hostname **`db`** (verify with `ping db` on the project network).

| Setting | Property / env | Default |
|---------|----------------|---------|
| JDBC URL | `spring.datasource.url` | `jdbc:postgresql://${POSTGRES_HOSTNAME:db}:${POSTGRES_PORT:5432}/${POSTGRES_DB:postgres}` |
| Username | `spring.datasource.username` | `${POSTGRES_USER:postgres}` |
| Password | `spring.datasource.password` | `${POSTGRES_PASSWORD:postgres}` |
| Driver | `spring.datasource.driver-class-name` | `org.postgresql.Driver` |
| Dialect | `spring.jpa.database-platform` | `PostgreSQLDialect` |
| DDL | `spring.jpa.hibernate.ddl-auto` | `update` |
| SQL log | `spring.jpa.show-sql` | `true` |
| Open-in-view | `spring.jpa.open-in-view` | `false` |

#### Environment constraints (binding for future SDD)

1. **Do not** add H2, Derby, or other embedded databases for the default app or default tests in this environment.
2. **Do not** reintroduce Docker Compose as the primary way to provision Postgres for this workspace.
3. **Do not** hardcode a different host without updating this spec; prefer `POSTGRES_HOSTNAME` / default `db`.
4. Schema management today is Hibernate `ddl-auto=update` only (no Flyway/Liquibase yet). Introducing migrations requires an explicit feature SDD and an update to this document.

### 5.3 JWT and default-user configuration

| Property | Env override | Purpose |
|----------|--------------|---------|
| `app.jwt.secret` | `APP_JWT_SECRET` | HS256 signing key (**≥ 32 characters**) |
| `app.jwt.issuer` | `APP_JWT_ISSUER` | JWT `iss` claim + decoder validation |
| `app.jwt.expirationMinutes` | `APP_JWT_EXPIRATION_MINUTES` | Access-token lifetime |
| `app.security.default-username` | `APP_DEFAULT_USERNAME` | Seed admin username if `users` is empty |
| `app.security.default-password` | `APP_DEFAULT_PASSWORD` | Seed admin password (dev convenience) |

Defaults in `application.properties` are for local/dev convenience. Production must supply strong secrets and non-default admin credentials.

### 5.4 Security model (summary)

Full auth contracts live in feature specs; this is the environment-level model:

| Aspect | Behavior |
|--------|----------|
| Session | **STATELESS** (no server session for auth) |
| CSRF | Disabled (stateless API) |
| Password storage | BCrypt |
| Tokens | JWT **HS256**; two tiers — **interim** (`tokenType=interim`, `ROLE_INTERIM`) and **access/session** (`tokenType=access`, user roles) |
| Claims | `jti`, `iss`, `iat`, `exp`, `sub`, `roles`, `tokenType` (+ `generatedAt` on interim) |
| Resource server | OAuth2 Resource Server validates Bearer JWTs |
| Revocation | In-memory `TokenDenylist` by `jti` (process-local); logout + interim single-use after login |
| Public routes | `GET /api/auth/getBearerToken`; `/error`; actuator health/info |
| Login | `POST /api/auth/login` requires interim Bearer + credentials → access JWT |
| Logout | `POST /api/auth/logout` requires access Bearer → denylist |
| Protected routes | Business APIs require `ROLE_USER` or `ROLE_ADMIN` (not interim alone) |

---

## 6. Package responsibilities

| Package | Responsibility |
|---------|----------------|
| `config` | `SecurityConfig`, `JwtProperties`, `RestExceptionHandler`, `DefaultUserInitializer` |
| `controller` | HTTP mappings only (thin) |
| `service` | Business logic and orchestration (`AuthService`, `CustomUserDetailsService`) |
| `security` | JWT generation helpers, token denylist |
| `dto` | API request/response types (prefer Java **records**, camelCase JSON) |
| `entity` | JPA entities (e.g. `User` → table `users`) |
| `repository` | Spring Data JPA repositories |

### 6.1 Layering rules (constitution)

1. Controllers must not contain authentication or JWT claim logic beyond wiring.
2. Services throw `ResponseStatusException` (or security exceptions) that map to the shared error JSON.
3. New **public** endpoints require an explicit `permitAll` entry in `SecurityConfig`.
4. New **protected** endpoints rely on existing JWT resource-server configuration; do not invent a second auth filter chain without an SDD.
5. Prefer reusing existing DTOs and error codes (`bad_request`, `unauthorized`, `invalid_credentials`, `conflict`, `forbidden`, …).

---

## 7. Current API inventory

Auth feature SPECs:

- Interim mint: [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md)
- Login / logout session: [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md)

Inventory for environment awareness:

| Method | Path | Auth | Role |
|--------|------|------|------|
| `GET` | `/api/auth/getBearerToken` | Public | Interim JWT (UUID + date/time, `ROLE_INTERIM`); see request-bearer SPEC |
| `POST` | `/api/auth/login` | Interim Bearer | Username/password → session access JWT; see login-logout SPEC |
| `POST` | `/api/auth/logout` | Access Bearer | Revoke session token (`jti` denylist); see login-logout SPEC |
| `GET` | `/actuator/health` | Public | Health |
| `GET` | `/actuator/info` | Public | Info |

### 7.1 Shared error response shape

```json
{
  "error": "<code>",
  "message": "<human-readable reason>"
}
```

Produced by `RestExceptionHandler` and security entry/access-denied handlers.

### 7.2 Domain seed

- Entity: `User` → table `users` (username, BCrypt password, email, role, enabled).
- Roles in use: `ROLE_USER`, `ROLE_ADMIN` (stored as authority strings; JWT claim `roles`).
- `DefaultUserInitializer` creates a single admin when the users table is empty.

---

## 8. Build, test, and run

Work from the application module:

```bash
cd heavy-rental-spring-rest-api

# Unit + integration tests (require reachable Postgres on db)
./mvnw test

# Run the API (port 8080)
./mvnw spring-boot:run
```

### 8.1 Prerequisites

1. PostgreSQL reachable: `ping db` (and TCP `db:5432`).
2. Env vars optional if defaults match the shared instance (`POSTGRES_*`, `APP_JWT_*`).
3. Java 21 available to Maven.

### 8.2 Testing notes

- Integration tests use the **same PostgreSQL configuration** as the app (no H2 in this project).
- Primary auth coverage: `AuthenticationIntegrationTest`.
- Context smoke: `RestApiApplicationTests`.

### 8.3 Manual smoke (examples)

```bash
# Health
curl -s http://localhost:8080/actuator/health

# 1) Interim token
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
echo "$INTERIM"

# 2) Login → session access token (JSON)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<password>"}'

# 3) Protected call with accessToken from login response
curl -s http://localhost:8080/<protected-path> \
  -H "Authorization: Bearer <accessToken>"

# 4) Logout
curl -s -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <accessToken>"
```

---

## 9. SDD process conventions for this repository

### 9.1 Spec files

| Kind | Location | Naming |
|------|----------|--------|
| Environment / constitution | Workspace root | `SPEC-project-environment.md` (this file) |
| Feature | Workspace root | `SPEC-<feature-kebab-case>.md` |

### 9.2 Recommended feature-spec sections

Feature SDDs should include at least:

1. Meta table (feature, status, module, related paths)
2. Outcomes
3. Scope (in / out)
4. Requirements with user stories and **GIVEN / WHEN / THEN** acceptance criteria
5. Design (API contract, components, security notes)
6. Verification (checklist, tests, manual smoke)
7. Implementation tasks
8. Key decisions / non-goals
9. Change control version table

### 9.3 Rules for feature work

1. **Load this environment spec** before drafting or implementing a feature.
2. Do not restate stack/DB defaults unless the feature **changes** them—then update **this** file in the same PR.
3. Align with existing layering, error JSON, and Bearer JWT auth unless the feature SDD explicitly replaces them.
4. Prefer incremental, independently testable changes.
5. Keep feature specs as the contract; keep this file as environment truth.

### 9.4 How agents should use these docs

```text
1. Read SPEC-project-environment.md
2. Read the relevant SPEC-<feature>.md
3. Implement against both (environment constraints + feature requirements)
4. Run ./mvnw test against Postgres on db
5. Update specs if behavior or environment deliberately changes
```

---

## 10. Explicit non-goals / forbidden drift

Unless a dedicated SDD says otherwise:

- No Docker Compose Postgres as the primary database for this workspace
- No H2 (or other embedded DB) for default runtime or default tests
- No cookie-session primary auth replacing Bearer JWT
- No second public API style (e.g. GraphQL) without an environment decision
- No secrets committed as production values; use env overrides
- No expanding token denylist to multi-instance stores without an explicit feature SDD

---

## 11. Key decisions (environment)

| Decision | Rationale |
|----------|-----------|
| External Postgres on host `db` | Shared project network already provides DB; Compose removed to avoid conflict |
| WAR packaging | Supports traditional servlet deployment via `ServletInitializer` |
| OAuth2 Resource Server JWT | Stateless API auth for SPA/mobile/Postman clients |
| Hibernate `ddl-auto=update` | Fast iteration; migrations can be introduced later deliberately |
| SDD markdown at workspace root | Visible, versioned next to feature specs; easy agent context |
| Env-overridable properties | Same artifact works across local/shared environments |

---

## 12. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.0.0 | 2026-08-02 | Initial as-built environment context: Spring Boot 4.1 / Java 21 / WAR, Postgres on `db`, JWT security, SDD conventions, no Compose |
| 1.1.0 | 2026-08-02 | Auth API reduced to `GET /api/auth/getBearerToken` only (register/login/logout/user removed) |
| 1.2.0 | 2026-08-02 | getBearerToken mints JWT from random UUID + date/time (no Basic credentials) |
| 1.3.0 | 2026-08-02 | Multi-step auth inventory: interim getBearerToken → login → logout |
| 1.3.1 | 2026-08-02 | Split feature SPECs: SPEC-request-bearer-token (mint) + SPEC-auth-login-logout (session) |

When changing stack, database strategy, packaging, default security model, or SDD file locations, bump this table and notify dependent feature specs.
