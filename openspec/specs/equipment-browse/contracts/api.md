# Contract: `/api/equipment`

| Field | Value |
|-------|--------|
| **Capability** | equipment-browse |
| **Status** | As-built |

## `GET /api/equipment`

Query (all optional): `category` (exact category name), `search` (case-insensitive name substring), `condition` (`ConditionType`), `startDate`/`endDate` (ISO dates, both or neither).

**Success `200`** — array of:

```json
{
  "id": 1,
  "name": "CAT 320 Excavator",
  "category": "Excavator",
  "baseDailyRate": 450.00,
  "minDailyRate": 400.00,
  "maxDailyRate": 500.00,
  "capacity": null,
  "platformHeight": null,
  "purchaseYear": 2021,
  "condition": "GOOD",
  "available": true,
  "desc": "...",
  "img": "data:image/jpeg;base64,/9j/...",
  "location": "Tuas",
  "tags": []
}
```

`available` is `null` when no date window; `tags` always `[]` as-built.

## Other routes

| Method | Path | Notes |
|--------|------|--------|
| `GET` | `/api/equipment/{id}` | Same body shape; optional date query for `available` |
| `POST` | `/api/equipment` | Create from `EquipmentRequest` |
| `PUT` | `/api/equipment/{id}` | Full replace |
| `PATCH` | `/api/equipment/{id}` | Partial update |
| `DELETE` | `/api/equipment/{id}` | `204` / `404` / `409` |

Auth: `Authorization: Bearer <access-jwt>`.

## Related

- Entity model: [`../../entity-repository/`](../../entity-repository/)  
- Seed images: [`../../seed-data/`](../../seed-data/)
