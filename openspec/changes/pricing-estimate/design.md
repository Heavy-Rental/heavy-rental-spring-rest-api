# Design: pricing estimate (draft)

## Approach

1. Thin `PricingEstimateController` → `PricingEstimateService`.
2. Request: `{ items: [{ assetId }], startDate, endDate }` (mirror booking multi-item shape; no plan/site fields).
3. Resolve each asset; `404` if any missing; `400` on empty items / bad dates.
4. Price via injected `PricingClient.priceItem(asset, start, end)` — **do not reimplement** day count or rate source.
5. Response: per-item `dailyRate`, `days`, `subtotal` + `totalAmount`.
6. **No writes** to any entity.

## Availability (open)

| Option | Behavior |
|--------|----------|
| A — arithmetic only | No booking overlap query; consistent with rental-plan quote |
| B — conflict check | Use `BookingItemRepository.findAssetIdsWithOverlappingBooking`; `409` names conflicting asset ids |

Decide before coding; affects signatures and query cost.

## Haystack

None. If dynamic pricing is needed later, that is a **new** change (possibly second `PricingClient` impl), not this estimate’s default path.
