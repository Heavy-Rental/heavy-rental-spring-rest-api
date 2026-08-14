# Design: rental-plan checkout conversion (draft)

## Approach

All steps share `RentalPlanService` / `BookingService` and `RentalPlan.status` / `updatedAt`. Ship as one implementation PR (not this docs change).

1. **Timestamps.** `create()` sets `createdAt`. `requestQuote()` sets `updatedAt` immediately before the `QUOTED` save. `updatedAt` is last-quoted-at.
2. **Inclusive days.** `BookingService.createBooking` uses `ChronoUnit.DAYS.between(start, end) + 1` on the no-plan path, matching `DefaultPricingClient`. Response field types stay the same; live totals shift by one day’s rate.
3. **Plan-backed create.** When `CreateBookingRequest.rentalPlanId` is present:
   - Load plan; non-owner or missing → `404` (same as `loadOwnedPlan`).
   - Status ≠ `QUOTED` → `409` `quote_not_ready`.
   - `Duration.between(updatedAt, now) > 24h` → `409` `quote_expired`.
   - Build `BookingItem`s from `RentalPlanRecord`s (copy asset, dailyRate, subtotal). Use plan dates and frozen `totalAmount`.
   - Re-run `findAssetIdsWithOverlappingBooking` (plans never hold availability).
   - After booking save, set plan `CONVERTED` in the same transaction.
   - Request `items` / `startDate` / `endDate` are ignored (not merged).
   - `siteAddress` stays required (`@NotBlank` + 6-digit postal code).
4. **Conflict codes.** Add `RentalPlanConflictException(code, message)` and a `RestExceptionHandler` mapping to `409` `{ "error": code, "message": ... }`. Do not reuse generic `conflict` for ready/expired. `@Version` double-submit stays `conflict`.
5. **Response fields.** Add `createdAt` / `updatedAt` (`LocalDateTime`, ISO-8601 no offset) to `RentalPlanResponse`.
6. **Unlock quoted carts.** On add/remove, if status is `QUOTED`: apply the mutation, set `DRAFT`, `totalAmount = null`, refresh `updatedAt`. `DRAFT`/`SAVED` unchanged.

## Files (when implemented)

| File | Change |
|------|--------|
| `RentalPlanService` | timestamps; revert-to-`DRAFT` on quoted item mutation |
| `BookingService` | ownership/status/freshness; derive from plan; inclusive days; `CONVERTED` |
| `RentalPlanConflictException` | new |
| `RestExceptionHandler` | dedicated 409 handler |
| `CreateBookingRequest` | `items`/dates optional when `rentalPlanId` present |
| `RentalPlanResponse` | `createdAt`, `updatedAt` |

No entity/schema change — columns already exist.

## Risks

- Frontend must handle `quote_expired` by re-quoting, not a generic toast.
- Callers that send both `rentalPlanId` and `items` will have `items` ignored.
- Inclusive-day fix changes live checkout totals immediately.
- Revert-to-`DRAFT` reverses as-built FR-RP-002 `409` lock — living spec updates land with the implementation PR.

## Portal B1–B10 (reconciled)

| Item | This change |
|------|-------------|
| B1–B3 timestamps, day math, derive booking | In |
| B4 preview endpoint | Not needed — `POST .../quote` is the freeze |
| B5 `POST /api/pricing/estimate` | **Dropped** — see [`../../pricing-estimate/`](../../pricing-estimate/) if a server estimate is still wanted |
| B6 deposit → `PENDING_CONFIRMED` | Already as-built in `PaymentWebhookService` |
| B7 quote refreshes `updatedAt` | In (step 1) |
| B8 response timestamps | In (step 5) |
| B9 server filter for active plan | **No** — client filters `status != CONVERTED` |
| B10 quoted item mutation → `DRAFT` | In (step 6) |

`POST /api/rentalPlans/{id}/quote` remains Spring-only (`PricingClient` / `DefaultPricingClient`). Haystack quoting is a later change.

## Why one implementation PR

Conversion, freshness, and revert-to-`DRAFT` are only testable together on the cart → quote → checkout walk. Do not split those service writes across PRs.
