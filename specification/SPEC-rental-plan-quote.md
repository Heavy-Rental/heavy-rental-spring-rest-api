# Specification: Rental Plan — Build & Quote

| Field | Value |
|-------|--------|
| **Feature** | Rental Plan build-and-quote flow (Jira `HR-19` "Request Quote") |
| **Status** | Implemented on `hr-19-request-quote`, not yet merged to `develop`. REQ-1 through REQ-5 all built and manually verified (Postman) against seeded data. |
| **Module** | `heavy-rental-spring-rest-api` |
| **Primary surface** | REST API for a customer building a Rental Plan (equipment line items + dates) into a priced quote |
| **Method** | Specification Driven Design (SDD) |
| **Related code** | `controller/RentalPlanController.java` (currently a stub, always returns `[]`), `entity/RentalPlan.java`, `entity/RentalPlanRecord.java`, `repository/RentalPlanRepository.java`, `repository/RentalPlanRecordRepository.java` |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |

This document is the **single source of truth** for the `/api/rental-plans` REST surface up through quoting. It does not cover agreement drafting/e-signature (`HR-21`) or converting a quoted plan into a `Booking` — those are separate, downstream work.

---

## 1. Outcomes

When this feature is correct:

1. A customer can start a new rental plan with a date range and site address (REQ-1).
2. Each customer can have only one **active** rental plan (`DRAFT`/`SAVED`/`QUOTEED`) at a time, with past/converted plans kept on record (BR-06).
3. A customer can add and remove equipment line items on an active plan, each snapshotting the asset's base daily rate at add-time (REQ-2, REQ-3).
4. A customer can request a quote, which computes and freezes the plan's total and locks its line items against further changes (REQ-4).
5. A customer can only ever see or modify their **own** rental plans (REQ-5).

---

## 2. Scope

### 2.1 In scope

- Creating a rental plan, with BR-06 (one active plan per customer) enforced at creation time.
- Adding and removing equipment line items on an active (`DRAFT`/`SAVED`) plan, each snapshotting `Asset.baseDailyRate`.
- Requesting a quote: computing `totalAmount` from all line items and locking the plan's items.
- Listing and retrieving a customer's own plans — ownership-scoped, never another customer's.

### 2.2 Out of scope

