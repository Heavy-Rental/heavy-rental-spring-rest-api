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
| `alex.tan@example.sg` | `customer123` | USER (default in env; has one active QUOTED plan, plus CONVERTED/CANCELLED history) |
| `mei.lin@example.sg` | `customer456` | USER (no plans — cart → quote → checkout) |
| `admin@localhost` | `admin1234` | ADMIN |

## Folders

| Folder | Notes |
|--------|--------|
| **0. Health** | `GET /actuator/health` (public) |
| **1. Auth** | Interim → login → logout |
| **2. Recommendations (S2b)** | Submit quote · knowledge-query · get session |
| **3. Equipment** | Browse / CRUD |
| **4. Bookings** | Direct create / **checkout from plan** / list / get / update |
| **5. Deliveries & Returns** | List + status patch (`CONFIRMED→MOBILISED`, `MOBILISED→COMPLETED`) |
| **6. Payments** | Deposit intent (needs Stripe key); webhook (signature) |
| **7. Rental Plans** | Create / list / get / add item / remove item / quote / cancel (`/api/rentalPlans`) |
| **8. Stubs** | Depots empty list only |
| **9. Postal Codes** | Real-time validation (`/api/postalCodes/{postalCode}`) — 200 VALID/INVALID, 503 UNAVAILABLE |

## Recommendations (S2b) checklist

1. Complete **Auth** login.
2. Ensure **haystack-fast-api** is up (`haystack.base-url`, default `http://localhost:8000`).
3. Run **Submit project-spec** — expects `quoteRef` + `items` (not `answer`).
4. Run **Knowledge query** — expects Call 3 `answer`.
5. Run **Get recommendation session** — DB only.

Submit test script asserts the portal body is **quote-shaped** and stores `recommendationId`.

## Rental plan checkout checklist

1. Complete **Auth** login.
2. For a full walk, login as **Mei Lin** (`mei.lin@example.sg` / `customer456`) so Create is not blocked by BR-06.
3. **Rental Plans → Create** → **Add item** → **Request quote**. Scripts save `rentalPlanId`.
4. **Bookings → Checkout from rental plan** — body is `rentalPlanId` + `siteAddress` (items/dates ignored).
5. Expect `201`, plan `CONVERTED`, `bookingId` saved.
6. If `409 quote_expired`, run **Request quote** again then retry checkout.
7. Alex Tan can skip Create: **Request quote** on seeded plan `3`, then checkout.

`siteAddress` must end with a 6-digit postal code or the API returns `400 validation_failed`.

## Variables

| Variable | Default | Set by |
|----------|---------|--------|
| `baseUrl` | `http://localhost:8080` | you |
| `email` / `password` | Alex Tan seed | you |
| `interimToken` | — | Get interim request |
| `accessToken` | — | Login request |
| `recommendationId` | `1` | Submit project-spec (success) |
| `bookingId` | `1` | Create / checkout booking |
| `rentalPlanId` | `3` | Create plan / quote (seeded Alex Tan QUOTED plan) |
| `rentalPlanItemId` | `1` | Add item |
| `equipmentId` | `1` | you |
| `correlationId` | `postman-corr-001` | optional header on submit |
| `postalCode` | `619094` | you — real Singapore postal code; set to a malformed value (e.g. `12345`) to see the `400` case |

## Contracts

- Route map: [`../openspec/specs/api-index/contracts/routes.md`](../openspec/specs/api-index/contracts/routes.md)
- Auth interim: [`../openspec/specs/auth-interim-token/`](../openspec/specs/auth-interim-token/)
- Auth login/logout: [`../openspec/specs/auth-login-logout/`](../openspec/specs/auth-login-logout/)
- Equipment: [`../openspec/specs/equipment-browse/`](../openspec/specs/equipment-browse/)
- Bookings: [`../openspec/specs/booking-delivery-return/`](../openspec/specs/booking-delivery-return/)  
- Checkout: [`../openspec/specs/rental-plan-quote/contracts/checkout.md`](../openspec/specs/rental-plan-quote/contracts/checkout.md)
- Payments: [`../openspec/specs/payments-stripe/`](../openspec/specs/payments-stripe/)
- Rental plans: [`../openspec/specs/rental-plan-quote/`](../openspec/specs/rental-plan-quote/)
- Admin users: [`../openspec/specs/admin-users/`](../openspec/specs/admin-users/)
- Monthly utilization: [`../openspec/specs/monthly-utilization/`](../openspec/specs/monthly-utilization/)
- Pricing estimate (design): [`../openspec/changes/pricing-estimate/`](../openspec/changes/pricing-estimate/)
- Postal code validation: [`../openspec/changes/pricing-postal-distance/contracts/postal-code-validation.md`](../openspec/changes/pricing-postal-distance/contracts/postal-code-validation.md)
- Recommender: [`../openspec/specs/haystack-recommender/`](../openspec/specs/haystack-recommender/) · [`contracts/portal-api.md`](../openspec/specs/haystack-recommender/contracts/portal-api.md)
- OpenSpec guide: [`../openspec/AGENTS.md`](../openspec/AGENTS.md)
