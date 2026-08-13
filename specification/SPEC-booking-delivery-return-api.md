# Specification: Booking, Delivery & Return API

| Field | Value |
|-------|--------|
| **Feature** | REST API for viewing/updating bookings and driving the delivery/return status workflow |
| **Status** | Implemented on branch `HR-80-implement-endpoints-for-bookings-deliveries-and-returns`, not yet merged to `develop`. `returnNotes` (HR-100) implemented on top of that. Two further additions on top of that, each on its own not-yet-merged branch: all of a booking's items, not just one (HR-113); and `siteAddress` postal-code validation on `PUT /api/bookings/{id}` (HR-116), implemented on branch `HR-116-site-address-postal-code-validation`. |
| **Module** | `heavy-rental-spring-rest-api` |
| **Primary paths** | `GET/PUT /api/bookings`, `/api/bookings/{id}`; `GET /api/deliveries`, `PATCH /api/deliveries/{id}/status`; `GET /api/returns`, `PATCH /api/returns/{id}/status` |
| **Client** | Mobile (per branch author — see [`SPEC-api-index.md`](./SPEC-api-index.md) §2.2) |
| **Depends on** | [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) (`Booking`, `BookingItem`, `Asset`, `User`), [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) (access token required to call any route here) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |
| **Related code** | `controller/BookingController.java`, `controller/DeliveryController.java`, `controller/ReturnController.java`, `service/BookingService.java`, `service/DeliveryService.java`, `service/ReturnService.java`, `mapper/BookingMapper.java`, `dto/BookingResponse.java`, `dto/BookingUpdateRequest.java`, `dto/DeliveryItemResponse.java`, `dto/ReturnItemResponse.java`, `dto/BookingItemLine.java`, `dto/StatusUpdateRequest.java`, `dto/ReturnStatusUpdateRequest.java`, `repository/BookingRepository.java`, `repository/BookingItemRepository.java`, `config/RestExceptionHandler.java` (`MethodArgumentNotValidException` → `400 validation_failed`, HR-116) |

This document is the **single source of truth** for the `/api/bookings`, `/api/deliveries`, and `/api/returns` REST surface: what each route does, the booking-status state machine it enforces, and known gaps against the underlying data model. It does not restate `Booking`/`BookingItem` column-level detail — see `SPEC-entity-repository.md` for that.

When code and this document diverge, update them in the same change set.

---

## 1. Outcomes

When this feature is correct:

1. A mobile client can list every booking, fetch one by id, and update its non-status details (dates, site address, delivery notes).
2. A mobile client can see "today's deliveries" (bookings starting today that are ready to go out) and "today's returns" (bookings ending today that are ready to come back), each showing customer, site, and equipment.
3. A booking can only move `CONFIRMED → MOBILISED` via the delivery endpoint, and only `MOBILISED → COMPLETED` via the return endpoint — no other transition is reachable through this API, and status can't be set through the general booking update endpoint at all.
4. Every route requires an access-tier Bearer token (`ROLE_USER` or `ROLE_ADMIN`), consistent with the rest of the API.
5. Completing a return can optionally record a free-text return note (`returnNotes`, HR-100), kept separate from the delivery-time note so one doesn't overwrite the other.

---

## 2. Scope

### 2.1 In scope

- `GET /api/bookings` — list every booking.
- `GET /api/bookings/{bookingId}` — single booking lookup.
- `PUT /api/bookings/{bookingId}` — full-replace update of `startDate`/`endDate`/`siteAddress`/`deliveryNotes`. Cannot change `status`.
- `GET /api/deliveries` — bookings with `startDate == today` and `status IN (CONFIRMED, MOBILISED)`.
- `PATCH /api/deliveries/{bookingId}/status` — the single legal transition `CONFIRMED → MOBILISED`.
- `GET /api/returns` — bookings with `endDate == today` and `status IN (MOBILISED, COMPLETED)`. Each item now includes `returnNotes` (HR-100).
- `PATCH /api/returns/{bookingId}/status` — the single legal transition `MOBILISED → COMPLETED`, now also accepting and persisting a `returnNotes` string (HR-100).
- The full `items` list `BookingMapper` builds to represent every asset on a booking in each response (§5.3).

### 2.2 Out of scope (not built by this feature; noted here so it isn't assumed to exist)

