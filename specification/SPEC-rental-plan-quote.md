# Specification: Rental Plan — Build & Quote

| Field | Value |
|-------|--------|
| **Feature** | Rental Plan build-and-quote flow (Jira `HR-19` "Request Quote") |
| **Status** | Implemented on `hr-19-request-quote`, not yet merged to `develop`. REQ-1 through REQ-5 all built and manually verified end-to-end against seeded data — full §6.1 checklist confirmed 2026-08-11. PR review changes applied 2026-08-11: route renamed to `/api/rentalPlans`, `QUOTEED` typo fixed to `QUOTED`, `@Version` double-submit guard added, pricing extracted behind `PricingClient` — see 1.1.0/1.2.0. `origin/develop`'s `HR-92` reseed merged in and the whole branch re-verified live end-to-end afterward (create → add item → quote, as a fresh customer) — see 1.2.1. `siteAddress` postal-code validation added on branch `HR-116-site-address-postal-code-validation` (also on top of `develop`, not yet merged, not yet re-verified live) — see 1.3.0. **Checkout conversion (`HR-129`) added 2026-08-13** — a `QUOTED` plan can now be converted into a `Booking`, and post-quote item mutation no longer locks the plan — see REQ-2/REQ-3/REQ-6 and 1.4.0. |
| **Module** | `heavy-rental-spring-rest-api` |
| **Primary surface** | REST API for a customer building a Rental Plan (equipment line items + dates) into a priced quote, and converting that quote into a `Booking` at checkout |
| **Method** | Specification Driven Design (SDD) |
| **Related code** | `controller/RentalPlanController.java`, `service/RentalPlanService.java`, `service/PricingClient.java`, `service/DefaultPricingClient.java`, `service/BookingService.java` (`createFromRentalPlan`, HR-129), `service/RentalPlanConflictException.java` (HR-129), `entity/RentalPlan.java`, `entity/RentalPlanRecord.java`, `repository/RentalPlanRepository.java`, `repository/RentalPlanRecordRepository.java`, `dto/RentalPlanCreateRequest.java`, `dto/RentalPlanResponse.java` (`updatedAt`/`createdAt`, HR-129), `config/RestExceptionHandler.java` (double-submit → `409`; `siteAddress` validation → `400`, HR-116; `quote_not_ready`/`quote_expired` → `409`, HR-129) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |
| **Frontend contract** | [`features/rental-plan-checkout-conversion/api-contract-for-frontend.md`](./features/rental-plan-checkout-conversion/api-contract-for-frontend.md) |

This document is the **single source of truth** for the `/api/rentalPlans` REST surface, including quoting and checkout conversion into a `Booking` (REQ-6, HR-129). It does not cover agreement drafting/e-signature (`HR-21`), which remains separate, downstream work.

---

## 1. Outcomes

When this feature is correct:

1. A customer can start a new rental plan with a date range and site address (REQ-1).
2. Each customer can have only one **active** rental plan (`DRAFT`/`SAVED`/`QUOTED`) at a time, with past/converted plans kept on record (BR-06).
3. A customer can add and remove equipment line items on an active plan, each snapshotting the asset's base daily rate at add-time. Mutating items after the plan has been quoted invalidates that quote rather than being blocked (REQ-2, REQ-3).
4. A customer can request a quote, which computes and freezes the plan's total (REQ-4).
5. A customer can only ever see or modify their **own** rental plans (REQ-5).
6. A `QUOTED` plan, quoted within the last 24 hours, converts into a `Booking` at checkout — items, dates, and price all come from the plan's own persisted records, never recomputed or re-submitted (REQ-6).

---

## 2. Scope

### 2.1 In scope

- Creating a rental plan, with BR-06 (one active plan per customer) enforced at creation time.
- Adding and removing equipment line items on an active (`DRAFT`/`SAVED`/`QUOTED`) plan, each snapshotting `Asset.baseDailyRate`. Mutating a `QUOTED` plan's items reverts it to `DRAFT` (REQ-2, REQ-3).
- Requesting a quote: computing `totalAmount` from all line items.
- Listing and retrieving a customer's own plans — ownership-scoped, never another customer's.
- Converting a `QUOTED`, not-yet-stale plan into a `Booking` at checkout (REQ-6, HR-129) — ownership, status, and 24-hour quote-validity checks; items/dates/price derived from the plan, not the request body; plan status becomes `CONVERTED`.

### 2.2 Out of scope

