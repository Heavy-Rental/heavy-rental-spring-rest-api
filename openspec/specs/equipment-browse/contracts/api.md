# Contract: `/api/assets`

| Field | Value |
|-------|--------|
| **Capability** | equipment-browse |
| **Status** | As-built |
| **Note** | Route family renamed from `/api/equipment` to `/api/assets` (2026-08-13), matching the `Asset`/`AssetService`/`AssetRepository` naming already used underneath. `AssetController`/`AssetRequest`/`AssetResponse` replace the old `Equipment*` types. |

## `GET /api/assets`

Query (all optional): `category` (exact category name), `search` (case-insensitive name substring), `condition` (`ConditionType`), `startDate`/`endDate` (ISO dates, both or neither).

Roles: `ROLE_USER`, `ROLE_ADMIN` (blanket SecurityConfig rule — also serves the public customer-facing browse feature).

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
  "tags": [],
  "serialno": "CAT320-2021-0042",
  "lastConditionUpdatedAt": "2026-08-10T09:15:00",
  "utilization": 62.5
}
```

`available` is `null` when no date window; `tags` always `[]` as-built. `serialno` and `lastConditionUpdatedAt` were previously on the `Asset` entity but never returned — `lastConditionUpdatedAt` auto-stamps server-side only when `condition` actually changes (create with a condition set, or a real change on replace/patch), never client-supplied. `utilization` is this asset's current-month booked-days percentage — same day-overlap math as [monthly-utilization](../../monthly-utilization/spec.md), computed per-asset instead of fleet-wide; always `0.0` on `create` (a brand-new asset can't have bookings yet).

## Other routes

| Method | Path | Roles | Notes |
|--------|------|-------|--------|
| `GET` | `/api/assets/{id}` | `ROLE_USER`, `ROLE_ADMIN` | Same body shape; optional date query for `available` |
| `POST` | `/api/assets` | `ROLE_ADMIN` only | Create from `AssetRequest`; `409` if `name` already in use |
| `PUT` | `/api/assets/{id}` | `ROLE_ADMIN` only | Full replace; `409` if `name` collides with another asset |
| `PATCH` | `/api/assets/{id}` | `ROLE_ADMIN` only | Partial update; same `409` name-collision check when `name` is supplied |
| `DELETE` | `/api/assets/{id}` | `ROLE_ADMIN` only | `204` / `404` / `409` |
| `PUT` | `/api/assets/{id}/image` | `ROLE_ADMIN` only | Body `{"image": "<base64>"}` (`AssetImageRequest`); replaces any existing image for the asset. `400` if blank, `413` if over ~7MB base64, `404` if asset missing |

`GET` stays on the blanket `ROLE_USER`/`ROLE_ADMIN` rule; all write verbs (including the image upload) are gated to `ROLE_ADMIN` in `SecurityConfig` — closes a prior gap where any authenticated user could write equipment.

Auth: `Authorization: Bearer <access-jwt>`.

## Related

- Entity model: [`../../entity-repository/`](../../entity-repository/)  
- Seed images: [`../../seed-data/`](../../seed-data/)  
- Recommender quote `items[].equipment.img` uses the same data-URI rule: [`../../haystack-recommender/contracts/portal-api.md`](../../haystack-recommender/contracts/portal-api.md)
- Route index: [`../../api-index/contracts/routes.md`](../../api-index/contracts/routes.md)