- **Booking creation.** No `POST /api/bookings` exists. Every booking in this API is seed data (`SPEC-seed-data.md` §6.6) or created directly against the DB.
- **The rest of the status lifecycle.** Nothing in this API drives `PENDING_DEPOSIT → PENDING_CONFIRMED → CONFIRMED`, and nothing sets `CANCELLED`. Only the two transitions in §2.1 are reachable through these routes; every other `Booking.BookingStatus` value is inert as far as this API is concerned.
- **`DeliveryRecord`/`ReturnRecord` persistence** (driver, timestamp, photos, signature) — see §6.3.
- **Role- or ownership-scoped access** beyond the blanket `SecurityConfig` rule shared by every route in the API — see §6.1.
- **Payments** (`PaymentController`/`PaymentService`) — separate, pre-existing feature, not covered here.
- **Rental-plan → booking conversion** — `Booking.rentalPlan` is a real FK (some seed bookings reference one), but no endpoint performs the conversion; it's set directly in seed data.
- **Editing `returnNotes` after completion (HR-100).** It's set once, at the point of completing the return via `PATCH /api/returns/{bookingId}/status`; no endpoint updates it afterward.

---

## 3. Booking status state machine

```text
PENDING_DEPOSIT --?--> PENDING_CONFIRMED --?--> CONFIRMED --[PATCH /api/deliveries/{id}/status]--> MOBILISED --[PATCH /api/returns/{id}/status]--> COMPLETED

CANCELLED: reachable from any state in principle; no endpoint sets it today.
```

`?` = no endpoint in this codebase drives that transition today (see §2.2). The two edges this feature *does* enforce are guarded in `DeliveryService.updateStatus` / `ReturnService.updateStatus`: the booking's current status and the requested status are both checked; any other combination is rejected with `400`, not silently accepted or ignored.

---

## 4. Requirements

### Requirement 1: List and view bookings

**User story:** As a mobile client, I want to list bookings and view one in detail.

1. **GIVEN** a valid access Bearer (`ROLE_USER`/`ROLE_ADMIN`)
   **WHEN** `GET /api/bookings`
   **THEN** `200` with a `BookingResponse` array for **every** booking in the system (not filtered by caller — see §6.1).
2. **GIVEN** an existing `bookingId`
   **WHEN** `GET /api/bookings/{bookingId}`
   **THEN** `200` with that booking's `BookingResponse`.
3. **GIVEN** a `bookingId` that doesn't exist
   **WHEN** `GET /api/bookings/{bookingId}`
   **THEN** `404`.

### Requirement 2: Update booking details (not status)

**User story:** As a mobile client, I want to correct a booking's dates, site address, or delivery notes without being able to jump its status.

1. **GIVEN** an existing booking and a `BookingUpdateRequest` body
   **WHEN** `PUT /api/bookings/{bookingId}`
   **THEN** `startDate`, `endDate`, `siteAddress`, and `deliveryNotes` are all overwritten with whatever the request body contains — **including `null` for any field the body omits** (this is a full replace, not a partial merge; see §6.5) — and the response is `200` with the updated `BookingResponse`.
2. **GIVEN** the same request
   **THEN** `status` is left untouched, because `BookingUpdateRequest` has no `bookingStatus` field to send one — see §7 Key decisions for why.
3. **GIVEN** a `bookingId` that doesn't exist
   **WHEN** `PUT /api/bookings/{bookingId}`
   **THEN** `404`.
4. **GIVEN** a `siteAddress` that is blank/missing, or does not end with a 6-digit postal code (HR-116)
   **WHEN** `PUT /api/bookings/{bookingId}`
   **THEN** `400` — `{"error":"validation_failed","message":"siteAddress: <reason>"}` — before any field is written; the booking is left completely unchanged. Leading/trailing whitespace on `siteAddress` is stripped before the check runs, so `"  ...619094  "` is accepted.

### Requirement 3: Today's deliveries

**User story:** As a mobile client (driver-facing), I want to see what needs to go out today.

1. **GIVEN** bookings with `startDate == today` and `status` in `(CONFIRMED, MOBILISED)`
   **WHEN** `GET /api/deliveries`
   **THEN** `200` with one `DeliveryItemResponse` per matching booking, each carrying the customer name, site address, every asset on the booking (§5.3), delivery notes, and current status.
