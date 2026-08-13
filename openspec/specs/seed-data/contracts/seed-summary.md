# Contract: Seed data summary

| Field | Value |
|-------|--------|
| **Capability** | seed-data |
| **File** | `src/main/resources/data.sql` |
| **Images** | `src/main/resources/mock-images/` (base64 into `asset_images`) |
## Users (dev)

| id | name | email | role | password (plaintext, dev-only) |
|----|------|-------|------|--------------------------------|
| 1 | admin | admin@localhost | ADMIN | `admin1234` |
| 2 | Alex Tan | alex.tan@example.sg | USER | `customer123` |
| 3 | Ravi Kumar | ravi.kumar@example.sg | ADMIN | `admin123` |
| 4 | Ah Tan | ah.tan@example.sg | DRIVER | `driver123` |
| 5 | Mei Ling | mei.ling@example.sg | USER | `customer234` |
| 6 | Farid Rahman | farid.rahman@example.sg | USER | `customer345` |
| 7 | Mei Lin | mei.lin@example.sg | USER | `customer456` |

Hashes: BCrypt via same encoder as `SecurityConfig`. `users` insert: `ON CONFLICT (id) DO UPDATE`.

## Categories (4)

Excavator · Scissors Lift · Boom Lift · Fork Lift

## Scale (as-built 2.x)

| Table group | Approx volume |
|-------------|----------------|
| Assets | 27 (spec-band depth + coverage) |
| Bookings | 90 (all statuses exercised) |
| Images | Reused mock-image base64 set |

Exact rows: inspect `data.sql`.

## Config

```properties
spring.jpa.defer-datasource-initialization=true
spring.sql.init.mode=always
```

## Related

- Entity model: [`../../entity-repository/`](../../entity-repository/)  
- Auth login against seed: [`../../auth-login-logout/`](../../auth-login-logout/)
