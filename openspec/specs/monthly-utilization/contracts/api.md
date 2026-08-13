# Contract: `GET /api/monthly-utilization`

| Field | Value |
|-------|--------|
| **Capability** | monthly-utilization |
| **Status** | As-built |
| **Code** | `MonthlyUtilizationController`, `MonthlyUtilizationService`, `MonthlyUtilizationResponse` |

## Request

```http
GET /api/monthly-utilization HTTP/1.1
Authorization: Bearer <admin-access-jwt>
```

SecurityConfig: `.requestMatchers("/api/monthly-utilization").hasAuthority("ROLE_ADMIN")`.

## Success `200`

Array of:

```json
{
  "id": 1,
  "month": "2026-03",
  "utilization": 12.5,
  "revenue": 1500.00
}
```

Field types follow `MonthlyUtilizationResponse` record as-built.

## Errors

| Condition | HTTP |
|-----------|------|
| No token | `401` |
| Non-admin | `403` |