2. **GIVEN** no bookings match
   **THEN** `200` with an empty array (never `404`).

### Requirement 4: Advance a delivery

**User story:** As a mobile client, I want to mark a booking as picked up/mobilised, and only that.

1. **GIVEN** `booking.status == CONFIRMED` and a `StatusUpdateRequest{"bookingStatus":"MOBILISED"}` body
   **WHEN** `PATCH /api/deliveries/{bookingId}/status`
   **THEN** the booking's status becomes `MOBILISED`, `200` with the updated `DeliveryItemResponse`.
2. **GIVEN** `booking.status != CONFIRMED`, **OR** the requested status isn't `MOBILISED`
   **WHEN** `PATCH /api/deliveries/{bookingId}/status`
   **THEN** `400` — `"Invalid transition: <current> -> <requested> (only CONFIRMED -> MOBILISED is allowed here)"`. No partial/side effect occurs.
3. **GIVEN** a `bookingStatus` value that isn't a real `BookingStatus` enum constant
   **WHEN** `PATCH /api/deliveries/{bookingId}/status`
   **THEN** `400` — `"Invalid bookingStatus: <value>"`.
4. **GIVEN** a `bookingId` that doesn't exist
   **THEN** `404`.

### Requirement 5: Today's returns

Mirrors Requirement 3: `GET /api/returns` — bookings with `endDate == today` and `status` in `(MOBILISED, COMPLETED)`, each mapped to a `ReturnItemResponse`. Since HR-100, that response also carries `returnNotes` (empty string until a return has been completed with one recorded).

### Requirement 6: Advance a return

**User story:** As a mobile client, I want to mark a booking as returned, optionally recording a return note, and only advance that one transition.

1. **GIVEN** `booking.status == MOBILISED` and a `ReturnStatusUpdateRequest{"bookingStatus":"COMPLETED","returnNotes":"<text>"}` body (HR-100)
   **WHEN** `PATCH /api/returns/{bookingId}/status`
   **THEN** the booking's status becomes `COMPLETED`, `returnNotes` is persisted to `Booking.returnNotes`, and `200` returns the updated `ReturnItemResponse` with both fields reflected.
2. **GIVEN** `booking.status != MOBILISED`, **OR** the requested status isn't `COMPLETED`
   **WHEN** `PATCH /api/returns/{bookingId}/status`
   **THEN** `400` — `"Invalid transition: <current> -> <requested> (only MOBILISED -> COMPLETED is allowed here)"`. Neither the status nor `returnNotes` is written in this case — a rejected transition leaves the booking completely unchanged, not partially applied.
3. **GIVEN** a `bookingStatus` value that isn't a real `BookingStatus` enum constant
   **WHEN** `PATCH /api/returns/{bookingId}/status`
   **THEN** `400` — `"Invalid bookingStatus: <value>"`.
4. **GIVEN** a `bookingId` that doesn't exist
   **THEN** `404`.
5. **GIVEN** a valid transition with `returnNotes` omitted or blank
   **WHEN** `PATCH /api/returns/{bookingId}/status`
   **THEN** the transition still succeeds — `returnNotes` has no validation constraint requiring non-blank content; an empty string is a legitimate value.

---

## 5. Design

### 5.1 Components

| Concern | Location |
|---|---|
| HTTP | `controller/BookingController.java`, `controller/DeliveryController.java`, `controller/ReturnController.java` |
| Orchestration, transition guards | `service/BookingService.java`, `service/DeliveryService.java`, `service/ReturnService.java` |
| Status parsing shared by all three services | `BookingService.parseStatusOr400(String)` (package-private static, called from `DeliveryService`/`ReturnService`) |
| Entity → DTO mapping | `mapper/BookingMapper.java` |
| DTOs | `dto/BookingResponse`, `dto/BookingUpdateRequest`, `dto/DeliveryItemResponse`, `dto/ReturnItemResponse`, `dto/StatusUpdateRequest`, `dto/ReturnStatusUpdateRequest` (HR-100 — separate from `StatusUpdateRequest`; carries `returnNotes` alongside `bookingStatus`, used only by the return status endpoint, deliveries continue to use the shared `StatusUpdateRequest`) |
| Data access | `repository/BookingRepository.java` (`findByStartDateAndStatusIn`, `findByEndDateAndStatusIn`, plus standard CRUD), `repository/BookingItemRepository.java` (`findByBookingId`) |
| Security | `config/SecurityConfig.java` — same blanket `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` rule as every other business route; no route-specific matcher exists for any path in this spec |

