# Plan: Rental Plan → Booking Checkout Conversion Fix

| Field | Value |
|-------|--------|
| **Document type** | Spec-Kit plan artifact (HOW) — not yet built |
| **Status** | Draft — implementation not started |
| **Date** | 2026-08-13 |
| **Owning spec** | [`SPEC-rental-plan-quote.md`](../../SPEC-rental-plan-quote.md) (§2.2 explicitly scopes conversion out — this is that "separate future spec"), cross-referenced from [`SPEC-api-index.md`](../../SPEC-api-index.md) §2.2.1 |
| **PR strategy** | Single Spring Boot PR — see §8. Cross-referenced against the web portal's independent 3-PR plan (`temp_web/Spec-rental-plan-cart-checkout.md`) — see §7. |
| **Frontend contract** | [`api-contract-for-frontend.md`](./api-contract-for-frontend.md) — the handoff document, separate from this internal HOW plan. Literal request/response JSON, error codes, status-casing. |

## 1. Problem recap

Walking the cart → quote → checkout → deposit-payment workflow against the current code found one real bug and several gaps, all inside the same code path (`BookingService.createBooking`):

1. **Bug:** a converted plan is never marked `CONVERTED`. `RentalPlan.PlanStatus.CONVERTED` is declared and never set anywhere in the codebase. Since `RentalPlanService.create`'s BR-06 check treats `QUOTED` as active, a customer who completes one booking is permanently locked out of starting a new cart.
2. Checkout (`POST /api/bookings`) ignores the `RentalPlan` entirely when pricing: it re-reads `items`/`startDate`/`endDate` from the request body and recomputes `totalAmount` live from `Asset.baseDailyRate`, rather than reusing the plan's already-frozen quote.
3. Day-count math disagrees between the two pricing paths — `DefaultPricingClient` uses `DAYS.between(start,end) + 1`, `BookingService.createBooking` uses `DAYS.between(start,end)` (no `+1`) — so the "actual" price at checkout can differ from the quoted price even with no rate change.
4. No ownership check, no `status == QUOTED` check, and no quote-freshness check on `rentalPlanId` in `createBooking`.
5. `RentalPlan.createdAt`/`updatedAt` are never set anywhere (`RentalPlanService.create` never calls `setCreatedAt`), so there's no timestamp to build a 24-hour quote-validity rule on top of yet.

A second round of review, cross-referencing the web portal's own planning docs (`temp_web/Spec-rental-plan-cart-checkout.md`, `temp_web/Spec-rest-api-reference.md`), surfaced further items — folded into this plan as of v0.2.0 (§7).

## 2. Target behavior

`POST /api/bookings` with a `rentalPlanId`:

- 404 if the plan doesn't belong to the caller (matches this codebase's existing "404, not 403" convention for cross-customer access — see `SPEC-rental-plan-quote.md` REQ-3).
- 409 if the plan's `status != QUOTED`.
- 409 if the quote is stale (> 24h since it was quoted) — response should tell the client to re-quote (`POST /rentalPlans/{id}/quote`) before retrying checkout.
- On success: booking's items, dates, and `totalAmount` are derived from the plan's own `RentalPlanRecord`s and frozen `totalAmount` — not recomputed from a client-supplied item list. `RentalPlan.status` flips to `CONVERTED` in the same transaction.
- Booking-without-a-plan (`rentalPlanId` omitted) keeps working as it does today, day-math fix aside.

`POST /rentalPlans/{id}/items` and `DELETE /rentalPlans/{id}/items/{itemId}` on a `QUOTED` plan:

