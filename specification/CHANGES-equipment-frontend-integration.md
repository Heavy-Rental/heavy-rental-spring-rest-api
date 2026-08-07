# Changes Made: Linking the Web Portal to the Real Equipment API

| Field | Value |
|-------|--------|
| **Purpose** | Plain-language log of every change made to get `heavy-rental-react-web-portal`'s "Browse Equipment" page working against this backend's real `/api/equipment`, instead of the mock server |
| **Scope** | Backend (`heavy-rental-spring-rest-api`, this repo) + Frontend (`heavy-rental-react-web-portal`, separate repo, changes described here from session notes since that repo isn't in this workspace) |
| **Related docs** | [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) (backend design detail), [`SPEC-seed-data.md`](./SPEC-seed-data.md) (data fixes) |

---

## 1. Backend changes (this repo)

### 1.1 Built the equipment API itself
New files:
- `dto/EquipmentResponse.java`, `dto/EquipmentRequest.java`
- `service/AssetService.java`
- `controller/EquipmentController.java` — `/api/equipment` (list/get/create/replace/patch/delete)

Small additions (no existing code removed):
- `repository/AssetImageRepository.java` — added `findByAssetIdIn`
- `repository/BookingItemRepository.java` — added `findAssetIdsWithOverlappingBooking`
- `repository/AssetRepository.java` — added `findAllWithCategory`

No `SecurityConfig` changes — new routes fall under the existing auth rule automatically.

### 1.2 Fixed two real data bugs found in `data.sql`
- `CAT 320 Excavator` had 2 photo rows seeded; removed the second (the API only ever returns one photo per equipment item, matching the frontend's actual data shape — see §2.1 below)
- `asset5-jlg-2630es-scissorlift.jpg` (asset id 4's photo) was a PNG mislabeled with a `.jpg` extension; re-encoded to a real JPEG

### 1.3 Added two stub endpoints (purely to unblock the frontend)
- `controller/DepotController.java` — `GET /api/depots` → `[]`
- `controller/RentalPlanController.java` — `GET /api/rental-plans` → `[]`

Neither has a real entity behind it. They exist only because the frontend's equipment page also calls these on every load, and errors out entirely if `/api/depots` fails — even though it's unrelated to equipment itself.

---

## 2. Frontend changes (`heavy-rental-react-web-portal`)

### 2.1 `src/app/api.ts`
- Added `login(email, password)` — calls this backend's real `GET /api/auth/getBearerToken` → `POST /api/auth/login` flow and returns a real access token, instead of the old fake client-generated one.
- Changed `equipmentApi.list()` to accept optional `{ startDate, endDate }`, appended as query params — lets the equipment list reflect real per-date availability.

### 2.2 `src/App.tsx` — `handleLogin`
Made login **mode-aware** (`import.meta.env.MODE`):
- `npm run dev:api` → calls the new real `login()`, stores the real backend token
- `npm run dev:mock` (or plain `npm run dev`) → unchanged, still uses the original fake `issueSession()`

This keeps both modes working — mock and real backend are both still toggle-able by which npm script you run.

### 2.3 `src/App.tsx` — `CustomerPortal`'s `equipmentRes`
Passes `sharedStartDate`/`sharedEndDate` into `equipmentApi.list()` and added them to `useApiResource`'s dependency array, so the equipment list (and each item's `available` flag) refetches automatically whenever the date range changes.

### 2.4 Missing-field crash fixes
This backend's `EquipmentResponse` only has 13 fields; the frontend's `Equipment` type expects 20. The 8 it doesn't provide — `weekly`, `location`, `rating`, `reviews`, `tags`, `utilization`, `revenue`, `hoursThisMonth`, `idealFor` — come back as `undefined`, and several places in the frontend called a method directly on them (`.split`, `.map`, `.toLocaleString`) without checking first, which crashes React with no error boundary anywhere in this app — meaning **any single one of these crashes blanks the entire page**, not just the broken component.

Fixed everywhere this was hit:

| File | What broke | Fix |
|---|---|---|
| `src/features/browse/EquipmentGrid.tsx` | `<img src>` assumed `img` was always a bare Unsplash photo ID | Made conditional: use `img` directly if it starts with `data:`, else build the Unsplash URL as before |
| `src/features/browse/EquipmentGrid.tsx` | `item.location.split(",")[0]` crashed on missing `location` | `item.location?.split(",")[0]`, plus `.filter(Boolean)` before `.map()` so no empty chip renders |
| `src/App.tsx` (equipment detail page, `SPEC_ROWS`) | `Weekly Rate`/`Location` rows | Fallback to `"—"` when missing |
| `src/App.tsx` (equipment detail page, main + 3 thumbnail images) | Same Unsplash-URL assumption as the grid | Same `data:`-prefix conditional, ×4 |
| `src/App.tsx` (equipment detail page, Pricing section) | `weekly.toLocaleString()` crashed; savings-% line produced `NaN%` | Guarded both behind `detailItem.weekly ? ... : "—"` / `{detailItem.weekly && (...)}` |
| `src/App.tsx` (equipment detail page, Ideal For / Tags) | `.map()` on missing `idealFor`/`tags` | `(detailItem.idealFor ?? []).map(...)`, same for `tags` |

**Not yet checked**: `rating`, `reviews`, `utilization`, `revenue`, `hoursThisMonth` — these weren't hit during equipment browsing/detail testing, but the same crash pattern likely exists anywhere else in the app that reads them unguarded (e.g. `deriveAssetRecord()` in `src/app/assetRecord.ts`, used by the admin dashboard, passes these straight through from the raw equipment object via `...e`).

---

## 3. What still isn't built

Only `/api/equipment`, `/api/auth/*`, and the two empty stubs above exist on this backend. Anything needing real bookings, checkout/payment, admin asset management, or AI recommendations will `404` if exercised — expected, not a bug, until those features are built the same way equipment was.

---

## 4. How to run both sides

```bash
# Backend (this repo)
cd heavy-rental-spring-rest-api
./mvnw spring-boot:run

# Frontend (separate repo/devcontainer)
npm run dev:api    # real backend — .env.api must have VITE_API_TARGET=http://heavy-rental-rest-api:8080
# or
npm run dev:mock   # mock server, unaffected by any of the above
```

Login (real backend mode): `alex.tan@example.sg` / `customer123` (customer), `ravi.kumar@example.sg` / `admin123` (admin).