- `HR-20` "Rental Quote Discount Recommendation" — no discount field or logic in this spec.
- `HR-21` "Auto-draft rental agreement for e-signature" — agreement drafting and e-signature remain separate, downstream work. (The `CONVERTED` status transition itself is now in scope — see REQ-6.)
- Dynamic/ML-based pricing — blocked on the separate `haystack-fast-api` service, confirmed not ready (same blocker as the admin Pricing tab's rate recommendation). `Asset.baseDailyRate` is used instead — see Open Question 2.
- Line-item quantity — `RentalPlanRecord` has no quantity column; renting 2 units of one asset means 2 separate line-item rows. Not redesigning the entity here.
- BR-04/BR-05 (30% deposit, full payment 2 days before delivery) — checkout-time concerns that apply once a plan becomes a `Booking`, not part of quoting itself.
- **Availability holds.** Quoting/adding an item to a `RentalPlan` never blocks that equipment's availability for other customers — `Booking` (with `PENDING_DEPOSIT`/`PENDING_CONFIRMED`/`CONFIRMED`/`MOBILISED` status) remains the sole source of availability truth, per existing precedent (`SPEC-equipment-browse-api.md` §"blocking" note) and `AssetService.resolveAvailabilityWindow`, which has no awareness of `RentalPlan` at all. Two customers can quote the same equipment for overlapping dates; whichever converts to a real `Booking` first wins — **as of REQ-6 (HR-129), that conflict is now actually resolved at conversion time**: checkout re-runs the same availability check `POST /api/bookings`'s direct-booking path already used (`BookingItemRepository.findAssetIdsWithOverlappingBooking`), so the second customer to convert gets a `409` naming the conflicting asset(s) instead of silently double-booking.

---

## 3. Requirements

### REQ-1: Create a rental plan (BR-06)

**GIVEN** an authenticated customer with no plan in `DRAFT`/`SAVED`/`QUOTED`
**WHEN** they `POST /api/rentalPlans` with `startDate`, `endDate`, `siteAddress`
**THEN** a new `RentalPlan` is created with `status = DRAFT` and returned.

**GIVEN** a customer who already has an active plan (`DRAFT`/`SAVED`/`QUOTED`)
**WHEN** they `POST /api/rentalPlans` again
**THEN** the request is rejected with `409 Conflict`.

**GIVEN** a `siteAddress` that is blank/missing, or does not end with a 6-digit postal code (HR-116)
**WHEN** `POST /api/rentalPlans`
**THEN** the request is rejected with `400 {"error":"validation_failed", ...}` before the BR-06 active-plan check or persistence run — no `RentalPlan` row is created. Leading/trailing whitespace on `siteAddress` is stripped before the check, so `"  ...619094  "` is accepted.

### REQ-2: Add a line item

**GIVEN** a `DRAFT`/`SAVED` plan owned by the caller
**WHEN** they `POST /api/rentalPlans/{id}/items` with a valid `assetId`
**THEN** a `RentalPlanRecord` is created with `dailyRate = asset.baseDailyRate` and `subtotal = dailyRate × days in the plan's date range`.

**GIVEN** a `QUOTED` plan
**WHEN** an item add is attempted
**THEN** the item is added (not rejected), and the plan reverts: `status → DRAFT`, `totalAmount → null`, `updatedAt` refreshed. **Changed in HR-129** — previously rejected with `409` ("items are locked once quoted"); that lock is gone because a `QUOTED` plan's frozen `totalAmount` only reflects the item set at the moment it was quoted, and leaving a `QUOTED` plan with a stale total once its items change is worse than requiring a fresh quote before checkout.

### REQ-3: Remove a line item

**GIVEN** a `DRAFT`/`SAVED` plan owned by the caller
**WHEN** they `DELETE /api/rentalPlans/{id}/items/{itemId}`
**THEN** the line item is removed.

**GIVEN** a `QUOTED` plan
**WHEN** an item remove is attempted
**THEN** same revert-to-`DRAFT` behavior as REQ-2 (**changed in HR-129**, same rationale).

**GIVEN** a plan not owned by the caller
**WHEN** any operation is attempted on it (view, add, remove, quote, checkout)
**THEN** the request returns `404` — not `403`, so a customer can't even confirm another customer's plan exists.

### REQ-4: Request a quote

**GIVEN** a `DRAFT`/`SAVED`/`QUOTED` plan owned by the caller with at least one line item
**WHEN** they `POST /api/rentalPlans/{id}/quote`
**THEN** `totalAmount` is set to the sum of all line items' subtotals, `status` moves to (or stays) `QUOTED`, and `updatedAt` is refreshed to now (**HR-129** — this timestamp is what REQ-6's 24-hour validity window is computed from).

**GIVEN** an already-`QUOTED` plan whose quote has gone stale (>24h, REQ-6)
**WHEN** they `POST /api/rentalPlans/{id}/quote` again
**THEN** it succeeds and refreshes `updatedAt` — **this is the intended recovery path for REQ-6's `quote_expired` response.** (**Changed in HR-129, found live during verification**: quoting originally only accepted `DRAFT`/`SAVED`, rejecting an already-`QUOTED` plan with `409 "Plan is already quoted"` regardless of staleness — which meant a customer whose quote expired had no way to refresh it. Only a `CONVERTED` plan is now rejected, with `409 "Plan has already been converted to a booking"`.)

**GIVEN** a plan with zero line items
**WHEN** a quote is requested
**THEN** the request is rejected with `400`.

### REQ-5: List and get, ownership-scoped

