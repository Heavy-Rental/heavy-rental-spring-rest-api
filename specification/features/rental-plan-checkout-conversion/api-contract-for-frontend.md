# API Contract: Rental Plan Checkout (For Frontend)

| Field | Value |
|-------|--------|
| **Document type** | External-facing API contract (WHAT) — handoff to the web portal team |
| **Status** | Proposed — describes the Spring Boot behavior once [`plan.md`](./plan.md) is implemented, not yet merged |
| **Companion** | [`plan.md`](./plan.md) — the internal HOW/execution plan this contract is generated from |
| **Supersedes** | Where this disagrees with `temp_web/Spec-rental-plan-cart-checkout.md` or `temp_web/Spec-rest-api-reference.md` (both slated for deletion once this work lands), this document wins — see inline notes below for what changed and why |

This is the literal request/response contract for the rental-plan checkout workflow. If you're implementing against this, everything below is what to code against — prose explanations of *why* live in `plan.md`, not here.

---

## 1. Status enum — wire format is UPPERCASE

`RentalPlan.status` serializes via Java's `Enum.name()` — there is no casing transform anywhere in the codebase (confirmed: no `@JsonProperty`/enum-naming-strategy on `PlanStatus`). The wire values are:

```
"DRAFT" | "SAVED" | "QUOTED" | "CONVERTED"
```

**Not** `"draft"`/`"quoted"`/`"converted"` — `Spec-rental-plan-cart-checkout.md`'s Clarifications section uses lowercase throughout; treat that as documentation shorthand, not the real wire value, and code status comparisons against the uppercase strings above. `SAVED` is a declared-but-unused enum value (per that same doc's own Q&A) — it will never appear on a live plan, but don't treat it as invalid input if you ever see it.

## 2. `RentalPlanResponse` — full shape

Returned by `POST /rentalPlans`, `GET /rentalPlans`, `GET /rentalPlans/{id}`, `POST /rentalPlans/{id}/items`, `DELETE /rentalPlans/{id}/items/{itemId}`, `POST /rentalPlans/{id}/quote`.

```json
{
  "id": 55,
  "startDate": "2026-09-01",
  "endDate": "2026-09-05",
  "siteAddress": "20 Jurong Port Road, 619094",
  "status": "QUOTED",
  "totalAmount": 2250.00,
  "items": [
    {
      "id": 101,
      "assetId": 4,
      "assetName": "CAT 320 Excavator",
      "dailyRate": 450.00,
      "subtotal": 2250.00
    }
  ],
  "updatedAt": "2026-08-13T10:30:00",
  "createdAt": "2026-08-13T09:15:00"
}
```

- **New fields (not live today):** `updatedAt`, `createdAt`. Both ISO-8601 local-date-time strings (`YYYY-MM-DDTHH:mm:ss`), no timezone offset — this is Spring Boot's default `LocalDateTime` serialization, confirmed by grepping the codebase for any Jackson date-format override (none exists) and cross-checked against the one other DTO that already exposes a `LocalDateTime` today (`RecommendationSessionResponse.createdAt`), which uses the same format.
- `updatedAt` is repurposed as **"last quoted at."** It's only meaningful once `status == "QUOTED"` — use it to compute the 24-hour quote-validity window: `now - updatedAt <= 24h` means checkout is allowed without re-quoting.
- `totalAmount` is `null` whenever `status != "QUOTED"` — including immediately after an item add/remove reverts a previously-`QUOTED` plan back to `DRAFT` (§3).

## 3. Item add/remove on an already-`QUOTED` plan — no longer `409`

**This is a behavior change from what's live today and from what `Spec-rental-plan-cart-checkout.md` may have assumed was still locked.** `POST /rentalPlans/{id}/items` and `DELETE /rentalPlans/{id}/items/{itemId}` now succeed even when `status == "QUOTED"`:

- The item is added/removed as requested.
- `status` reverts to `"DRAFT"`.
- `totalAmount` becomes `null` (it was for the old item set).
- `updatedAt` refreshes to now.

The response is the same `RentalPlanResponse` shape as §2, just reflecting the reverted state — no follow-up `GET` needed to see the new status. **UI implication:** if you're showing a "Quoted ✓" state and the customer edits the cart, expect the very next response to show `"status": "DRAFT"` and `"totalAmount": null` — treat that as the signal to show "cart changed, get a new quote" rather than diffing old vs. new state yourself.

## 4. `POST /rentalPlans/{id}/quote`

