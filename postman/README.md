# Postman — Heavy Rental Spring REST API

Manual API collection for **`heavy-rental-spring-rest-api`** only (not haystack, not the React app).

| File | Purpose |
|------|---------|
| [`Heavy-Rental-Spring-REST-API.postman_collection.json`](./Heavy-Rental-Spring-REST-API.postman_collection.json) | Full route set + test scripts |
| [`Heavy-Rental-Spring-REST-API.postman_environment.json`](./Heavy-Rental-Spring-REST-API.postman_environment.json) | Local defaults (`baseUrl`, seed user) |

## Import

1. Postman → **Import** → select both JSON files (or the whole `postman/` folder).
2. Select environment **Heavy Rental Spring — Local** (or use collection variables alone).
3. Start the Spring app on port **8080**.

## Auth (do this first)

Routes other than interim token, login, health, and Stripe webhook need an **access JWT**.

1. **Auth → 1. Get interim Bearer token**  
   - Public `GET /api/auth/getBearerToken`  
   - Test script saves body → `interimToken`
2. **Auth → 2. Login**  
   - `Authorization: Bearer {{interimToken}}`  
   - Body: `{{email}}` / `{{password}}`  
   - Test script saves `accessToken`  
3. Other folders use collection auth: **Bearer `{{accessToken}}`**.

### Seed users (`data.sql`)

| Email | Password | Role |
|-------|----------|------|
| `alex.tan@example.sg` | `customer123` | USER (default in env) |
| `admin@localhost` | `admin1234` | ADMIN |

## Folders

| Folder | Notes |
|--------|--------|
| **0. Health** | `GET /actuator/health` (public) |
| **1. Auth** | Interim → login → logout |
| **2. Recommendations (S2b)** | Submit quote · knowledge-query · get session |
| **3. Equipment** | Browse / CRUD |
| **4. Bookings** | Create / list / get / update |
| **5. Deliveries & Returns** | List + status patch |
| **6. Payments** | Deposit intent (needs Stripe key); webhook (signature) |
| **7. Stubs** | Depots / rental-plans empty lists |

## Recommendations (S2b) checklist

1. Complete **Auth** login.
2. Ensure **haystack-fast-api** is up (`haystack.base-url`, default `http://localhost:8000`).
3. Run **Submit project-spec** — expects `quoteRef` + `items` (not `answer`).
4. Run **Knowledge query** — expects Call 3 `answer`.
5. Run **Get recommendation session** — DB only.

Submit test script asserts the portal body is **quote-shaped** and stores `recommendationId`.

## Variables

| Variable | Default | Set by |
|----------|---------|--------|
| `baseUrl` | `http://localhost:8080` | you |
| `email` / `password` | Alex Tan seed | you |
| `interimToken` | — | Get interim request |
| `accessToken` | — | Login request |
| `recommendationId` | `1` | Submit project-spec (success) |
| `bookingId` | `1` | Create booking (if id present) |
| `equipmentId` | `1` | you |
| `correlationId` | `postman-corr-001` | optional header on submit |

## Contracts

- Index: [`../specification/SPEC-api-index.md`](../specification/SPEC-api-index.md)
- Auth: [`../specification/SPEC-auth-login-logout.md`](../specification/SPEC-auth-login-logout.md)
- Recommender: [`../specification/SPEC-haystack-recommender-client.md`](../specification/SPEC-haystack-recommender-client.md)