### 5.2 API contracts

#### `GET /api/bookings` / `GET /api/bookings/{bookingId}`

```json
{
  "bookingId": 1,
  "customerName": "Alex Tan",
  "startDate": "2026-08-09",
  "endDate": "2026-08-13",
  "bookingStatus": "CONFIRMED",
  "siteAddress": "20 Jurong Port Road, Singapore 619094",
  "items": [
    { "assetName": "JLG 460SJ Boom Lift", "serialNumber": "SN-BML-000460" },
    { "assetName": "Toyota 8FD25 Forklift", "serialNumber": "SN-FKL-008FD25" }
  ],
  "deliveryNotes": ""
}
```

`GET /api/bookings` returns an array of the above; the single-booking route returns one object. `404` body follows the shared error shape (§5.4). Note: `BookingResponse` does not carry `returnNotes` — that field only exists on `ReturnItemResponse` (below), since only the return workflow ever sets it. `items` (HR-113) is booking id `1`'s real seed data — two `BookingItem` rows — shown here specifically to demonstrate that both now come back; see §5.3.

#### `PUT /api/bookings/{bookingId}`

```http
PUT /api/bookings/3 HTTP/1.1
Authorization: Bearer <access-jwt>
Content-Type: application/json

{
  "startDate": "2026-08-18",
  "endDate": "2026-08-21",
  "siteAddress": "15 Pioneer Sector 1, Singapore 628413",
  "deliveryNotes": "Access via loading bay B, coordinate with site security"
}
```

**DTO:** `BookingUpdateRequest(startDate, endDate, siteAddress, deliveryNotes)` — no `bookingStatus` field exists on this type; it cannot be sent. `200` — updated `BookingResponse`. Every field in the request body is written to the entity unconditionally (see §6.5): a client that wants to change only `deliveryNotes` must still resend the current `startDate`/`endDate`/`siteAddress`, or those fields will be nulled.

**`siteAddress` validation (HR-116):** the controller method is now `@Valid`-annotated, and `siteAddress` on `BookingUpdateRequest` carries `@NotBlank` plus `@Pattern(regexp = "^.*\\d{6}$")` — the value must end with a 6-digit postal code (e.g. `"20 Jurong Port Road, 619094"`). A compact canonical constructor strips leading/trailing whitespace before either constraint is evaluated. A violation short-circuits before `BookingService.updateBooking` runs at all — see Requirement 2.4 and §5.4 for the resulting `400`. `startDate`/`endDate`/`deliveryNotes` carry no such constraint and remain fully nullable, per §6.5.

#### `GET /api/deliveries`

```json
[
  {
    "bookingId": 1,
    "customerName": "Alex Tan",
    "startDate": "2026-08-09",
    "siteAddress": "20 Jurong Port Road, Singapore 619094",
    "items": [
      { "assetName": "JLG 460SJ Boom Lift", "serialNumber": "SN-BML-000460" },
      { "assetName": "Toyota 8FD25 Forklift", "serialNumber": "SN-FKL-008FD25" }
    ],
    "deliveryNotes": "",
    "bookingStatus": "CONFIRMED"
  }
]
```

#### `PATCH /api/deliveries/{bookingId}/status`

```http
PATCH /api/deliveries/1/status HTTP/1.1
Authorization: Bearer <access-jwt>
Content-Type: application/json

{ "bookingStatus": "MOBILISED" }
```

**DTO:** `StatusUpdateRequest(bookingStatus: String)`. `200` — updated `DeliveryItemResponse`. `400` on any transition other than `CONFIRMED → MOBILISED` (§4, Requirement 4.2) or an unparseable status (4.3). `404` if the booking doesn't exist.

#### `GET /api/returns`

```json
[
  {
    "bookingId": 4,
    "customerName": "Alex Tan",
    "endDate": "2026-08-12",
    "siteAddress": "88 Tuas South Ave 3, Singapore 637311",
    "items": [
      { "assetName": "JLG 460SJ Boom Lift", "serialNumber": "SN-BML-000460" }
    ],
    "deliveryNotes": "Crane assist required for offload",
    "returnNotes": "",
    "bookingStatus": "MOBILISED"
  }
]
```