No shape change from what's live today — still returns `RentalPlanResponse` (§2). What's new: this call now reliably refreshes `updatedAt` as part of succeeding (today it doesn't — see `plan.md` Step 1), so re-quoting a stale plan is exactly how you reset the 24-hour window.

## 5. `POST /api/bookings` with `rentalPlanId` — checkout

**Request:**

```json
{
  "rentalPlanId": 55,
  "siteAddress": "20 Jurong Port Road, 619094",
  "deliveryNotes": "Site access via loading bay B"
}
```

- `items`, `startDate`, `endDate` are **optional and ignored** when `rentalPlanId` is present — they're derived server-side from the plan's own records, not from what you send. Don't bother sending them for this flow; if you do, they're silently discarded, not merged or validated against.
- `siteAddress` stays required (`@NotBlank` + 6-digit-postal-code pattern), independent of `rentalPlanId`.

**Success response** — same `BookingResponse` shape already live today (`totalAmount`/`depositAmount`/`remainingBalance` additive fields), with `totalAmount` guaranteed to exactly equal the plan's quoted `totalAmount` at the time of checkout — no independent recomputation. `status: "PENDING_DEPOSIT"`.

**Side effect:** the referenced `RentalPlan`'s `status` becomes `"CONVERTED"` in the same transaction. A subsequent `GET /rentalPlans/{id}` will reflect that, and `POST /rentalPlans` (start a new cart) becomes available again immediately.

## 6. Error codes relevant to this workflow

All error responses share the shape `{"error": "<code>", "message": "<human-readable text>"}`. Codes below are what to branch UI logic on — **not** the `message` string, which can change wording without notice.

| HTTP | `error` code | When | New in this change? |
|---|---|---|---|
| `404` | `not_found` | `rentalPlanId` doesn't exist, or belongs to another customer (same response either way — no way to distinguish "doesn't exist" from "not yours") | No — existing convention |
| `409` | `quote_not_ready` | `rentalPlanId` present but `status != "QUOTED"` (e.g. still `DRAFT`, never quoted) | **Yes** |
| `409` | `quote_expired` | `rentalPlanId` present, `status == "QUOTED"`, but `now - updatedAt > 24h` | **Yes** — route this back to your "Get Quote" flow with a specific message, not a generic error toast |
| `409` | `conflict` | Rare: two concurrent "Proceed to Payment" clicks race on the same plan (optimistic-lock double-submit) | No — pre-existing generic double-submit guard, now also reachable from checkout since this step writes `RentalPlan.status` too |
| `400` | `bad_request` | No `rentalPlanId` **and** no `items`/dates (the pre-existing direct-booking path's own validation) | No — existing convention, only relevant if you're not using `rentalPlanId` |
| `400` | `validation_failed` | `siteAddress` blank or missing a 6-digit postal code | No — existing convention |

`quote_not_ready` and `quote_expired` did not exist before this change and previously would have come back as generic `"error":"conflict"` with only the message text to go on — worth updating any existing error-handling code that might have been written against that assumption.

## 7. `GET /rentalPlans` — no server-side "active plan" filter

There's no query param to fetch "just my current active plan." A customer has at most one non-`CONVERTED` plan at a time (enforced server-side, `SPEC-rental-plan-quote.md` BR-06), so filter client-side: the plan (if any) where `status != "CONVERTED"`.

## 8. Single-item price preview — no new endpoint

There is no `POST /api/pricing/estimate` and none is planned. Compute it client-side:

```
price = asset.baseDailyRate × days
days  = (endDate - startDate in whole days) + 1     // inclusive of both ends
```

- `asset.baseDailyRate` — already returned by `GET /api/equipment` and `GET /api/equipment/{id}`.
- **The `+1` is required.** The backend's own pricing (`DefaultPricingClient`, used both when an item is actually added and when a booking is priced) counts days inclusively. A client-side preview using an exclusive day count (no `+1`) will show a lower number than what actually gets charged once the item is added — worked example: `startDate: "2026-09-01"`, `endDate: "2026-09-05"` → 5 days, not 4.
- This is a pure client-side calculation — no network call, no server round trip, and no risk of drifting from the real price, since both sides read the identical `baseDailyRate` field with no markup/discount logic in between.

---

## Change Log

- 2026-08-13: Initial contract, generated from `plan.md` v0.3.0. Covers status casing, the full `RentalPlanResponse` shape with new `updatedAt`/`createdAt` fields, the item-mutation revert-to-`DRAFT` behavior, the `rentalPlanId` checkout contract, the new `quote_not_ready`/`quote_expired` error codes, and the client-side single-item pricing formula (no `/api/pricing/estimate` endpoint).