- `HR-20` "Rental Quote Discount Recommendation" — no discount field or logic in this spec.
- `HR-21` "Auto-draft rental agreement for e-signature" — agreement drafting, e-signature, and the `CONVERTED` status transition are separate, downstream work.
- Converting a `QUOTEED` plan into a `Booking` — `Booking.rentalPlan` is a real FK today, but no endpoint populates it; that conversion is its own future spec.
- Dynamic/ML-based pricing — blocked on the separate `haystack-fast-api` service, confirmed not ready (same blocker as the admin Pricing tab's rate recommendation). `Asset.baseDailyRate` is used instead — see Open Question 2.
- Line-item quantity — `RentalPlanRecord` has no quantity column; renting 2 units of one asset means 2 separate line-item rows. Not redesigning the entity here.
- BR-04/BR-05 (30% deposit, full payment 2 days before delivery) — checkout-time concerns that apply once a plan becomes a `Booking`, not part of quoting itself.
- **Availability holds.** Quoting/adding an item to a `RentalPlan` never blocks that equipment's availability for other customers — `Booking` (with `PENDING`/`CONFIRMED`/`MOBILISED` status) remains the sole source of availability truth, per existing precedent (`SPEC-equipment-browse-api.md` §"blocking" note) and `AssetService.resolveAvailabilityWindow`, which has no awareness of `RentalPlan` at all. Two customers can quote the same equipment for overlapping dates; whichever converts to a real `Booking` first wins — that conflict is resolved at conversion time (a separate future spec), not here. Checked: this exact question isn't addressed in any existing spec file — decided fresh this session, not inherited from documented behavior.

---

## 3. Requirements

### REQ-1: Create a rental plan (BR-06)

**GIVEN** an authenticated customer with no plan in `DRAFT`/`SAVED`/`QUOTEED`
**WHEN** they `POST /api/rental-plans` with `startDate`, `endDate`, `siteAddress`
**THEN** a new `RentalPlan` is created with `status = DRAFT` and returned.

**GIVEN** a customer who already has an active plan (`DRAFT`/`SAVED`/`QUOTEED`)
**WHEN** they `POST /api/rental-plans` again
**THEN** the request is rejected with `409 Conflict`.

### REQ-2: Add a line item

**GIVEN** a `DRAFT`/`SAVED` plan owned by the caller
**WHEN** they `POST /api/rental-plans/{id}/items` with a valid `assetId`
**THEN** a `RentalPlanRecord` is created with `dailyRate = asset.baseDailyRate` and `subtotal = dailyRate × days in the plan's date range`.

**GIVEN** a `QUOTEED` plan
**WHEN** an item add is attempted
**THEN** the request is rejected with `409` — items are locked once quoted.

### REQ-3: Remove a line item

**GIVEN** a `DRAFT`/`SAVED` plan owned by the caller
**WHEN** they `DELETE /api/rental-plans/{id}/items/{itemId}`
**THEN** the line item is removed.

**GIVEN** a plan not owned by the caller
**WHEN** any operation is attempted on it (view, add, remove, quote)
**THEN** the request returns `404` — not `403`, so a customer can't even confirm another customer's plan exists.

### REQ-4: Request a quote

**GIVEN** a `DRAFT`/`SAVED` plan owned by the caller with at least one line item
**WHEN** they `POST /api/rental-plans/{id}/quote`
**THEN** `totalAmount` is set to the sum of all line items' subtotals and `status` moves to `QUOTEED`.

**GIVEN** a plan with zero line items
**WHEN** a quote is requested
**THEN** the request is rejected with `400`.

### REQ-5: List and get, ownership-scoped

**GIVEN** an authenticated customer
**WHEN** they `GET /api/rental-plans`
**THEN** only their own plans are returned — never another customer's.

---

## 4. Open questions (need your decision before implementation)

All resolved during spec drafting — kept here for traceability, matching this project's convention of tracking exactly how each open question was resolved:

1. **BR-06 scope**: does "one rental plan per customer" mean one plan ever, for the account's entire history, or only one **active** plan at a time? **Resolved:** one active (`DRAFT`/`SAVED`/`QUOTEED`) plan at a time — past/converted plans stay on record.
2. **Daily-rate source**: `Asset` has `baseDailyRate`, `minDailyRate`, and `maxDailyRate` — which is snapshotted into `RentalPlanRecord.dailyRate` when a line item is added? **Resolved:** `Asset.baseDailyRate`. Dynamic/ML-recommended pricing (the `haystack-fast-api` service) isn't ready, so it isn't used here.
3. **Availability hold on quoting**: should quoting/adding an item place a hold on the equipment's availability for other customers? **Resolved:** no hold — matches existing precedent (`Booking` status alone gates availability). See §7 for the concern this leaves open.

---

## 5. Design

Left intentionally high-level, per this project's convention of keeping the contract separate from implementation detail:

- New `RentalPlanService`, replacing the current `RentalPlanController` stub (`controller/RentalPlanController.java`, today just `GET → []`).
- New request/response records: `RentalPlanResponse`, `RentalPlanItemResponse`, `RentalPlanCreateRequest`, `RentalPlanItemRequest` — following this codebase's existing DTO-as-record convention (see `EquipmentResponse.java`).
- No new entities or columns — `RentalPlan`, `RentalPlanRecord`, and both their repositories already exist and are unused today (`RentalPlanRepository.findByCustomerId`/`findByStatus` and `RentalPlanRecordRepository.findByRentalPlanId` already cover what REQ-1 and REQ-5 need).
- Day-count math has no existing convention in this codebase to match (checked every service — no `ChronoUnit`/`Period` usage anywhere); plan is `ChronoUnit.DAYS.between(startDate, endDate) + 1` (inclusive of both start and end day) — worth confirming once implementation starts.
- Needs a real ownership check (`plan.customer.id == principal.id`) — no other route in this codebase does this yet (`SPEC-booking-delivery-return-api.md` §6.1 flags the same gap as unfixed on `Booking`); this spec deliberately builds it in rather than repeating that gap.

---

## 6. Verification

### 6.1 Checklist

- [ ] Only one active (`DRAFT`/`SAVED`/`QUOTEED`) plan can exist per customer at a time
- [ ] Line items snapshot `Asset.baseDailyRate`, never a dynamic/ML rate
- [ ] Requesting a quote locks line items and freezes `totalAmount`
- [ ] A customer can never see or modify another customer's plan (`404`, not `403`)
- [ ] A plan with zero line items cannot be quoted (`400`)

### 6.2 Manual smoke test

1. Create a plan, add 2 line items, request a quote, and confirm `totalAmount` matches a manual sum of the line items.
2. Attempt a second `POST /api/rental-plans` while the first plan is still active — confirm `409`.
3. With a second customer's token, attempt to `GET`/modify the first customer's plan — confirm `404`.
4. Request a quote on a plan with zero line items — confirm `400`.

---

## 7. Known issues / concerns (flagged for follow-up)

Matches this project's convention (`SPEC-booking-delivery-return-api.md` §6) of recording known risks explicitly rather than letting them go undocumented, even when a deliberate call was made not to fix them in this pass.

- **No availability hold from quoting — flagged as a concern, not just a decision.** Per §2.2, `RentalPlan` never blocks equipment availability; only a real `Booking` does. Concretely: two different customers can both add the same excavator to their own plans for the same week, both get a `QUOTEED` price, and nothing here stops either of them — the conflict only surfaces when one of them later tries to convert to a `Booking` (a separate future spec) and the equipment is no longer actually available. This is workable but has a real customer-facing failure mode (a quoted price/plan that turns out to be unbookable) that isn't resolved anywhere yet. Revisit once the plan→`Booking` conversion flow is designed — that's the natural place to decide whether this needs a hold mechanism after all.

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
