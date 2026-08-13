# Specification: `POST /api/pricing/estimate` (design only — not built)

| Field | Value |
|-------|--------|
| **Feature** | A standalone, Spring-only price estimate for a set of assets + a date range, with no persisted `RentalPlan`/`Booking` required first |
| **Status** | **Design only. No code exists.** Written 2026-08-13 in response to a web-portal API audit item; see §0 for why this is not the route the index previously removed. |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related specs** | [`SPEC-api-index.md`](./SPEC-api-index.md) §2.5.2, [`SPEC-rental-plan-quote.md`](./SPEC-rental-plan-quote.md) (the other, Haystack-eligible pricing path — see §0), [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) (`Asset.baseDailyRate`, availability query this route may or may not reuse), [`SPEC-spring-proxy-endpoints.md`](./SPEC-spring-proxy-endpoints.md) §3 (records this route as deliberately **not** a Haystack proxy) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |

---

## 0. Why this exists, and why it isn't the route `SPEC-api-index.md` removed

`SPEC-api-index.md` §2.5 previously listed, then removed (2026-08-13), a `POST /api/pricing/estimate` row with the note *"never built, no matching Haystack endpoint to proxy."* That removal was correct for what it described: a placeholder that assumed this route's job was to **proxy** to a Haystack pricing endpoint, which never existed on the FastAPI side either.

This spec is a **different, deliberate requirement**, reusing the same path on purpose: a genuinely new, **Spring-only, non-Haystack** endpoint that lets a caller (the web portal) get a price estimate for a prospective set of items + dates **without** first creating and owning a `RentalPlan`. It has no dependency on `haystack-fast-api` and is not a resurrection of the removed phantom.

It is also distinct from `POST /api/rentalPlans/{id}/quote` (`SPEC-rental-plan-quote.md`), which:

- requires an existing, owned `RentalPlan` with line items already added one at a time (`POST .../items`), and
- **locks** the plan (`status → QUOTED`) as a side effect (REQ-4) — not idempotent, not safe to call speculatively.

`POST /api/pricing/estimate` is meant for the "what would this roughly cost" case — before a customer commits to starting a plan — where locking anything would be wrong.

---

## 1. Outcomes (proposed)

When this feature is correct:

1. A caller can get a price estimate for a set of `{assetId, quantity?}` items and a date range, without creating any persistent record.
2. The response uses the same pricing source as `RentalPlan` line items today (`Asset.baseDailyRate`, per `SPEC-rental-plan-quote.md` Open Question 2) — no separate pricing logic to keep in sync.
3. Calling this endpoint has **no side effects** — nothing is written, no availability hold is taken (unless §3 below is resolved the other way).

---

## 2. Scope (proposed)

### 2.1 In scope