`returnNotes` (HR-100) sits alongside `deliveryNotes` — the delivery-time note is kept for context and is never overwritten by the return-time one. Empty string, not `null`, until a return note has actually been recorded. Booking id `4` has only one `BookingItem` row, so `items` has one entry here — see the `GET /api/bookings`/`GET /api/deliveries` examples above for the multi-item case (booking id `1`).

#### `PATCH /api/returns/{bookingId}/status`

```http
PATCH /api/returns/4/status HTTP/1.1
Authorization: Bearer <access-jwt>
Content-Type: application/json

{ "bookingStatus": "COMPLETED", "returnNotes": "Returned in good condition" }
```

**DTO (HR-100):** `ReturnStatusUpdateRequest(bookingStatus: String, returnNotes: String)` — a **separate schema from deliveries' `StatusUpdateRequest`**, not that type plus an extra field. Deliveries has no use for a notes field, so sharing the type would mean it silently accepts one it ignores. `200` — updated `ReturnItemResponse`, including the now-persisted `returnNotes`. `400` on any transition other than `MOBILISED → COMPLETED` or an unparseable status — on rejection, `returnNotes` is **not** persisted either, consistent with the status itself being left unchanged (see Requirement 6). `404` if the booking doesn't exist.

#### 5.4 Shared errors

```json
{ "error": "<code>", "message": "<reason>" }
```

| HTTP | Typical `error` |
|------|-----------------|
| `400` | `bad_request` (invalid transition, unparseable status) |
| `400` | `validation_failed` (`PUT /api/bookings/{id}` only — `siteAddress` blank or missing its 6-digit postal-code suffix; HR-116, handled by `RestExceptionHandler.handleValidation`) |
| `401` | `unauthorized` (missing/invalid Bearer) |
| `404` | `not_found` |

### 5.3 Item list mapping (HR-113)

Every response DTO in this spec (`BookingResponse`, `DeliveryItemResponse`, `ReturnItemResponse`) carries a booking's **full** set of line items as `items: List<BookingItemLine>`, where `BookingItemLine(assetName, serialNumber)` (`dto/BookingItemLine.java`) is one entry per `BookingItem` row. `BookingMapper.toItemLines(List<BookingItem>)` builds the list by sorting the booking's items by `BookingItem.id` ascending (deterministic order, no display-order column exists) and mapping each to its asset's name/serial, guarding a null `BookingItem.asset` the same way the mapper always has (empty strings, not a `NullPointerException`). A booking with no `BookingItem` rows maps to `items: []` — not `null`, not omitted.

**Previously (fixed HR-113): primary-asset selection dropped every item but one.** Before this change, `BookingMapper.primaryAsset(List<BookingItem>)` picked a single `BookingItem` via `items.stream().min(Comparator.comparing(BookingItem::getId))` — the *first-created* row, not any semantically "primary" item — and every response DTO carried one flat `assetName`/`serialNumber` pair. Seed booking id `1` has two `BookingItem` rows (JLG 460SJ Boom Lift, Toyota 8FD25 Forklift); every response shape that included that booking used to show the boom lift only. Fixed: see §6.2. Unaffected by HR-100 — `returnNotes` is a scalar `Booking` column, not asset-scoped. The mobile client's `HR-113` branch already expects the `items` shape in §5.2; this change makes the backend match it.

---

## 6. Known issues / gaps

