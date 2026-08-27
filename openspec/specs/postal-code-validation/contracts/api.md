# Contract: `GET /api/postalCodes/{postalCode}`

| Field | Value |
|-------|--------|
| **Capability** | postal-code-validation |
| **Status** | As-built |

## `GET /api/postalCodes/{postalCode}`

```http
GET /api/postalCodes/619094
Authorization: Bearer <access-jwt>
```

Roles: `ROLE_USER` or `ROLE_ADMIN`.

### `200` VALID

```json
{
  "status": "VALID",
  "postalCode": "619094",
  "address": "20 JURONG PORT ROAD SINGAPORE 619094"
}
```

### `200` INVALID

```json
{
  "status": "INVALID",
  "postalCode": "999999",
  "message": "No address found for this postal code"
}
```

### `400` malformed (never calls OneMap)

```json
{
  "error": "bad_request",
  "message": "Postal code must be exactly 6 digits"
}
```

### `503` lookup unavailable

```json
{
  "status": "UNAVAILABLE",
  "postalCode": "619094",
  "message": "Postal code lookup is temporarily unavailable — you may continue"
}
```

`VALID`/`INVALID` share HTTP `200` — branch on `status`. `UNAVAILABLE` is `503` so a transient outage is distinct from an invalid field.

## Recommended portal behavior

1. Call on blur of the postal-code input (or once six digits are typed), not on every keystroke.
2. `VALID` → clear inline error, allow submit.
3. `INVALID` or `400` → inline error, block submit until resolved.
4. `503` or network failure → do **not** hard-block; quote already falls back to default distance.

## Related

- Living spec: [`../spec.md`](../spec.md)  
- Site address on plans: [`../../rental-plan-quote/`](../../rental-plan-quote/)  
- Change pack: [`../../../changes/pricing-postal-distance/`](../../../changes/pricing-postal-distance/)  
