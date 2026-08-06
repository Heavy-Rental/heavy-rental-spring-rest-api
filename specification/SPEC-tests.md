# Specification: Test Flow

| Field | Value |
|-------|--------|
| **Document type** | SDD test reference (as-built) |
| **Status** | Implemented |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related code** | `src/test/java/com/heavy_rental/rest_api/RestApiApplicationTests.java`; `src/test/java/com/heavy_rental/rest_api/controller/AuthenticationIntegrationTest.java` |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md), [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md), [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md) |

---

## 1. Test classes

- **`RestApiApplicationTests`** — `@SpringBootTest` smoke test, `contextLoads()` only. Confirms the Spring context wires up (beans, JPA, security config) with no assertions beyond that.
- **`AuthenticationIntegrationTest`** — `@SpringBootTest @AutoConfigureMockMvc`, 11 tests driving the full interim-token → login → access-token → logout flow through `MockMvc` against real controllers/security filters.

## 2. Database target

Both classes run with no test profile, no `@DataJpaTest`, no Testcontainers/H2 — they use the same `application.properties` as `spring-boot:run`, i.e. the same Postgres instance (`POSTGRES_HOSTNAME`) as local dev. There is no separate test database in this project today.

## 3. Test isolation

`AuthenticationIntegrationTest.createUser()` (`@BeforeEach`) inserts a throwaway `User` via `userRepository.save(...)` for each test to log in as. The class is annotated `@Transactional`, so that insert and everything each test does through `MockMvc` in the same thread share one transaction that rolls back at test end — no row is ever committed to the shared Postgres instance.

This was added after the class ran without `@Transactional`: every `mvn test` run left a permanent `Test User <uuid>` row in `users`, which accumulated over repeated runs and polluted the `data.sql`-seeded rows (see [`SPEC-seed-data.md`](./SPEC-seed-data.md) §6.0) with junk visible through any query or the API.

## 4. `AuthenticationIntegrationTest` flow

Per-test setup mints a fresh user (`email`, `password` fields) via `createUser()`. Tests cover:

1. `getBearerTokenReturnsInterimJwt` — `GET /api/auth/getBearerToken` returns a plain-text interim JWT (`ROLE_INTERIM`, no `ROLE_USER`) with a valid `generatedAt` claim.
2. `interimTokenCannotCallLogout` / `interimTokenCannotCallProtectedBusinessPath` — interim tokens are rejected (403) on non-login endpoints.
3. `loginWithoutBearerReturns401` — `POST /api/auth/login` without an interim token is rejected.
4. `loginWithInterimAndBadPasswordReturns401` — wrong password with a valid interim token → 401 `invalid_credentials`.
5. `loginWithInterimAndValidCredentialsReturnsAccessToken` — correct credentials + interim token → `ROLE_USER` access token.
6. `loginWithAccessTokenReturns403` — an access token can't be replayed against `/login`.
7. `interimTokenCannotBeReusedAfterLogin` — an interim token is single-use.
8. `logoutRevokesAccessToken` — `POST /api/auth/logout` revokes the access token; reuse after logout → 401.
9. `protectedEndpointWithoutTokenReturns401` — no token on a protected endpoint → 401.

Helpers `mintInterim()` and `loginAndGetAccessToken()` factor out the common interim-token and full-login steps used across tests.