**GIVEN** an authenticated customer
**WHEN** they `GET /api/rentalPlans`
**THEN** only their own plans are returned — never another customer's. Each plan's JSON now also includes `updatedAt`/`createdAt` (**HR-129**, ISO-8601 strings, Spring Boot's default `LocalDateTime` serialization).

### REQ-6: Convert a quoted plan into a Booking (checkout) — added HR-129

**GIVEN** a `QUOTED` plan owned by the caller, quoted (i.e. `updatedAt`) within the last 24 hours
**WHEN** they `POST /api/bookings` with `rentalPlanId` set to that plan's id
**THEN** a `Booking` is created with items, dates, and `totalAmount` derived from the plan's own `RentalPlanRecord`s and frozen `totalAmount` — never recomputed and never taken from the request body's `items`/`startDate`/`endDate`, which are ignored when `rentalPlanId` is present. The new `Booking`'s `status` is `PENDING_DEPOSIT`, and the plan's `status` becomes `CONVERTED` in the same transaction — freeing the customer to start a new plan immediately (BR-06 no longer treats a `CONVERTED` plan as active).

**GIVEN** availability has changed since quoting (no hold is placed at quote time — see the "Availability holds" note in §2.2)
**WHEN** checkout is attempted
**THEN** the same overlap check `POST /api/bookings`'s direct-booking path uses runs against the plan's assets and dates; a conflict returns `409` naming the asset(s), and neither the `Booking` nor the plan's status change is persisted.

**GIVEN** a plan not owned by the caller
**WHEN** checkout is attempted with its id
**THEN** `404` — same convention as REQ-3.

**GIVEN** a plan whose `status != QUOTED` (still `DRAFT`/`SAVED`, or already `CONVERTED`)
**WHEN** checkout is attempted
**THEN** `409 {"error":"quote_not_ready", ...}`.

**GIVEN** a `QUOTED` plan whose `updatedAt` is more than 24 hours old
**WHEN** checkout is attempted
**THEN** `409 {"error":"quote_expired", ...}` — the customer must `POST /api/rentalPlans/{id}/quote` again (refreshing `updatedAt`, per REQ-4) before retrying checkout. These two `409` conditions use dedicated error codes (`RentalPlanConflictException`) rather than the generic `"conflict"` code every other `409` in this codebase shares, specifically so a frontend caller can distinguish them without parsing the `message` string.

---

## 4. Open questions (need your decision before implementation)

All resolved during spec drafting — kept here for traceability, matching this project's convention of tracking exactly how each open question was resolved:

1. **BR-06 scope**: does "one rental plan per customer" mean one plan ever, for the account's entire history, or only one **active** plan at a time? **Resolved:** one active (`DRAFT`/`SAVED`/`QUOTED`) plan at a time — past/converted plans stay on record.
2. **Daily-rate source**: `Asset` has `baseDailyRate`, `minDailyRate`, and `maxDailyRate` — which is snapshotted into `RentalPlanRecord.dailyRate` when a line item is added? **Resolved:** `Asset.baseDailyRate`. Dynamic/ML-recommended pricing (the `haystack-fast-api` service) isn't ready, so it isn't used here.
3. **Availability hold on quoting**: should quoting/adding an item place a hold on the equipment's availability for other customers? **Resolved:** no hold — matches existing precedent (`Booking` status alone gates availability). See §7 for the concern this leaves open.

---

## 5. Design

Left intentionally high-level, per this project's convention of keeping the contract separate from implementation detail:

- New `RentalPlanService`, replacing the current `RentalPlanController` stub (`controller/RentalPlanController.java`, today just `GET → []`).
- New request/response records: `RentalPlanResponse`, `RentalPlanItemResponse`, `RentalPlanCreateRequest`, `RentalPlanItemRequest` — following this codebase's existing DTO-as-record convention (see `EquipmentResponse.java`).
- No new entities — `RentalPlan`, `RentalPlanRecord`, and both their repositories already exist and are unused today (`RentalPlanRepository.findByCustomerId`/`findByStatus` and `RentalPlanRecordRepository.findByRentalPlanId` already cover what REQ-1 and REQ-5 need). One column was added after the fact — see the `@Version` note below.
- Day-count math has no existing convention in this codebase to match (checked every service — no `ChronoUnit`/`Period` usage anywhere); plan is `ChronoUnit.DAYS.between(startDate, endDate) + 1` (inclusive of both start and end day) — worth confirming once implementation starts.
- Needs a real ownership check (`plan.customer.id == principal.id`) — no other route in this codebase does this yet (`SPEC-booking-delivery-return-api.md` §6.1 flags the same gap as unfixed on `Booking`); this spec deliberately builds it in rather than repeating that gap.
- **Double-submit guard (added post-review):** `RentalPlan` now has a `@Version` (optimistic locking) column. A double-submit — e.g. a customer double-clicking "Request Quote," firing two concurrent `POST .../quote` calls that both read the same pre-quote row before either commits — now fails the losing write with a `409 Conflict` (`"This record was updated by another request — please retry"`, handled in `RestExceptionHandler`) instead of silently racing.
- **Migration note — `ddl-auto=update` alone is NOT enough against an existing database.** Confirmed by actually booting the app (not just compiling): against a persistent Postgres instance that predates this change, Hibernate correctly runs `ALTER TABLE rental_plan ADD COLUMN version bigint NOT NULL` — but that fails outright (`column "version" ... contains null values`) because existing rows have no value for the new NOT NULL column, and `update` mode never backfills. Separately, Hibernate auto-generates a `CHECK` constraint restricting `rental_plan.status` to the enum's values at table-creation time; that constraint was baked in back when the enum still said `QUOTEED`, and `update` mode never alters/drops existing constraints — so even past the version-column failure, seeding a `'QUOTED'` row would still fail against `rental_plan_status_check`. Matches the schema-drift behavior already documented in `SPEC-entity-repository.md` §10.6. **Fix used:** temporarily set `ddl-auto=create-drop`, boot once to force a fully fresh schema from the current entity definitions, then revert to `update` — the same technique already used for this spec's own 1.0.1 verification pass. Anyone pulling this branch onto a database from before this change needs to do the same one-time reset.
- `data.sql`'s `rental_plan` insert now explicitly sets `version = 0` on every seeded row — a raw SQL `INSERT` bypasses JPA's automatic version initialization (which only happens through `save()`), so without an explicit value the insert fails `NOT NULL` on the fresh schema.
- **`siteAddress` validation (HR-116, added post-review).** `RentalPlanController.create` is now `@Valid`-annotated; `RentalPlanCreateRequest.siteAddress` carries `@NotBlank` plus `@Pattern(regexp = "^.*\\d{6}$")` — must end with a 6-digit postal code (e.g. `"20 Jurong Port Road, 619094"`), matching the identical constraint added to `CreateBookingRequest`/`BookingUpdateRequest` (`SPEC-booking-delivery-return-api.md` §5.2/§9 1.2.0, `SPEC-api-index.md` §2.2.1) in the same change. A compact canonical constructor strips leading/trailing whitespace before either constraint is evaluated. A violation is caught by `RestExceptionHandler.handleValidation(MethodArgumentNotValidException)` (new handler, same class as the `@Version` double-submit guard above) and returns `400 validation_failed` — Spring's `@Valid` runs before the controller method body, so this happens ahead of the BR-06 active-plan check, not after it. `startDate`/`endDate` carry no such constraint and remain nullable. New Maven dependency: `spring-boot-starter-validation` (`pom.xml`).
- **`PricingClient` interface (added post-review).** `addItem`'s rate lookup and math (`asset.getBaseDailyRate()` × days in range) moved behind a new `PricingClient` interface (`priceItem(Asset, LocalDate, LocalDate) -> ItemPrice(dailyRate, subtotal)`), with `DefaultPricingClient` as the only implementation — same logic as before, just extracted. Pure refactor, no behavior change: `RentalPlanService` now depends on the interface instead of computing pricing inline. This is what lets a real FastAPI-backed `PricingClient` (see §5.1 below) be swapped in later as a second implementation, without touching `RentalPlanController` or persistence code.
- **Checkout conversion (HR-129, added 2026-08-13).** `BookingService.createFromRentalPlan` handles `POST /api/bookings` when `rentalPlanId` is present — ownership check (same pattern as `loadOwnedPlan`), `status == QUOTED` check, and a 24-hour quote-freshness check against `updatedAt`, each with its own `RentalPlanConflictException` code (`quote_not_ready`/`quote_expired`) rather than the generic `409` every other business-rule rejection in this codebase shares — see REQ-6. Item add/remove (REQ-2/REQ-3) now revert a `QUOTED` plan to `DRAFT` instead of rejecting with `409`, so a stale quote is never left standing against a changed item set.

### 5.0 Explicit correction (2026-08-13): `POST /api/rentalPlans/{id}/quote` does **not** call Haystack today

Raised by a web-portal API audit: a separate, portal-facing reference document had stated that this route proxies to Haystack, sourced from a conversational clarification rather than any backend documentation. That statement is **incorrect as of current code** — recorded here explicitly so it's grounded in this spec rather than resting on say-so.

Verified directly against the source: `RentalPlanService.requestQuote` (§3 REQ-4) does not call any HTTP client, Haystack or otherwise — it sums `RentalPlanRecord.subtotal` values that were already computed and persisted when each item was **added** (`POST /api/rentalPlans/{id}/items`, REQ-2), via the injected `PricingClient`. `PricingClient` has exactly **one** registered implementation in this codebase, `DefaultPricingClient` (`@Service`, no other `@Service`/`@Primary`/`@Qualifier` competing for the interface) — confirmed by `grep`, only one class in `src/main/java` implements `PricingClient`. Its `priceItem` method is pure arithmetic: `dailyRate = asset.getBaseDailyRate()`, `subtotal = dailyRate × days`, no network call, no reference to `HaystackRecommenderClient` or any other HTTP client anywhere in the class.

The FastAPI-backed `PricingClient` described immediately below in §5.1 is the *only* place in this codebase's design where `POST /api/rentalPlans/{id}/quote` would ever reach Haystack — and per that section's own heading, it is **not yet built**. Until a second `PricingClient` implementation is written and wired in (a real code change, not a doc change), this route's pricing is Spring-only, deterministic, and has no dependency on `haystack-fast-api`'s availability.

### 5.1 Future: FastAPI-backed `PricingClient` (not yet built)

Design notes for the eventual second `PricingClient` implementation that calls Haystack instead of using `Asset.baseDailyRate` — folded in from the now-retired `SPEC-spring-proxy-endpoints.md` §1, which covered this before it had a home in this file. `POST /api/rentalPlans/{id}/quote` itself already exists and needs no new route; only `PricingClient`'s internals would change:

- Call FastAPI's `POST /internal/v1/pricing/quote` with the plan's items as one batch request, not a per-item loop. Per Haystack's own contract: `items[].asset_id` is the real `Asset.id` (integer), not a string code; the response is always `200` even on partial failure, with per-item `error` (`"asset_not_found"` / `"unrecognized_category: ..."`) and every other pricing field `null` on that item — a per-item `error` must be treated as a failure, not just a non-2xx status.
- Send `Idempotency-Key` (UUID) and forward/mint `X-Correlation-Id`, per Haystack's conventions (same headers `SPEC-haystack-recommender-client.md` §9 already requires for the recommender client).
- Only write item rates / `totalAmount` / status → `QUOTED` after a fully successful FastAPI response (no per-item `error`), inside the existing `@Transactional` method (gives rollback for free).
- The existing `@Version` double-submit guard (§5 above) still applies unchanged.

**Open item — deposit rate source conflict.** Haystack's contract returns `deposit_rate` (currently a fixed `0.30`) on every quote response and says to read it from there rather than hardcoding a copy. `RentalPlanService`/`BookingService` currently compute the 30%/70% split from their own `DEPOSIT_RATE` constant, independent of any FastAPI call. Both happen to agree today (0.30), but nothing keeps them in sync if either changes. Needs a decision before this `PricingClient` is built: keep Spring's constant as the source of truth, or switch to Haystack's `deposit_rate` field. Not resolved here.

---

## 6. Verification

### 6.1 Checklist

- [x] Only one active (`DRAFT`/`SAVED`/`QUOTED`) plan can exist per customer at a time — enforcement verified (`POST /api/rentalPlans → `409` while an active plan exists). The positive direction (creating successfully when zero active plans exist) was not exercisable at the time of original verification: the only seeded customer, Alex Tan, already started with 4 active-status plans (ids 1, 2, 3, 6) baked into `data.sql`, and there was no registration endpoint to create a fresh customer to test against. **Update (2026-08-11, PR review):** `data.sql` now seeds a second customer, Mei Lin (`mei.lin@example.sg`, renumbered to id **7** after merging `origin/develop`'s `HR-92` reseed — see 1.2.1 below — `customer456`), with zero `rental_plan` rows. **Re-verified 2026-08-11, live against a real boot (not Postman this time — curl):** logged in as Mei Lin, `POST /api/rentalPlans` → `201`, plan id 7, `status: DRAFT` — the positive direction is confirmed working, closing this gap for real.
- [x] Line items snapshot `Asset.baseDailyRate`, never a dynamic/ML rate — verified (`assetId 1`, `dailyRate: 450.00`, matching `assets.base_daily_rate`, not `min_daily_rate`/`max_daily_rate`)
- [x] Requesting a quote freezes `totalAmount` — verified (`POST .../1/quote` → `200`, `status: QUOTED`, `totalAmount: 1050.00`). ~~follow-up `POST .../1/items` → `409` once quoted~~ **Superseded by HR-129** — see the two new checklist items below; items are no longer locked once quoted.
- [x] A customer can never see or modify another customer's plan (`404`, not `403`) — verified (Ravi Kumar, an admin who doesn't own plan 1, gets `404` on `GET /api/rentalPlans/1`)
- [x] A plan with zero line items cannot be quoted (`400`) — verified (plan 6's two items removed, then `POST .../6/quote` → `400`, `"Cannot quote an empty plan"`)
- [ ] `POST /api/rentalPlans` with `siteAddress` blank, missing, or not ending in a 6-digit postal code → `400 validation_failed`, no `RentalPlan` row created (HR-116, not yet re-verified live against a running instance since this change).
- [x] **HR-129:** Adding/removing an item on a `QUOTED` plan succeeds (not `409`), reverting `status → DRAFT`, clearing `totalAmount`, refreshing `updatedAt` — **verified live 2026-08-13** against a real boot (Mei Lin, plan 9): added a 2nd item to a `QUOTED` plan → `201`, `status: DRAFT`, `totalAmount: null`; re-quoted, then removed an item → `200`, same revert.
- [x] **HR-129:** `POST /api/bookings` with a `rentalPlanId` — **verified live 2026-08-13**, all sub-cases: happy path (plan 7 → booking 91, `totalAmount: 2250.00` exactly matching the quote, deposit `675.00`/`1575.00` at 30%/70%, plan → `CONVERTED`); a converted customer immediately created a new plan (`201`, closing the BR-06 lockout bug — the original motivation for this whole change); `404` for another customer's plan (Alex Tan against Mei Lin's plan 7); `409 quote_not_ready` for an unquoted plan (plan 9, fresh `DRAFT`); `409 quote_expired` for a plan aged past 24h via direct DB update (plan 8), followed by successful re-quote-then-checkout once REQ-4's re-quote gap (found live, see below) was fixed. Direct booking (no `rentalPlanId`) day-math also re-verified: `450 × 4` inclusive days (Dec 1–4) = `1800.00`.
- [x] **HR-129, found and fixed during this live pass:** `requestQuote` originally rejected re-quoting an already-`QUOTED` plan unconditionally (`409 "Plan is already quoted"`, inherited from the pre-HR-129 REQ-4 guard), which silently broke the `quote_expired` recovery path — a customer had no way to refresh a stale quote. Caught live: a re-quote call on a deliberately-staled plan returned a `409` with no `status`/`updatedAt` fields, not the expected refreshed response. Fixed by rejecting only `CONVERTED` (see REQ-4); re-verified end-to-end afterward (stale → `409 quote_expired` → re-quote → `200` → checkout → `201`).

### 6.2 Manual smoke test

1. ~~Create a plan, add 2 line items, request a quote, and confirm `totalAmount` matches a manual sum of the line items.~~ Adapted (original 1.0.1 run): created a plan wasn't possible against seeded data at the time (see checklist note above), so that pass ran against existing seeded plan 1 instead — added 1 item (assetId 1, subtotal 2700.00), removed it, then quoted the plan's original single item and confirmed `totalAmount: 1050.00` matched. **Re-run for real, 2026-08-11 (live curl against the fully merged branch):** created a brand-new plan as Mei Lin (`POST /api/rentalPlans` → `201`, id 7), added 1 item (`assetId: 1`, CAT 320 Excavator, `dailyRate: 450.00`, `subtotal: 2250.00` — matches `450 × 5 days` for the `2026-09-01`→`2026-09-05` window), requested a quote (`POST .../7/quote` → `200`, `status: "QUOTED"`, `totalAmount: 2250.00` — matches the single item's subtotal exactly).
2. Attempt a second `POST /api/rentalPlans` while the first plan is still active — confirm `409`. **Done**, `409`.
3. With a second customer's token, attempt to `GET`/modify the first customer's plan — confirm `404`. **Done**, `404` (used Ravi Kumar's token against plan 1).
4. Request a quote on a plan with zero line items — confirm `400`. **Done**, `400` (plan 6, after removing both seeded items).

---

## 7. Known issues / concerns (flagged for follow-up)

Matches this project's convention (`SPEC-booking-delivery-return-api.md` §6) of recording known risks explicitly rather than letting them go undocumented, even when a deliberate call was made not to fix them in this pass.

- **No availability hold from quoting — flagged as a concern, not just a decision.** Per §2.2, `RentalPlan` never blocks equipment availability; only a real `Booking` does. Concretely: two different customers can both add the same excavator to their own plans for the same week, both get a `QUOTED` price, and nothing here stops either of them — the conflict only surfaces when one of them later tries to convert to a `Booking` (a separate future spec) and the equipment is no longer actually available. This is workable but has a real customer-facing failure mode (a quoted price/plan that turns out to be unbookable) that isn't resolved anywhere yet. Revisit once the plan→`Booking` conversion flow is designed — that's the natural place to decide whether this needs a hold mechanism after all.

---

## 8. Key decisions

| Decision | Rationale |
|----------|-----------|
| One active plan per customer, not one ever | Matches BR-06 as clarified during drafting (Open Question 1); keeps historical plans on record rather than deleting them. |
| `baseDailyRate` snapshotted, not dynamic pricing | Dynamic/ML pricing is blocked on the separate `haystack-fast-api` service, confirmed not ready (Open Question 2). |
| No availability hold from quoting | Matches existing precedent — `Booking` status alone gates availability today (`AssetService`/`SPEC-equipment-browse-api.md`); confirmed no spec addresses this for `RentalPlan` (Open Question 3). First customer to convert to a real `Booking` wins; conflict resolution is out of scope here. See §7 — flagged as an open concern, not a fully closed decision. |
| Ownership check built in from the start | Avoids repeating the known, already-flagged gap on `Booking` (`SPEC-booking-delivery-return-api.md` §6.1). |
| No line-item quantity | `RentalPlanRecord` entity already lacks a quantity column; out of scope to redesign here. |

---

## 9. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-10 | Initial draft, created collaboratively — REQ-1 through REQ-5 captured; all three open questions (BR-06 scope, daily-rate source, availability hold) resolved and §7 "Known issues / concerns" added. Not yet implemented — `RentalPlanController` still the pre-existing stub. |
| 1.0.0 | 2026-08-10 | Implemented and manually verified end-to-end (REQ-1 through REQ-5) via Postman against seeded data, on `hr-19-request-quote`. Two pre-existing bugs found and fixed along the way, both unrelated to this spec's own logic: (1) `data.sql` was missing a `setval(...)` sequence fixup for `rental_plan`/`rental_plan_records` — every seeded table except `users` had this same latent gap, only `rental_plan` happened to be the next one to get a real `save()` exercised against it; fixed by adding the same fixup `users` already had. (2) `RentalPlanService` was the only service in the codebase missing `@Transactional`, causing a `LazyInitializationException` on `RentalPlanRecord.asset` the first time a plan with real line items was serialized; fixed by adding `@Transactional`/`@Transactional(readOnly = true)` matching the convention already used in `AssetService`/`BookingService`/`DeliveryService`/`ReturnService`. |
| 1.0.1 | 2026-08-11 | §6.1 checklist and §6.2 smoke test walked through step-by-step in Postman against a fresh reset of the seeded data (`ddl-auto` temporarily set to `create-drop`, then reverted to `update`) and checked off with actual observed responses. All 5 checklist items pass; one caveat documented inline — the "one active plan per customer" rule's positive direction (create succeeds with zero active plans) isn't exercisable against current seed data, since Alex Tan already starts with 4 active-status plans and there's no registration endpoint to create a fresh customer. |
| 1.1.0 | 2026-08-11 | Three PR review changes applied together: (1) all six routes renamed `/api/rental-plans` → `/api/rentalPlans` (this doc's route references updated throughout); (2) the `QUOTEED` enum-constant typo fixed to `QUOTED` everywhere (entity, service, seed data, this doc); (3) added a `@Version` optimistic-locking column to `RentalPlan` plus an `ObjectOptimisticLockingFailureException` → `409` handler, closing the double-submit gap (§5). |
| 1.1.1 | 2026-08-11 | Verified 1.1.0 by actually booting the app against a real Postgres instance (not just `mvnw compile`), which surfaced two real bugs neither compilation nor code review would have caught: (1) against a database that pre-dates this change, `ddl-auto=update` fails outright adding the new `version` column (existing rows have no value for a NOT NULL column) and, separately, Hibernate's auto-generated `rental_plan_status_check` CHECK constraint was baked in with the old `QUOTEED` spelling and `update` mode never alters it — fixed with the same `create-drop`-then-`update` reset already used in 1.0.1 (documented in §5 as a migration note for anyone else pulling this branch onto an existing database); (2) `data.sql`'s `rental_plan` insert needed an explicit `version = 0` per row, since raw SQL inserts bypass JPA's automatic version initialization. Also added a fifth seeded customer, Mei Lin (id 5, zero `rental_plan` rows), closing the 1.0.1-documented testing gap for BR-06's positive direction (§6.1 updated). |
| 1.2.0 | 2026-08-11 | PR review's fourth change: extracted `addItem`'s inline rate/subtotal math into a new `PricingClient` interface (`service/PricingClient.java`) with `DefaultPricingClient` as its sole implementation (§5) — pure refactor, verified by re-running the full boot test with no behavior change. Sets up the swap point for a future FastAPI-backed pricing client (see new `SPEC-spring-proxy-endpoints.md`, added to this branch in the same review) without touching the controller or persistence code. Header **Related code** updated. |
| 1.2.1 | 2026-08-11 | Merged `origin/develop` (`HR-92-data-seeding-for-ml`, a 90-booking/27-asset reseed that landed independently) into this branch. Real conflict: both branches added a user at `id=5` (ours: Mei Lin; develop's: Mei Ling + a 6th, Farid Rahman). Resolved by keeping develop's ids as-is (referenced 47× elsewhere in `data.sql` via name-based subqueries) and renumbering Mei Lin to `id=7` — zero risk, since she has no other references anywhere by design. §6.1/§6.2 updated to the new id. Merge then verified live end-to-end (not just compiled): logged in as Mei Lin against the fully merged app, created a plan, added an item, requested a quote — full results in §6.1/§6.2 above. This closes out the positive-direction re-verification that 1.1.1 had left pending. |
| 1.3.0 | 2026-08-13 | **`SPEC-spring-proxy-endpoints.md` retired as redundant with `SPEC-api-index.md` + this file.** Its only content not already duplicated elsewhere — the FastAPI-backed `PricingClient` design (batch call, `Idempotency-Key`/`X-Correlation-Id`, per-item error handling, the open deposit-rate-source conflict) — folded in as new §5.1. `PricingClient`/`DefaultPricingClient` cross-references elsewhere in this file (1.2.0/1.2.1 entries above, §5) updated to stop pointing at the now-deleted file. |
| 1.4.0 | 2026-08-13 | **New §5.0, correcting an external misconception surfaced by a web-portal API audit.** A portal-facing reference document had stated `POST /api/rentalPlans/{id}/quote` proxies to Haystack; re-checked directly against `RentalPlanService`/`PricingClient`/`DefaultPricingClient` and found that claim false as of current code — `DefaultPricingClient` is the only registered `PricingClient` implementation and is pure arithmetic, no HTTP call. §5.0 records this explicitly so the route's actual (Spring-only) behavior is grounded in this spec rather than in an unverified conversational claim. No behavior change; §5.1's not-yet-built FastAPI plan is unaffected. |
| 1.4.1 | 2026-08-13 | [`SPEC-spring-proxy-endpoints.md`](./SPEC-spring-proxy-endpoints.md) — retired earlier the same day (1.3.0 above) as redundant with §5.1 below — **restored**, specifically because 1.4.0's correction showed the redundancy argument had a blind spot: folding "Spring will proxy to Haystack here" into this feature-scoped file made it easy to read as already true. §5.1 unchanged; that file now leads with current (Spring-only) behavior before its own copy of the same FastAPI-rewire design. |
| 1.5.0 | 2026-08-13 | **HR-116: `siteAddress` postal-code validation on `POST /api/rentalPlans`.** (Renumbered from a colliding `1.3.0` assigned independently on this branch.) `RentalPlanCreateRequest.siteAddress` now carries `@NotBlank` + `@Pattern(regexp = "^.*\\d{6}$")` (must end with a 6-digit postal code), stripped of leading/trailing whitespace in a compact canonical constructor; `RentalPlanController.create` is now `@Valid`-annotated. New `RestExceptionHandler.handleValidation(MethodArgumentNotValidException)` maps a violation to `400 validation_failed`, ahead of the BR-06 active-plan check (§3 REQ-1, §5 updated). Identical constraint added in the same change to `CreateBookingRequest`/`BookingUpdateRequest` — see `SPEC-booking-delivery-return-api.md` §9 1.2.0 and `SPEC-api-index.md` §2.2.1. New Maven dependency `spring-boot-starter-validation`. Not yet re-verified live against a running instance (§6.1 checklist item added, unchecked). |
| 1.6.0 | 2026-08-13 | **Resolved unmerged git conflict markers found live in this file** (both this section and §5, discovered while documenting HR-129 below) — `<<<<<<< HEAD`/`=======`/`>>>>>>>` markers and a corrupted, mid-sentence-truncated duplicate of §5.0/§5.1 had been committed as part of an earlier merge on this branch (confirmed via `git show HEAD`, not a working-tree artifact). Resolved by keeping one complete copy of each duplicated section — HEAD's full §5.1 (the develop-side duplicate was truncated) plus develop's unique `siteAddress` bullet and 1.5.0 changelog entry, HEAD's fuller 1.3.0–1.4.1 entries plus develop's unique 1.5.0. No content lost; only the duplication and stray markers removed. |
| 1.7.0 | 2026-08-13 | **HR-129: checkout conversion.** `POST /api/bookings` with `rentalPlanId` now actually converts a `QUOTED` plan into a `Booking` — new REQ-6 (ownership/status/24-hour-freshness checks, `quote_not_ready`/`quote_expired` error codes via `RentalPlanConflictException`, items/dates/price derived from the plan's own records, plan `status → CONVERTED`). REQ-2/REQ-3 changed: item add/remove on a `QUOTED` plan no longer returns `409` — it reverts the plan to `DRAFT` (clearing `totalAmount`, refreshing `updatedAt`) instead, so a stale quote is never left standing. REQ-4/REQ-5 updated: `requestQuote` now refreshes `updatedAt` (the timestamp REQ-6's freshness check reads), and `RentalPlanResponse` gains `updatedAt`/`createdAt`. §2.1/§2.2 updated — conversion is now in scope; the §2.2 "availability holds" note updated to reflect that the quote-time double-booking race is now actually resolved at checkout, not left as an open future concern. Also fixed, same change: `BookingService`'s day-count math (`DAYS.between` with no `+1`) disagreed with `DefaultPricingClient`'s quoting math (`+1`, inclusive) — now aligned, so a booking's total always matches what was quoted. Full contract for the web portal: `features/rental-plan-checkout-conversion/api-contract-for-frontend.md`. |
| 1.8.0 | 2026-08-13 | **HR-129 live verification, full pass against a real boot** (Postgres on `db`, Mei Lin/Alex Tan seeded customers) — §6.1 checklist updated with actual observed results for every REQ-6 sub-case plus the REQ-2/REQ-3 revert behavior. **One real bug found and fixed during this pass, not caught by code review or compilation**: `requestQuote` (REQ-4) still carried its pre-HR-129 guard rejecting any non-`DRAFT`/`SAVED` plan, which meant re-quoting an already-`QUOTED`-but-stale plan — the exact recovery path `quote_expired` (REQ-6) is supposed to route a customer to — was itself rejected with `409`. Fixed to reject only `CONVERTED`; re-verified live afterward. REQ-4 updated to document both the fix and the now-working stale-quote-recovery flow. |