- One route: `POST /api/pricing/estimate`.
- Per-item and total pricing, mirroring `DefaultPricingClient`'s existing `dailyRate × days` math (`SPEC-rental-plan-quote.md` §5, §5.0).
- Multi-item requests in one call (unlike `RentalPlan`'s one-item-at-a-time `POST .../items`).

### 2.2 Out of scope

- Persisting anything — this is a pure computation endpoint, no `RentalPlan`/`Booking`/any entity written.
- Locking or reserving equipment — see §3, the one part of scope not yet decided.
- Haystack/dynamic pricing — same rationale as `RentalPlan`'s Open Question 2 (`SPEC-rental-plan-quote.md`): `haystack-fast-api` pricing isn't ready, and this route is explicitly the non-Haystack path.
- Discounts (`HR-20`-equivalent) — no discount field, matching `SPEC-rental-plan-quote.md` §2.2's existing precedent of excluding `HR-20` from adjacent pricing surfaces.

---

## 3. Open question — needs a decision before implementation (flagged, not resolved)

**Should this route run the same availability/conflict check `POST /api/bookings` does (`BookingItemRepository.findAssetIdsWithOverlappingBooking`, `SPEC-api-index.md` §2.2.1 step 4), or stay purely arithmetic and ignore availability entirely?**

Two considerations, neither conclusive on its own:

- **Purely arithmetic** matches this route's own stated purpose (a quick, side-effect-free "what would this cost" check) and `RentalPlan`'s existing precedent that quoting never blocks or checks availability (`SPEC-rental-plan-quote.md` §2.2, §7 — "two customers can both quote the same equipment for overlapping dates, and nothing stops either of them"). Consistent behavior across both pricing surfaces is a real advantage — a caller wouldn't need to remember that one pricing endpoint checks availability and the other doesn't.
- **Running the same conflict check** would mean an estimate can't quietly return a price for equipment that's actually unavailable over the requested window, which `RentalPlan`'s existing gap (§7 of that spec) already flags as a known, unresolved, customer-facing failure mode. Adding the check here wouldn't fix that gap on `RentalPlan` itself, but it would avoid extending the same failure mode to a second, newer endpoint.

Not resolved here — recorded explicitly so it isn't decided by default/omission, per this project's convention for open items (`SPEC-rental-plan-quote.md` §7). Whoever picks this up should decide before writing `PricingEstimateController`/`PricingEstimateService`, since it changes the method signature (whether an availability-conflict `409` is even a possible response) and the query cost (an extra `BookingItemRepository` call per estimate if enforced).

---

## 4. Proposed contract (draft — not implemented, not verified against running code)

### 4.1 `POST /api/pricing/estimate`

**Request (draft):**

```json
{
  "items": [
    { "assetId": 1 },
    { "assetId": 4 }
  ],
  "startDate": "2026-09-01",
  "endDate": "2026-09-05"
}
```

Mirrors `CreateBookingRequest`'s `items`/`startDate`/`endDate` shape (`SPEC-api-index.md` §2.2.1) rather than inventing a new one, since both describe "a set of assets over a date window." No `rentalPlanId`, `siteAddress`, or `deliveryNotes` — this route creates nothing, so none of `CreateBookingRequest`'s booking-specific fields apply.

**Response (draft, `200`):**

```json
{
  "items": [
    { "assetId": 1, "dailyRate": 450.00, "days": 5, "subtotal": 2250.00 },
    { "assetId": 4, "dailyRate": 220.00, "days": 5, "subtotal": 1100.00 }
  ],
  "totalAmount": 3350.00
}
```

`days`/subtotal math should reuse `PricingClient.priceItem` (`SPEC-rental-plan-quote.md` §5) rather than reimplementing it, so both pricing surfaces can't drift on rounding/day-count convention — same reasoning already applied to `Booking.ACTIVE_STATUSES` being promoted to a shared field to prevent `AssetService`/`BookingService` drift (`SPEC-api-index.md` §2.2.1 step 4).

**Errors (draft, matching existing conventions elsewhere in this index):**

| Condition | HTTP |
|---|---|
| Empty `items`, missing/invalid dates, `endDate` not after `startDate` | `400` |
| Unknown `assetId` | `404` |
| (If §3 resolves to "enforce availability") asset unavailable over the window | `409`, naming the conflicting asset id(s) — same shape as `POST /api/bookings` |

None of the above is final — this section exists to give implementation a concrete starting point, not to lock behavior before §3 is resolved.

---

## 5. Verification (not run — nothing to verify yet)

No checklist here, deliberately — per this project's convention (e.g. `SPEC-rental-plan-quote.md`'s 0.1.0 entry), a verification checklist gets written once there's code to check it against, not before.

---

## 6. Change control

| Version | Date | Notes |
|---------|------|--------|
| 0.1.0 | 2026-08-13 | Initial design-only draft, written in response to a web-portal API audit item distinguishing this deliberate new route from the phantom `POST /api/pricing/estimate` `SPEC-api-index.md` had already removed as never-built. §3's availability-check question captured and explicitly left open, not decided by default. No code written. |
