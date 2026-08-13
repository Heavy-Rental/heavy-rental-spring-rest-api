# Contract: Entity & repository catalog (summary)

| Field | Value |
|-------|--------|
| **Capability** | entity-repository |
| **Packages** | `entity`, `repository`, `enums` |
Full historical field tables: git history (former `specification/SPEC-entity-repository.md`). This contract lists the **as-built inventory** and high-signal columns.

## Entities (13)

| Entity | Table | Notes |
|--------|-------|--------|
| `User` | `users` | Auth principal; `name` UNIQUE; `email` login; roles USER/ADMIN/DRIVER |
| `AssetCategory` | `asset_categories` | 4 categories in seed |
| `Asset` | `assets` | Fleet item; rates; capacity/height; condition |
| `AssetImage` | `asset_images` | base64 `image` TEXT; FK asset; also source for haystack-recommender portal `items[].equipment.img` |
| `RentalPlan` | `rental_plan` | customer FK; status DRAFT/SAVED/QUOTED/CONVERTED |
| `RentalPlanRecord` | `rental_plan_records` | plan line items |
| `Booking` | `bookings` | customer, dates, status, totals |
| `BookingItem` | `booking_items` | asset lines; conditions; hours |
| `Payment` | `payments` | deposit/balance; Stripe fields |
| `DeliveryRecord` | `delivery_records` | booking + driver |
| `ReturnRecord` | `return_records` | booking + driver |
| `AIRecommendation` | `ai_recommendations` | S2b session + haystack handles |
| `RecommendationItem` | `recommendation_items` | optional ranked lines (not required on S2b submit) |

## AIRecommendation S2b columns (as-built)

`ingest_id`, `haystack_user_id`, `idempotency_key`, `correlation_id`, `tentative_start_date`, `tentative_end_date`, budget amount/currency/source, `warnings`, `confidence_score`, plus prompt/summary/status/user/created_at.

## Enums

| Enum | Values |
|------|--------|
| `ConditionType` | EXCELLENT, GOOD, FAIR, NEEDS_REPAIR |
| `User.UserRole` | USER, ADMIN, DRIVER |
| `RentalPlan.PlanStatus` | DRAFT, SAVED, QUOTED, CONVERTED |
| `Booking.BookingStatus` | PENDING_DEPOSIT, PENDING_CONFIRMED, CONFIRMED, MOBILISED, COMPLETED, CANCELLED |
| `Payment.PaymentType` | DEPOSIT, BALANCE, FULL_PAYMENT |
| `Payment.PaymentStatus` | PENDING, SUCCESS, FAIL |
| `AIRecommendation.RecommendationStatus` | GENERATED, ACCEPTED, REJECTED, EXPIRED |

## Repositories

Spring Data JPA repositories under `repository/` (≈12 interfaces) with derived `findBy…` methods. Prefer repository queries over inventing bidirectional entity graphs.

## Related

- Seed data: [`../../seed-data/`](../../seed-data/)  
- Haystack session use: [`../../haystack-recommender/`](../../haystack-recommender/)