- No longer `409`. Instead, the mutation succeeds, `status` reverts to `DRAFT`, `totalAmount` is cleared (it's now stale), and `updatedAt` refreshes. **This is a deliberate reversal of `SPEC-rental-plan-quote.md` REQ-2/REQ-3's current, already-verified "locked once quoted" behavior** — see §7 (B10) for why, and update that spec's checklist accordingly rather than leaving it looking still-accurate.

`RentalPlanResponse` (all read routes: `POST/GET /rentalPlans`, `GET /rentalPlans/{id}`, `POST /rentalPlans/{id}/quote`) additionally includes `updatedAt` and `createdAt`, so the frontend can compute quote-expiry state without an extra call.

## 3. Execution steps

Ordered so each step leaves the code compiling and the previous step's behavior intact.

### Step 1 — Stamp `RentalPlan` timestamps
- `RentalPlanService.create()`: set `plan.setCreatedAt(LocalDateTime.now())`.
- `RentalPlanService.requestQuote()`: set `plan.setUpdatedAt(LocalDateTime.now())` right before saving the `QUOTED` transition — this becomes the "quoted at" stamp. (`updatedAt` is currently declared but never written anywhere, so repurposing it as "last quoted at" doesn't collide with any existing use.)

### Step 2 — Fix day-count math consistency
- Align `BookingService.createBooking`'s day count with `DefaultPricingClient` (`DAYS.between(start,end) + 1`, inclusive of both ends). Applies to the no-`rentalPlanId` direct-booking path too.
- **Ships in this PR, not held back.** Confirmed non-breaking: the response contract (`totalAmount`/`depositAmount`/`remainingBalance` fields/types) is unchanged, so nothing on the frontend can fail to parse it, and the frontend already treats its own client-side deposit estimate as non-authoritative (`temp_web/Spec-rest-api-reference.md` §8.2), so a corrected server-side number is within expected variance. The one non-engineering follow-up: flag to whoever owns pricing that checkout totals shift slightly (by one day's rate) from this PR onward, independent of when the frontend's own rewire lands.

### Step 3 — Derive booking from the `RentalPlan` when `rentalPlanId` is present
In `BookingService.createBooking`, when `request.rentalPlanId() != null`:
- Load the plan, verify `plan.getCustomer().getId().equals(customer.getId())` → 404 if not (reuse the same pattern as `RentalPlanService.loadOwnedPlan`).
- Verify `plan.getStatus() == QUOTED` → 409 if not, thrown as `RentalPlanConflictException("quote_not_ready", "...")` (see below), not a generic `ResponseStatusException`.
- Verify `Duration.between(plan.getUpdatedAt(), now) <= 24h` → 409 if stale, thrown as `RentalPlanConflictException("quote_expired", "...")`.
- Load the plan's `RentalPlanRecord`s via `RentalPlanRecordRepository.findByRentalPlanId`; build `BookingItem`s from them (asset, dailyRate, subtotal all copied from the record, not recomputed).
- Use `plan.getStartDate()`/`plan.getEndDate()` and `plan.getTotalAmount()` for the booking, instead of the request body's `items`/`startDate`/`endDate`.
- Still run the existing availability/overlap conflict check (`findAssetIdsWithOverlappingBooking`) against the derived asset list before persisting — availability was never held at quote time (`SPEC-rental-plan-quote.md` §7), so it must be re-checked here.
- After the `Booking`/`BookingItem`s save successfully, set `plan.setStatus(CONVERTED)` and save the plan, same transaction.
- When `rentalPlanId` is absent, behavior is unchanged (still validates/prices off the request body's `items`).

**New exception type (distinct error codes, not generic `conflict`).** `RestExceptionHandler.handleResponseStatus` currently collapses every `ResponseStatusException` into one code per HTTP status (all `409`s → `"error":"conflict"`) — checked directly against the current file. That's not good enough here: the frontend's own checkout flow needs to tell "quote not requested yet" apart from "quote expired, please re-quote" to show the right message and route back to the right screen, and matching on the free-text `message` field is fragile. Add:
- `service/RentalPlanConflictException.java` — small `RuntimeException` carrying its own `code` + `message`, same shape as the existing `HaystackException` → dedicated-handler pattern already in this codebase.
- `RestExceptionHandler`: new `@ExceptionHandler(RentalPlanConflictException.class)` → `409` with `{"error": ex.getCode(), "message": ex.getMessage()}`.
- Used only for `quote_not_ready` and `quote_expired` above. The pre-existing double-submit `@Version` conflict (`ObjectOptimisticLockingFailureException` → generic `"conflict"`) is untouched — it can still fire here too if two "Proceed to Payment" clicks race, since this step also writes `RentalPlan.status`; that's existing, already-handled behavior, not new.

### Step 4 — DTO/contract adjustments
- `CreateBookingRequest`: `items`/`startDate`/`endDate` become optional when `rentalPlanId` is supplied (document this — don't loosen `@NotBlank`/`@Pattern` on `siteAddress`, that stays required either way).
- Add a small validation note: if `rentalPlanId` is present, `items` is ignored (not merged, not validated against) — avoids ambiguity about which source of truth wins.
- `RentalPlanResponse` (B8): add `updatedAt` and `createdAt` fields, sourced straight from the entity. Purely additive — safe for any existing caller.

### Step 5 — Item add/remove reverts a `QUOTED` plan to `DRAFT` (B10)
In `RentalPlanService.addItem` and `removeItem`, replace the current `409` (`"Plan is already quoted — items are locked"`) with:
- If `plan.getStatus() == QUOTED`: perform the add/remove as normal, then also set `plan.setStatus(DRAFT)`, `plan.setTotalAmount(null)` (the frozen total is now stale — don't leave a `QUOTED`-looking number sitting on a `DRAFT` plan), and `plan.setUpdatedAt(LocalDateTime.now())`, and save.
- If `plan.getStatus() == DRAFT`/`SAVED`: unchanged from today.
- This is a deliberate behavior reversal, not an extension — flag it as such in the PR description, not just in code comments, since it changes something `SPEC-rental-plan-quote.md`'s checklist already marked verified.

### Step 6 — Spec updates (same change set, per this project's "update docs with the code" convention)
- `SPEC-rental-plan-quote.md`: §2.2 loses the "conversion is out of scope" line; add the conversion contract (ownership/status/freshness checks, `CONVERTED` transition). REQ-2/REQ-3 rewritten to describe the revert-to-`DRAFT` behavior instead of the `409`-lock; §6.1 checklist items for the old locked-item behavior marked superseded, not silently dropped.
- `SPEC-api-index.md` §2.2.1: document the `rentalPlanId`-present behavior (derived items, 409s, `CONVERTED` transition) alongside the existing direct-booking contract.
- `SPEC-entity-repository.md` §5.5: note that `updatedAt` is now written (quoted-at stamp, and refreshed on post-quote item mutation), `createdAt` is now written at plan-creation time, and `RentalPlanResponse` exposes both.

### Step 7 — Manual end-to-end verification
Run against a real boot, not just `mvnw compile` (matches this project's established verification convention):

1. Fresh customer: `POST /rentalPlans` → `201 DRAFT`. `POST /rentalPlans/{id}/items` → item added, estimated price shown. `POST /rentalPlans/{id}/quote` → `200 QUOTED`, `totalAmount` frozen, `updatedAt` present in response.
2. `POST /bookings` with that `rentalPlanId` → `201`, booking `totalAmount` exactly matches the plan's quoted `totalAmount`, status `PENDING_DEPOSIT`. Confirm (DB or `GET /rentalPlans/{id}`) the plan is now `CONVERTED`.
3. Immediately try `POST /rentalPlans` again as the same customer → `201` (no longer blocked — confirms the BR-06 lockout bug is fixed).
4. Repeat steps 1–2 but manually age the plan's `updatedAt` past 24h before checkout → `POST /bookings` → `409`, confirm re-quoting (`POST /rentalPlans/{id}/quote`) then checkout succeeds.
5. Attempt checkout with another customer's `rentalPlanId` → `404`.
6. Attempt checkout against a `DRAFT`/unquoted plan's id → `409`.
7. Quote a plan, then add a second item to it → confirm `200` (not `409`), `status` back to `DRAFT`, `totalAmount` null, `updatedAt` refreshed. Quote again, then remove an item → same check.
8. Run the existing deposit-payment flow (`POST /payments/deposit-intent` → webhook success) against the booking from step 2 → confirm `PENDING_DEPOSIT → PENDING_CONFIRMED`, matching already-verified behavior in `PaymentWebhookService`.
9. Direct booking (no `rentalPlanId`) still works, with corrected day-count math — spot check a multi-day booking's `totalAmount` against `dailyRate × inclusive days`.

## 4. Files touched

| File | Change |
|---|---|
| `service/RentalPlanService.java` | Set `createdAt` on create; set `updatedAt` on quote; revert-to-`DRAFT` on post-quote item add/remove (B10) |
| `service/BookingService.java` | Ownership/status/freshness checks; derive items+pricing from plan when `rentalPlanId` present; day-count fix; set plan `CONVERTED`; throw `RentalPlanConflictException` for the two new 409s |
| `service/RentalPlanConflictException.java` | New — small `RuntimeException` carrying `code`+`message`, mirrors the existing `HaystackException` pattern |
| `config/RestExceptionHandler.java` | New `@ExceptionHandler(RentalPlanConflictException.class)` → `409` with the exception's own code |
| `dto/CreateBookingRequest.java` | Loosen `items`/`startDate`/`endDate` to optional-when-`rentalPlanId`-present (doc comment, no annotation change expected) |
| `dto/RentalPlanResponse.java` | Add `updatedAt`, `createdAt` fields (B8) |
| `repository/RentalPlanRecordRepository.java` | No change expected — `findByRentalPlanId` already exists |
| `SPEC-rental-plan-quote.md`, `SPEC-api-index.md`, `SPEC-entity-repository.md` | Doc updates per §6 above |
| `api-contract-for-frontend.md` (this feature folder) | New — frontend-facing handoff doc, kept separate from this HOW plan |

No entity/schema changes — `RentalPlan.updatedAt`/`createdAt` and `Booking`/`BookingItem` columns already exist. No new endpoint — B5 (`POST /api/pricing/estimate`) is explicitly dropped, see §7.

## 5. Risks

- **Stale-quote UX**: a 409 mid-checkout requires the frontend to catch it and re-trigger `/quote` before retrying — needs frontend coordination, not just a backend change. Mitigated by giving it its own `quote_expired` code (see Step 3) instead of leaving the frontend to match on message text.
- **`items` semantics change**: any existing frontend caller that always sends both `rentalPlanId` and a fresh `items` list will silently have `items` ignored post-fix.
- **Optimistic lock interaction**: `RentalPlan` already carries `@Version` (double-submit guard on `/quote`). Both the `CONVERTED` write (`createBooking`) and the revert-to-`DRAFT` write (`addItem`/`removeItem`, B10) increment this — should not conflict in practice, but worth a double-submit sanity check given B10 adds a second write path that changes `status`.
- **Day-math fix changes live pricing immediately** (Step 2) — not a compatibility risk (contract unchanged), but a real dollar-amount change to the current production checkout flow from the moment this PR ships, independent of frontend readiness. Loop in whoever owns pricing communication.
- **B10 reverses already-verified spec behavior** — `SPEC-rental-plan-quote.md`'s §6.1 checklist currently has a checked-off item for the `409`-lock behavior this PR removes. Update that checklist, don't just leave it looking stale (this project's own stated convention, e.g. `SPEC-entity-repository.md`'s "update docs in the same change set").

## 6. Test plan

Manual, live-boot verification per Step 7 above, matching this project's existing convention (no permanent automated test suite for this layer today — see `SPEC-rental-plan-quote.md` §6, `CHANGES-monthly-utilization.md` §2). No new automated tests planned unless requested.

## 7. Cross-team alignment (web portal's `temp_web/*` docs)

The web portal team independently drafted their own plan (`temp_web/Spec-rental-plan-cart-checkout.md`) and REST reference (`temp_web/Spec-rest-api-reference.md`), listing backend items B1–B10. Reconciled against this repo's actual code as follows — both temp files are expected to be deleted once this work lands, so the resolution is captured here instead:

| Item | Resolution |
|---|---|
| B1–B3 | Matches Steps 1–3 exactly. No new scope. |
| B4 (no-commit preview endpoint) | Confirmed unnecessary — `POST /rentalPlans/{id}/quote` already is the no-commit preview; checkout just needs to reuse its frozen `totalAmount`. Their own doc reaches the same conclusion. |
| B5 (`POST /api/pricing/estimate`) | **Dropped — not built.** Clarified scope is a single-item price preview (`baseDailyRate × days`) while browsing/considering an add, not a whole-cart total. `GET /api/equipment`/`GET /api/equipment/{id}` already return `baseDailyRate`; the frontend already has the plan's date range. This is client-side multiplication, not a server call — and `DefaultPricingClient` computes the real add-time price the identical way, so there's no drift risk. **Frontend-facing requirement, not a backend change:** their client-side formula must use the same inclusive (`+1`) day count as `DefaultPricingClient`/Step 2, or the preview will disagree with the real price at add-time. |
| B6 (`Booking.status → PENDING_CONFIRMED` on deposit success) | **Correction, not a gap** — already implemented today at `PaymentWebhookService.java:69-70`. Their doc cites a stale claim from `Spec-stripe-payment-checkout.md` predating this fix. |
| B7 (quote sets `status=QUOTED` and refreshes `updatedAt`) | Half already true (`status=QUOTED` already happens in `requestQuote`); the `updatedAt` refresh half is Step 1. No new scope beyond Step 1. |
| B8 (`RentalPlanResponse` needs `status`/`updatedAt`) | `status` already present; `updatedAt`/`createdAt` added in Step 4. |
| B9 (filter `GET /rentalPlans` to the active plan) | **No new endpoint.** BR-06 guarantees at most one active (non-`CONVERTED`) plan per customer, so client-side filtering over a short list is cheap — recommended back to the frontend team rather than adding server-side filtering. |
| B10 (item mutation reverts `quoted → draft`) | **Included** — Step 5. Confirmed as a deliberate reversal of `SPEC-rental-plan-quote.md` REQ-2/REQ-3's existing locked-item behavior, not a new extension; the spec's own verification checklist gets updated in Step 6, not left stale. |

**Also corrected:** `temp_web/Spec-rest-api-reference.md` §8.1 claims `POST /rentalPlans/{id}/quote` "reaches Haystack" for AI-informed pricing. Confirmed false against current code — grepped `service/` for Haystack usage; it only appears in `RecommenderSagaService` (the unrelated S2b recommender feature). `requestQuote` is, and remains under this plan, pure arithmetic over already-snapshotted line-item subtotals. If Haystack-backed quoting is an actual product goal, it's new scope requiring a new `PricingClient` implementation — not assumed or included here.

## 8. Why one PR

All of Steps 1–5 share the same two services (`RentalPlanService`, `BookingService`) and the same underlying mechanism (`status`/`updatedAt` on `RentalPlan`), and are only meaningfully testable together — fixing the `CONVERTED` bug alone without deriving items/pricing from the plan doesn't produce a working checkout to verify, and B10's revert-to-`DRAFT` only matters in combination with the freshness check it feeds. Splitting these would leave intermediate commits where the core workflow (cart → quote → checkout → deposit) still can't be walked start to finish. Doc updates (Step 6) land in the same PR per this project's "update docs in the same change set" convention, already followed by every SPEC file in this repo.

This does **not** require the web portal to also collapse to one PR — none of their three PRs actually need this Spring PR split further. Their PR 1 (cart persistence) depends only on already-live, already-unwired routes and does not depend on this PR at all; PR 2 and PR 3 both depend on this bundled PR as a whole, not on any sub-slice of it, so there's nothing to gain by splitting Spring's side to match their sequencing.

## 9. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-13 | Initial draft plan — not yet implemented. |
| 0.2.0 | 2026-08-13 | Reconciled against the web portal's independent `temp_web/*` planning docs (B1–B10). Added: `RentalPlanResponse.updatedAt`/`createdAt` (B8, Step 4); item add/remove reverts a `QUOTED` plan to `DRAFT` instead of `409`-locking (B10, new Step 5), including the resulting `SPEC-rental-plan-quote.md` REQ-2/REQ-3 rewrite. Confirmed dropped: `POST /api/pricing/estimate` (B5) — resolved as client-side-computable, no backend endpoint needed, with a day-count-formula-parity note for the frontend. Confirmed no new endpoint for B9 (client-side filtering instead). Corrected two inaccuracies in the web portal's docs: B6 (`PENDING_CONFIRMED` on deposit success) is already implemented, not a gap; and `POST /rentalPlans/{id}/quote` does not reach Haystack today, contrary to `temp_web/Spec-rest-api-reference.md` §8.1. Confirmed day-math fix (Step 2) ships in this PR without holding for frontend readiness — response contract is unchanged so nothing can break, only a live pricing-amount change to flag to the pricing owner. Confirmed single-PR strategy holds regardless of the web portal's own 3-PR split, since none of their PRs require finer-grained Spring slicing (§8). |
| 0.3.0 | 2026-08-13 | Frontend-handoff review: found `plan.md` alone wasn't sufficient to hand to the frontend team — it's an internal HOW document, not a contract. Four concrete gaps found and resolved: (1) `PlanStatus` wire values are uppercase (`DRAFT`/`QUOTED`/`CONVERTED`, via Java `.name()`), contradicting the frontend's own lowercase notation throughout their docs — now called out explicitly. (2) `RestExceptionHandler.handleResponseStatus` collapses all `409`s to one generic `"conflict"` code — Step 3 now introduces `RentalPlanConflictException` (new file) with distinct `quote_not_ready`/`quote_expired` codes instead, mirroring the existing `HaystackException` dedicated-handler pattern; `Files touched` and `Risks` updated. (3) Confirmed `updatedAt`/`createdAt` serialize as ISO-8601 strings (Spring Boot default, no override found in the codebase; precedented by `RecommendationSessionResponse.createdAt`). (4) Added [`api-contract-for-frontend.md`](./api-contract-for-frontend.md) as a separate, literal-JSON handoff doc — kept apart from this HOW plan per decision to not conflate execution planning with the external contract. |