Carried over and expanded from the PR review recorded in `SPEC-api-index.md` §5 (that section now points here as the primary write-up; keep both in sync per this doc's own convention). None of these are fixed as of this version, except 6.2 — resolved by HR-113 (see below).

### 6.1 No role or ownership checks

No `@PreAuthorize`, `@Secured`, or principal/ownership comparison exists in any controller or service in §5.1 — grepped, zero matches. Authorization is entirely the blanket `SecurityConfig` rule: any `ROLE_USER` or `ROLE_ADMIN` token can call every route in this spec against **any** booking, not just one belonging to the caller. `ROLE_DRIVER` — the role `DeliveryRecord`/`ReturnRecord` exist for — cannot call any of these routes at all (excluded from `SecurityConfig`'s blanket rule; see `SPEC-api-index.md` §4).

**Recommended fix (not applied):** a role check on the delivery/return status routes (`ROLE_ADMIN`/`ROLE_DRIVER`, once `ROLE_DRIVER` is let in — see `SPEC-api-index.md` §4), and an ownership check on `PUT /api/bookings/{id}` (`booking.customer.id == principal`).

### 6.2 Multi-asset bookings lose items in every response

See §5.3. Was reproducible against seed booking id `1` in `GET /api/deliveries` (and every other route in this spec).

**Status: Fixed (HR-113).** `assetName`/`serialNumber` on `BookingResponse`/`DeliveryItemResponse`/`ReturnItemResponse` replaced with `items: List<BookingItemLine>`; `BookingMapper.toItemLines` now maps every `BookingItem` row instead of picking one (§5.3). Verified against seed booking id `1`: `GET /api/bookings/1` and `GET /api/deliveries` both now return both items (JLG 460SJ Boom Lift, Toyota 8FD25 Forklift), sorted by `BookingItem.id`. This matches the shape the mobile client's `HR-113` branch already expects, so both sides are now aligned.

### 6.3 `DeliveryRecord`/`ReturnRecord` never created

`DeliveryController`/`ReturnController` flip `Booking.status` only; `DeliveryRecordRepository`/`ReturnRecordRepository` are never referenced by this feature. No driver, timestamp, photo, or signature is ever recorded, despite `DeliveryRecord`/`ReturnRecord` (`SPEC-entity-repository.md` §5.10–5.11) existing specifically for that purpose.

**Status:** scope, not a bug — the status-transition APIs work correctly as far as they go. Suitable as its own follow-up ticket ("persist delivery/return proof records") rather than a fix to this branch.

### 6.4 N+1 queries on the three list/lookup paths

`BookingService.getBookings()`, `DeliveryService.getTodaysDeliveries()`, and `ReturnService.getTodaysReturns()` each run one query to list bookings, then one `bookingItemRepository.findByBookingId(...)` call per booking during mapping, plus a lazy load per distinct `Asset` (`BookingItem.getAsset()`) and per distinct `Booking.customer` the first time each is touched (both `@ManyToOne(FetchType.LAZY)`). Negligible at current seed-data volume (single-digit bookings).

**Status:** fine to defer to an opportunistic fix or a dedicated perf pass.

### 6.5 `PUT /api/bookings/{id}` is a full replace, not a partial merge

`BookingService.updateBooking` calls all four setters unconditionally from the request DTO with no null-check:

```java
booking.setStartDate(request.startDate());
booking.setEndDate(request.endDate());
booking.setSiteAddress(request.siteAddress());
booking.setDeliveryNotes(request.deliveryNotes());
```

A client that omits (or sends `null` for) any of `startDate`/`endDate`/`siteAddress`/`deliveryNotes` will overwrite that field to `null` in the database, not leave it unchanged. This is documented as current behavior, not flagged as a bug — full-replace `PUT` semantics are a legitimate, common design choice — but it's easy for a client author to assume partial-update (`PATCH`-like) behavior from a `PUT`, so it's called out explicitly here. Unaffected by HR-100 — this route doesn't touch `returnNotes`.

**Status:** as-built behavior; revisit only if a client actually needs partial updates (at which point the fix is either a `PATCH` variant or null-coalescing in the service).

---

## 7. Key decisions

| Decision | Rationale |
|----------|-----------|
| `BookingUpdateRequest` has no `bookingStatus` field | The route originally accepted a full `BookingResponse` as its request body, so any client could set an arbitrary `bookingStatus` directly via `PUT` — bypassing the transition guards below entirely. Fixed (commit `c06b2ea`, "Restrict `PUT /api/bookings/{id}` to booking details, remove status field") by introducing a narrower request DTO that structurally cannot carry a status. This is the same category of bug the delivery/return transition guards below exist to prevent, just via the response-DTO-as-request-DTO route instead. |
| Status changes only via two single-hop, guarded endpoints | `DeliveryService.updateStatus`/`ReturnService.updateStatus` each check *both* the booking's current status and the requested one before writing, rejecting anything else with `400` — makes the delivery/return workflow the only path that can advance a booking's lifecycle through this API, and makes each step auditable to exactly one precondition. |
| `GET /api/deliveries`/`GET /api/returns` are "today" queries, not "all open" queries | `findByStartDateAndStatusIn`/`findByEndDateAndStatusIn` filter to `LocalDate.now()` — matches a driver's daily worklist use case rather than a general booking browser (that's what `GET /api/bookings` is for). |
| Full item list via `toItemLines`, sorted by `BookingItem.id` ascending (HR-113) | Supersedes the earlier `primaryAsset`/`min(BookingItem.id)` single-asset selection, which was incomplete for multi-item bookings (§6.2, now fixed). Sorting by id keeps ordering deterministic without inventing a display-order concept the schema doesn't have. |
| `returnNotes` is a dedicated request/response field, not folded into `StatusUpdateRequest` (HR-100) | Deliveries has no use for a notes field. Adding it to the shared `StatusUpdateRequest` would mean the delivery endpoint silently accepts and ignores a field that only makes sense for returns — a `ReturnStatusUpdateRequest` DTO keeps each endpoint's contract limited to what it actually uses. |
| `returnNotes` kept separate from `deliveryNotes`, not overwriting it (HR-100) | The delivery-time note (e.g. access instructions) remains useful context through the return step; folding a return-time note into the same field would destroy it. Both are shown together on the client. |
| `returnNotes` only persisted on a successful transition (HR-100) | Matches the existing guard pattern for the status itself — a rejected `PATCH` (invalid transition, unparseable status) leaves the booking entirely unchanged, not partially applied. |
| No automated tests for this feature | Consistent with `SPEC-tests.md`, which only covers `AuthenticationIntegrationTest` and the context-load smoke test today; this feature's state-machine and (once added) authorization logic have no test coverage yet. Recorded as current state, not argued for or against here. |

---

## 8. Verification

### 8.1 Checklist

- [ ] No Bearer → `401` on every route in this spec.
- [ ] `GET /api/bookings` → `200`, array covering all seeded bookings.
- [ ] `GET /api/bookings/{id}` on a real id → `200`; on a fake id → `404`.
- [ ] `PUT /api/bookings/{id}` with all four fields → `200`, values updated; omitting a field → that field nulled (§6.5), not preserved.
- [ ] `PUT /api/bookings/{id}` with `siteAddress` blank, missing, or not ending in a 6-digit postal code → `400 validation_failed`, booking left unchanged (HR-116).
- [ ] `PUT /api/bookings/{id}` with `siteAddress` padded with leading/trailing whitespace but otherwise valid → `200`, stored value stripped (HR-116).
- [ ] `GET /api/deliveries` → only bookings with `startDate == today` and status `CONFIRMED`/`MOBILISED`.
- [ ] `PATCH /api/deliveries/{id}/status` on a `CONFIRMED` booking with `{"bookingStatus":"MOBILISED"}` → `200`, status now `MOBILISED`.
- [ ] Same call on a non-`CONFIRMED` booking, or with any status other than `MOBILISED` → `400`.
- [ ] `GET /api/returns` / `PATCH /api/returns/{id}/status` → mirrored checks against `MOBILISED → COMPLETED`.
- [ ] `PATCH /api/returns/{id}/status` with `returnNotes` set → `200`, response reflects it, and it's actually persisted (re-`GET /api/returns` shows the same value) — not just echoed back (HR-100).
- [ ] Same call on an invalid transition → `400`, and `returnNotes` is **not** persisted either (HR-100).
- [ ] Seed booking id `1` (two `BookingItem`s) → confirm `GET /api/bookings/1` and `GET /api/deliveries` return **both** items under `items`, sorted by `BookingItem.id` (§5.3/§6.2, HR-113) — a real pass/fail gate now that the fix is in.

### 8.2 Manual smoke (curl)

```bash
INTERIM=$(curl -s http://localhost:8080/api/auth/getBearerToken)
ACCESS=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Authorization: Bearer $INTERIM" -H "Content-Type: application/json" \
  -d '{"email":"alex.tan@example.sg","password":"customer123"}' | jq -r .accessToken)

curl -s http://localhost:8080/api/bookings -H "Authorization: Bearer $ACCESS" | jq .
curl -s http://localhost:8080/api/deliveries -H "Authorization: Bearer $ACCESS" | jq .

# booking 1 is seeded CONFIRMED, start_date = today
curl -s -X PATCH http://localhost:8080/api/deliveries/1/status \
  -H "Authorization: Bearer $ACCESS" -H "Content-Type: application/json" \
  -d '{"bookingStatus":"MOBILISED"}' | jq .

# now booking 1 is MOBILISED; a second call with the same body should 400
curl -i -X PATCH http://localhost:8080/api/deliveries/1/status \
  -H "Authorization: Bearer $ACCESS" -H "Content-Type: application/json" \
  -d '{"bookingStatus":"MOBILISED"}'

# HR-100: complete a return with a note, then confirm it persisted
curl -s -X PATCH http://localhost:8080/api/returns/4/status \
  -H "Authorization: Bearer $ACCESS" -H "Content-Type: application/json" \
  -d '{"bookingStatus":"COMPLETED","returnNotes":"Returned in good condition"}' | jq .

curl -s http://localhost:8080/api/returns -H "Authorization: Bearer $ACCESS" | jq .
```

---

## 9. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.0.0 | 2026-08-09 | Initial as-built spec: booking read/update, today's-deliveries/returns lists, guarded `CONFIRMED→MOBILISED`/`MOBILISED→COMPLETED` transitions, primary-asset selection, and the known-issues list from PR review (role/ownership checks, multi-asset data loss, missing `DeliveryRecord`/`ReturnRecord` persistence, N+1 queries, full-replace `PUT` semantics). Written per the standalone-spec criterion added to `SPEC-project-environment.md` §9.1: this feature has independently-evolving business logic (a state machine, its own future authz needs) that warrants its own file rather than living in `SPEC-entity-repository.md`/`SPEC-api-index.md`. |
| 1.1.0 | 2026-08-12 | HR-100: `PATCH /api/returns/{bookingId}/status` now accepts a dedicated `ReturnStatusUpdateRequest(bookingStatus, returnNotes)` body instead of the shared `StatusUpdateRequest`, and persists `returnNotes` to a new `Booking.returnNotes` column (`SPEC-entity-repository.md` §5.7) only on the valid `MOBILISED → COMPLETED` transition. `ReturnItemResponse` gained a `returnNotes` field alongside the existing `deliveryNotes`; the two are kept separate rather than one overwriting the other. Deliveries' contract (`StatusUpdateRequest`, `DeliveryItemResponse`) is unchanged. |
| 1.2.0 | 2026-08-12 | **HR-113: multi-asset bookings fixed — closes §6.2.** `BookingResponse`, `DeliveryItemResponse`, `ReturnItemResponse` now carry `items: List<BookingItemLine>` (new `dto/BookingItemLine.java` — `assetName`/`serialNumber` per row) instead of one flat `assetName`/`serialNumber` pair. `BookingMapper.primaryAsset()` (picked one `BookingItem` via `min(BookingItem.id)`, discarding the rest) replaced by public `BookingMapper.toItemLines(List<BookingItem>)`, which maps every `BookingItem` row, sorted by id ascending; an empty input maps to `items: []`, never `null`. Matches the shape the mobile client's `HR-113` branch already expects. `BookingMapperTest` gained zero/single/multi-item coverage (previously only the zero-item path was exercised). §5.2 examples, §5.3 (renamed from "Primary-asset selection" to "Item list mapping"), §6.2, §7, and §8.1 updated to match. |
| 1.3.0 | 2026-08-13 | **HR-116: `siteAddress` postal-code validation on `PUT /api/bookings/{id}`.** (Renumbered from a colliding `1.2.0` assigned independently on this branch.) `BookingController.updateBooking` is now `@Valid`-annotated; `BookingUpdateRequest.siteAddress` carries `@NotBlank` + `@Pattern(regexp = "^.*\\d{6}$")` (must end with a 6-digit postal code) and is stripped of leading/trailing whitespace in a compact canonical constructor. A new `RestExceptionHandler.handleValidation(MethodArgumentNotValidException)` maps any violation to `400 {"error":"validation_failed", ...}` — new row in §5.4 — before `BookingService.updateBooking` runs, so a rejected request leaves the booking completely unchanged. New Maven dependency `spring-boot-starter-validation` (`pom.xml`). `startDate`/`endDate`/`deliveryNotes` are unaffected — still fully nullable, per §6.5. `POST /api/bookings` (`CreateBookingRequest`) got the identical constraint in the same change; that route's contract lives in `SPEC-api-index.md` §2.2.1, not here — see that file's own change control for the mirrored entry. |