# Specification: Stripe Payments (Deposit, Webhook, Balance Auto-Charge)

| Field | Value |
|-------|--------|
| **Feature** | 30% deposit (client-initiated) + 70% balance (system-initiated, off-session, 1 day before rental start) |
| **Status** | As-built, now rebased onto `develop` (branch `hr-27-payment-checkout`, local, not pushed as of 2026-08-11). `./mvnw compile`/`test` pass against a live Postgres. **Payment-state tracking is partially deferred** — `Booking.paidStatus` was removed during the rebase and several transitions this feature used to perform no longer happen; see §10. Not yet verified against a real Stripe sandbox — see §11 |
| **Module** | `heavy-rental-spring-rest-api` |
| **Endpoints** | `POST /api/payments/deposit-intent`, `POST /api/payments/webhook` |
| **Depends on** | [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) (`Booking`, `Payment`, `User`) · [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) (JWT principal) · a Booking module to create the `Booking` a deposit attaches to — **`POST /api/bookings` added 2026-08-11; see [`SPEC-api-index.md`](./SPEC-api-index.md) §2.2.1 and §10 item 4 below** |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) · [`SPEC-api-index.md`](./SPEC-api-index.md) §2.2/§2.4 (this feature's routes in the full endpoint surface; §2.4 also covers the CORS fix in §10 item 7) |
| **Related code** | `PaymentController`, `PaymentService`, `StripeWebhookController`, `PaymentWebhookService`, `BalanceChargeSchedulerService`, `CurrentUserService`, `StripeConfig`, `SecurityConfig`, entities `Booking`/`Payment`/`User`, DTOs `CreateDepositIntentRequest`/`PaymentIntentResponse` |
| **Related docs** | §12 below (frontend integration guidance, written in-file rather than as a separate handoff doc — see that section's note) · [`STRIPE_WEBHOOK_TESTING.md`](../STRIPE_WEBHOOK_TESTING.md) (`stripe listen` walkthrough — referenced but was never actually committed to this repo; treat as not existing until someone writes it) |

This document is the **single source of truth** for the Stripe payment integration: deposit PaymentIntent creation, the webhook that confirms payment outcomes, and the daily cron that auto-charges the remaining balance off-session.

---

## 1. Outcomes

When this feature is correct:

1. A customer can pay a 30% deposit against their own booking; the card they use is saved (via Stripe's `setup_future_usage=off_session`) for a later unattended charge.
2. A daily job finds every booking starting tomorrow that's still only deposit-paid and charges the remaining 70% off-session, with no customer interaction.
3. A failed balance charge is never retried automatically — it's flagged for manual admin follow-up.
4. Stripe webhook events are the source of truth for payment outcome, verified by signature, and idempotent against redelivery and against the cron's own synchronous result.
5. Deposit amounts are always derived server-side from the booking, never trusted from the client.

---

## 2. Process flow

### 2.1 Deposit (client-initiated)

```text
Client                                    API                                   Stripe
  │  POST /api/payments/deposit-intent
  │  { bookingId }  (Bearer access JWT)
  │────────────────────────────────────────►│
  │                                          │  verify ownership (booking.customer == jwt)
  │                                          │  verify booking.paidStatus == UNPAID
  │                                          │  resolve/create Stripe Customer ─────────►│
  │                                          │◄──────────────────────────── customer.id ─│
  │                                          │  create PaymentIntent
  │                                          │    amount = booking.depositAmount
  │                                          │    setup_future_usage = off_session ─────►│
  │                                          │◄──────────────── PaymentIntent (PENDING) ─│
  │                                          │  save Payment row (status=PENDING)
  │◄──── { clientSecret, paymentIntentId } ──│
  │
  │  stripe.confirmPayment(clientSecret)  (browser, Stripe.js / PaymentElement)
  │─────────────────────────────────────────────────────────────────────────────────────►│
  │                                                                    (see §2.2 webhook) │
```

### 2.2 Webhook (Stripe → API)

```text
Stripe                                    API
  │  POST /api/payments/webhook
  │  Stripe-Signature: t=...,v1=...
  │  body: payment_intent.succeeded | payment_intent.payment_failed
  │──────────────────────────────────────────►│
  │                                            │  Webhook.constructEvent(payload, sig, secret)
  │                                            │  → 400 if signature invalid
  │                                            │  look up Payment by stripePaymentIntentId
  │                                            │  no-op if not found or already non-PENDING
  │                                            │  on succeeded:
  │                                            │    Payment.status = SUCCESS, paidAt = now
  │                                            │    Payment.stripePaymentMethodId = intent.paymentMethod
  │                                            │    if DEPOSIT: Booking.paidStatus = DEPOSIT
  │                                            │    if BALANCE: Booking.paidStatus = FULL, remainingBalance = 0
  │                                            │  on payment_failed:
  │                                            │    Payment.status = FAIL, failureReason = ...
  │                                            │    if BALANCE: requiresManualFollowUp = true (Payment + Booking)
  │◄──── 200 ──────────────────────────────────│
```

### 2.3 Balance auto-charge (system-initiated, daily)

```text
BalanceChargeSchedulerService                                                    Stripe
  │  @Scheduled(cron="0 0 2 * * *", zone=Asia/Singapore)  — fires 02:00 SGT daily
  │
  │  tomorrow = today + 1
  │  due = bookings WHERE startDate = tomorrow
  │                   AND paidStatus = DEPOSIT
  │                   AND status <> CANCELLED
  │
  │  for each booking (own transaction, processOne):
  │    re-check paidStatus == DEPOSIT (race guard)
  │    skip if ANY BALANCE Payment row already exists for this booking
  │      (non-FAIL = already succeeded/in-flight; FAIL = the one allowed attempt already used)
  │    booking.balanceChargeAttemptedAt = now   ← audit marker, set before calling Stripe
  │    PaymentService.chargeBalanceOffSession(booking):
  │      find the SUCCESS DEPOSIT Payment for this booking
  │        → its stripeCustomerId / stripePaymentMethodId is what gets charged
  │      create Payment row (BALANCE, PENDING)
  │      create+confirm PaymentIntent, off_session=true, confirm=true ─────────────────►│
  │      ├─ success → Payment=SUCCESS, Booking.paidStatus=FULL, remainingBalance=0     │
  │      └─ StripeException (declined / expired / auth_required) → Payment=FAIL,       │
  │           requiresManualFollowUp=true, Booking.needsManualFollowUp=true,           │
  │           NO RETRY                                                    ◄────────────│
```

---

## 3. Scope

### 3.1 In scope

- `POST /api/payments/deposit-intent` — deposit PaymentIntent creation with card-saving for later off-session use
- `POST /api/payments/webhook` — signature-verified, idempotent event handling for `payment_intent.succeeded` / `payment_intent.payment_failed`
- Daily cron (`BalanceChargeSchedulerService`) that charges the 70% balance off-session, one day before `Booking.startDate`
- Single-attempt failure policy with an in-app "needs manual follow-up" flag (no auto-retry, no email/Slack notification)
- Server-side-only amount computation (deposit rate lives in `BookingService`, not this feature — see [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) once a Booking SPEC exists)
- Ownership enforcement tying the JWT principal to the booking's customer (`CurrentUserService`), with `ROLE_ADMIN` bypass

### 3.2 Out of scope

- Booking creation itself (`BookingController`/`BookingService`) — a prerequisite, documented as code only, not yet as a SPEC file
- Retry logic for failed balance charges — deliberately never auto-retried (§7)
- Email/Slack/SMS notification of admins on a failed balance charge — deliberately in-app-flag-only for now
- React frontend implementation — see [`FRONTEND_CHANGES.md`](../FRONTEND_CHANGES.md) for the handoff spec; no frontend code was changed as part of this feature
- Full/one-shot payments (`PaymentType.FULL_PAYMENT` exists on the entity and in seed data, but no endpoint creates one)
- PayNow or any non-card Stripe payment method
- Database migrations (Flyway/Liquibase) — new columns rely on `ddl-auto=update`, see §6.4

---

## 4. Configuration

### 4.1 `application.properties`

```properties
stripe.api.key=${STRIPE_API_KEY:YOUR_STRIPE_SECRET_KEY_HERE}
stripe.publishable.key=${STRIPE_PUBLISHABLE_KEY:YOUR_STRIPE_PUBLISHABLE_KEY_HERE}
stripe.webhook.secret=${STRIPE_WEBHOOK_SECRET:whsec_YOUR_WEBHOOK_SECRET_HERE}
```

| Property | Env var | Used by |
|---|---|---|
| `stripe.api.key` | `STRIPE_API_KEY` | `StripeConfig` (`@PostConstruct` sets `Stripe.apiKey`) — global SDK config, secret key |
| `stripe.publishable.key` | `STRIPE_PUBLISHABLE_KEY` | Not consumed server-side; exists for the frontend to read via its own env var (see `FRONTEND_CHANGES.md` §2) |
| `stripe.webhook.secret` | `STRIPE_WEBHOOK_SECRET` | `StripeWebhookController` (`@Value`, used in `Webhook.constructEvent`) |

`STRIPE_WEBHOOK_SECRET` comes from `stripe listen`'s printed `whsec_...` value in local dev (see [`STRIPE_WEBHOOK_TESTING.md`](../STRIPE_WEBHOOK_TESTING.md)), or from the Stripe Dashboard's webhook endpoint config in a deployed environment.

### 4.2 `config/StripeConfig.java`

```java
@Configuration
public class StripeConfig {
    @Value("${stripe.api.key}")
    private String secretKey;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }
}
```
This is process-global SDK configuration (`com.stripe.Stripe.apiKey`), not per-request — every `PaymentIntent.create(...)` / `Customer.create(...)` call anywhere in the app uses this key implicitly.

### 4.3 Currency and deposit rate

- Currency is hardcoded `"sgd"` in `PaymentService` (both deposit and balance PaymentIntents) — not configurable today.
- The deposit rate (30%) is **not** defined in this feature — it lives in `BookingService` at booking-creation time, and is persisted onto `Booking.depositAmount`/`Booking.remainingBalance`. `PaymentService` only ever reads those persisted amounts; it never computes a rate itself. This is deliberate: the rate must be defined in exactly one place.

---

## 5. Requirements

### Requirement 1: Deposit PaymentIntent creation

**User story:** As a customer, I want to pay a 30% deposit on my booking so it becomes confirmed, and have my card saved so I'm not asked to pay again for the balance.

#### Acceptance criteria

1. **GIVEN** a booking owned by the caller with `paidStatus = UNPAID`
   **WHEN** `POST /api/payments/deposit-intent` with `{ "bookingId": <id> }` and a valid access Bearer
   **THEN** `200 OK` with `{ clientSecret, paymentIntentId }`, a `Payment` row is created (`paymentType=DEPOSIT`, `status=PENDING`, `amount=booking.depositAmount`), and the Stripe PaymentIntent has `setup_future_usage=off_session`.

2. **GIVEN** a booking whose `paidStatus` is already `DEPOSIT` or `FULL`
   **WHEN** the same endpoint is called
   **THEN** `409 Conflict` ("Booking deposit has already been initiated or paid") — no new PaymentIntent or Payment row is created.

3. **GIVEN** a booking that does not belong to the caller and the caller is not `ROLE_ADMIN`
   **WHEN** the endpoint is called
   **THEN** `403 Forbidden`.

4. **GIVEN** a `bookingId` that doesn't exist
   **WHEN** the endpoint is called
   **THEN** `404 Not Found`.

5. **GIVEN** the caller's `User` row has no `stripeCustomerId` yet
   **WHEN** the endpoint is called
   **THEN** a Stripe `Customer` is created and `User.stripeCustomerId` is persisted; subsequent calls for the same user reuse it instead of creating a duplicate Customer.

6. **GIVEN** any request body
   **THEN** the charged amount is always `booking.depositAmount` read from the database — **the client cannot supply or influence the amount.**

### Requirement 2: Webhook confirms payment outcome

**User story:** As the system, I need Stripe's async confirmation of a payment's real outcome, verified authentically, applied exactly once.

#### Acceptance criteria

1. **GIVEN** a request to `POST /api/payments/webhook` with an invalid or missing `Stripe-Signature` header
   **WHEN** verified against `stripe.webhook.secret`
   **THEN** `400 Bad Request`, no state changes.

2. **GIVEN** a valid `payment_intent.succeeded` event for a `Payment` currently `PENDING`
   **THEN** that `Payment` is set `SUCCESS`, `paidAt` is set, `stripePaymentMethodId`/`stripeChargeId` are captured from the intent; if `paymentType=DEPOSIT`, `Booking.paidStatus → DEPOSIT`; if `paymentType=BALANCE`, `Booking.paidStatus → FULL` and `remainingBalance → 0`.

3. **GIVEN** a valid `payment_intent.payment_failed` event for a `Payment` currently `PENDING`
   **THEN** that `Payment` is set `FAIL` with `failureReason` from the Stripe error; if `paymentType=BALANCE`, also sets `requiresManualFollowUp=true` on the `Payment` and `needsManualFollowUp=true` on the `Booking`.

4. **GIVEN** an event whose PaymentIntent ID doesn't match any `Payment`, or whose matching `Payment` is no longer `PENDING` (already resolved by an earlier delivery, or by the cron's own synchronous result)
   **THEN** the handler no-ops — **no exception, no duplicate state change.** (Idempotency — see §7.2.)

5. **GIVEN** any event type other than the two above
   **THEN** the handler returns `200` without processing it.

### Requirement 3: Daily off-session balance charge

**User story:** As the system, I need to automatically collect the remaining 70% one day before a rental starts, without the customer present, and never charge twice or retry a failed attempt.

#### Acceptance criteria

1. **GIVEN** a `CONFIRMED`/non-cancelled booking with `startDate = tomorrow` and `paidStatus = DEPOSIT`
   **WHEN** the 02:00 Asia/Singapore cron fires
   **THEN** an off-session PaymentIntent is created for `booking.remainingBalance`, using the `stripeCustomerId`/`stripePaymentMethodId` captured from that booking's successful `DEPOSIT` Payment.

2. **GIVEN** the off-session charge succeeds
   **THEN** the `BALANCE` `Payment` is `SUCCESS`, `Booking.paidStatus → FULL`, `remainingBalance → 0` (this happens synchronously in `chargeBalanceOffSession`; the webhook in Requirement 2 is a second, idempotent path to the same result if it lands first or is redelivered).

3. **GIVEN** the off-session charge fails for any reason (declined, expired card, `authentication_required`, etc.)
   **THEN** the `BALANCE` `Payment` is `FAIL` with `requiresManualFollowUp=true` and a coarse `manualFollowUpReason` (`card_declined` / `expired_card` / `authentication_required` / `other`); `Booking.needsManualFollowUp = true`. **No retry is attempted, then or on any later cron run.**

4. **GIVEN** a booking that already has any `BALANCE` `Payment` row (success, pending, or failed)
   **WHEN** the cron runs again (including a second run on the same day, or after a restart)
   **THEN** that booking is skipped — never double-charged, and a previously-failed attempt is never retried.

5. **GIVEN** a booking with `status = CANCELLED`
   **THEN** it is excluded from the cron's query entirely, regardless of `paidStatus`.

6. **GIVEN** a booking that has no successful `DEPOSIT` `Payment` row at all (shouldn't happen if Requirement 1 is enforced correctly, but is a defensive check)
   **THEN** `chargeBalanceOffSession` throws `IllegalStateException` rather than attempting a charge with no saved payment method.

---

## 6. API contracts

### 6.1 `POST /api/payments/deposit-intent`

```http
POST /api/payments/deposit-intent HTTP/1.1
Authorization: Bearer <access-jwt>
Content-Type: application/json

{ "bookingId": 3 }
```

**DTO:** `CreateDepositIntentRequest(Long bookingId)`

**Success `200` — `PaymentIntentResponse`:**

| Field | Type | Description |
|-------|------|-------------|
| `clientSecret` | string | Passed to Stripe.js / `stripe.confirmPayment` client-side |
| `paymentIntentId` | string | Stripe PaymentIntent ID, also stored on the `Payment` row |

**Errors:**

| HTTP | Cause |
|------|-------|
| `404` | Booking not found |
| `403` | Caller doesn't own the booking and isn't `ROLE_ADMIN` |
| `409` | Booking `paidStatus` isn't `UNPAID` |
| `502` | Stripe API call itself failed (`StripeException`) — message wrapped as `"Stripe error: ..."` |

### 6.2 `POST /api/payments/webhook`

```http
POST /api/payments/webhook HTTP/1.1
Stripe-Signature: t=1699999999,v1=...
Content-Type: application/json

{ "id": "evt_...", "type": "payment_intent.succeeded", "data": { "object": { ... } } }
```

No response body (`200 OK` / `400 Bad Request` for signature failure). **No Authorization header** — see §7.1 for why this route is `permitAll` at the Spring Security layer.

There is **no client-facing endpoint for the balance charge** — it is exclusively triggered by `BalanceChargeSchedulerService`, by design.

---

## 7. Design

### 7.1 Components

| Concern | Location |
|---------|----------|
| Deposit HTTP | `controller/PaymentController.java` |
| Deposit + balance-charge orchestration | `service/PaymentService.java` |
| Webhook HTTP (raw body, signature check) | `controller/StripeWebhookController.java` |
| Webhook event application | `service/PaymentWebhookService.java` |
| Daily cron | `service/BalanceChargeSchedulerService.java` |
| Ownership / JWT → User resolution | `service/CurrentUserService.java` (shared with the Booking module) |
| Stripe SDK global config | `config/StripeConfig.java` |
| Security matcher for the webhook route | `config/SecurityConfig.java` |
| DTOs | `dto/CreateDepositIntentRequest.java`, `dto/PaymentIntentResponse.java` |

### 7.2 Idempotency (why every write checks state first)

Three independent triggers can all attempt to move the same `Payment` row from `PENDING` to a terminal state:
1. The synchronous result of `PaymentIntent.create(...)` inside `chargeBalanceOffSession` (balance charge only — the deposit flow's PaymentIntent is confirmed client-side, so only the webhook resolves it).
2. The webhook (`payment_intent.succeeded` / `payment_intent.payment_failed`), which Stripe may also **redeliver**.
3. A second cron run finding the same booking again (crash/restart, manual re-trigger, overlapping schedules).

Guards in place:
- `PaymentWebhookService.handle` only acts on a `Payment` that is currently `PENDING` — anything else is a no-op (Requirement 2.4).
- `BalanceChargeSchedulerService.processOne` skips a booking if **any** `BALANCE` `Payment` row already exists for it, regardless of that row's status (Requirement 3.4) — this is also what enforces the single-attempt policy, since a `FAIL` row counts as "already handled."

### 7.3 Off-session "save card, charge later" pattern

1. **Deposit** (`createDepositPaymentIntent`): PaymentIntent created with `customer=<Stripe Customer>` and `setup_future_usage=off_session`. When the customer confirms it client-side (with any payment method), Stripe automatically attaches that payment method to the Customer for reuse.
2. **Balance** (`chargeBalanceOffSession`): reads `stripeCustomerId`/`stripePaymentMethodId` off the booking's successful `DEPOSIT` `Payment` row, then creates a PaymentIntent with `.setPaymentMethod(...)`, `.setOffSession(true)`, `.setConfirm(true)` — charges immediately, no client involved. A `CardException`/`StripeException` here (including Stripe's `authentication_required` — some cards require 3DS even off-session, which cannot be completed without the customer present) is caught and converted into the manual-follow-up state, never retried.

### 7.4 Entity fields added for this feature

(Full entity reference: [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) — not yet updated with these; treat this table as the addendum until that doc is refreshed.)

| Entity | Field | Column | Type | Purpose |
|---|---|---|---|---|
| `User` | `stripeCustomerId` | `stripe_customer_id` | `String`, nullable | One Stripe Customer per user, reused across all their bookings/payments |
| `Payment` | `stripePaymentMethodId` | `stripe_payment_method_id` | `String`, nullable | Captured after deposit confirmation; read by the balance-charge cron |
| `Payment` | `requiresManualFollowUp` | `requires_manual_follow_up` | `boolean`, `columnDefinition="boolean default false"` | Set on a `FAIL`ed `BALANCE` payment; orthogonal to `status` |
| `Payment` | `manualFollowUpReason` | `manual_follow_up_reason` | `String`, nullable | Coarse bucket: `card_declined` / `expired_card` / `authentication_required` / `other` |
| `Booking` | `balanceChargeAttemptedAt` | `balance_charge_attempted_at` | `LocalDateTime`, nullable | Audit marker set by the cron just before calling Stripe (not itself the idempotency guard — see §7.2) |
| `Booking` | `needsManualFollowUp` | `needs_manual_follow_up` | `boolean`, `columnDefinition="boolean default false"` | Mirrors `Payment.requiresManualFollowUp` so booking lists can filter without a join |

The two `boolean` columns use an explicit `columnDefinition` with a `default false` so `ddl-auto=update`'s `ALTER TABLE ... ADD COLUMN` doesn't fail against an already-populated table (a plain `NOT NULL` addition with no default errors on non-empty Postgres tables).

### 7.5 New repository methods

| Repository | Method | Used by |
|---|---|---|
| `PaymentRepository` | `Optional<Payment> findByStripePaymentIntentId(String)` | `PaymentWebhookService` — look up the `Payment` an incoming event belongs to |
| `BookingRepository` | `List<Booking> findByStartDateAndPaidStatusAndStatusNot(LocalDate, PaidStatus, BookingStatus)` | `BalanceChargeSchedulerService` — the daily sweep query |

---

## 8. Security

| Route | Rule | Why |
|---|---|---|
| `POST /api/payments/deposit-intent` | `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` (falls through to the existing catch-all in `SecurityConfig`) | Standard JWT auth; ownership is then enforced in `PaymentService` via `CurrentUserService.assertOwnerOrAdmin`, not at the matcher level |
| `POST /api/payments/webhook` | `permitAll()` (explicit matcher added in `SecurityConfig`, before the catch-all) | Stripe cannot present a JWT; the `Stripe-Signature` check inside `StripeWebhookController` **is** the authentication for this route |

`StripeWebhookController` reads the request body as a raw `String` rather than a parsed DTO — `Webhook.constructEvent` verifies an HMAC over the **exact bytes** Stripe signed, so a parsed-then-reserialized body would fail verification.

`PaymentController` never accepts a client-supplied amount (Requirement 1.6) — this closed a real gap in the original skeletal implementation, which trusted `request.amount()` directly.

---

## 9. Key decisions

| Decision | Rationale |
|----------|-----------|
| Daily cron sweep, not per-booking Quartz scheduling | No new dependency/jobstore; correctness comes from re-scanning the DB each run rather than precise trigger timing — acceptable since "one day before" doesn't need to-the-second precision |
| Single balance-charge attempt, no auto-retry | User decision: a failed off-session charge (often a hard decline or 3DS requirement) needs human judgment, not blind retries close to the rental start date |
| In-app flag only for manual follow-up, no email/Slack | User decision: no notification infra exists in either repo yet; standing one up was explicitly deferred |
| Cron timezone `Asia/Singapore` | User decision: rentals and the business operate in Singapore time, so "one day before start" must be evaluated in that timezone regardless of server locale |
| `setup_future_usage=off_session` on the deposit intent (not a separate `SetupIntent`) | Standard Stripe pattern — confirms the deposit charge and saves the payment method in a single customer-facing step |
| Deposit rate defined once, in `BookingService`, not here | User instruction: business logic (including money math) must live in exactly one place in the Spring service layer — `PaymentService` only ever reads `Booking.depositAmount`, never computes it |
| Ownership check inside the transactional service method, not the controller | `booking.customer` is a lazy `@ManyToOne` and `spring.jpa.open-in-view=false`; touching it outside an open transaction would throw `LazyInitializationException` |
| `Payment.requiresManualFollowUp` as a boolean flag, not a new `PaymentStatus` enum value | Keeps `status` meaning "the raw Stripe outcome" (`PENDING`/`SUCCESS`/`FAIL`) undisturbed for existing `findByStatus` callers; follow-up is an orthogonal admin-workflow concern |

---

## 10. Known gaps / not yet built

1. **Runtime-verified only up to compile/test, not a real payment.** `./mvnw compile` and `./mvnw test` both pass against a live Postgres as of the 2026-08-11 rebase onto `develop` — an improvement over the original "no Docker/DB available" state — but nothing here has been exercised against the real Stripe sandbox yet (no `stripe listen`, no real `PaymentIntent` confirmed). `STRIPE_WEBHOOK_TESTING.md`, referenced throughout this doc, was never actually committed to this repo — treat it as a walkthrough that still needs writing, not a resource that exists.
2. **No automated tests** for this feature (contrast with `AuthenticationIntegrationTest`, referenced from `SPEC-auth-login-logout.md` §8.2). `SPEC-tests.md` should be updated once tests exist.
3. **`data.sql` payment rows are placeholder Stripe IDs** (`cus_AlexTan001`, `pm_AlexTan001card`, etc.) — they exercise the DB-lookup code paths (e.g. `chargeBalanceOffSession`'s deposit-payment lookup) but will not work against the real Stripe API, since they aren't real Stripe objects. Genuine off-session testing requires running the real deposit flow first to mint real `cus_`/`pm_` IDs.
4. ~~**No Booking SPEC file yet, and no create endpoint at all.**~~ **Fixed 2026-08-11**: `POST /api/bookings` now exists — full contract in [`SPEC-api-index.md`](./SPEC-api-index.md) §2.2.1 (still no standalone Booking SPEC file, but the create contract is documented there). This was the single biggest blocker to a real end-to-end deposit flow, and it's closed: verified against a running instance by creating a real booking and feeding its real `bookingId` straight into `POST /api/payments/deposit-intent`, which correctly reached Stripe and failed only on the placeholder API key. One thing to know if you're creating test bookings yourself: the new endpoint rejects double-booking the same asset over overlapping dates with `409`, using the same availability check `GET /api/equipment`'s `available` flag already relies on.
5. **`Booking.paidStatus` was removed, and this feature's code hasn't been reconciled with what replaced it.** During the 2026-08-11 rebase onto `develop`, it surfaced that a `develop` commit (`8bdf067`, "remove paidStatus, amend bookingStatus") had already deleted the separate `Booking.PaidStatus` field (`UNPAID`/`DEPOSIT`/`FULL`) this feature was built against, folding payment state into `BookingStatus` instead (`PENDING_DEPOSIT` → `PENDING_CONFIRMED` → `CONFIRMED` → ...). Per explicit direction, `develop`'s model won and this feature's `paidStatus` usages were deleted rather than the field being reinstated — each removal is marked `// TODO(stripe-refactor)` in code. Concretely, as of this rebase:
   - **`createDepositPaymentIntent` has no re-initiation guard.** Requirement 1.2's `409 Conflict` on an already-paid booking (`PaymentService.java`, previously `if (booking.getPaidStatus() != UNPAID)`) is gone. Calling `deposit-intent` repeatedly on the same booking will create duplicate `PaymentIntent`s and `Payment` rows today.
   - **A successful payment no longer updates the booking.** Requirement 2.2's `Booking.paidStatus → DEPOSIT`/`→ FULL` transitions (`PaymentWebhookService.applySucceeded`) and Requirement 3.2's equivalent in `PaymentService.chargeBalanceOffSession` are both gone. A paid deposit updates the `Payment` row only; the `Booking` row's status is untouched.
   - **The daily balance-charge cron (§2.3, Requirement 3) is disabled.** `BalanceChargeSchedulerService.chargeBalancesDueTomorrow()`'s sweep query depended on the same removed field (`findByStartDateAndPaidStatusAndStatusNot`, itself deleted from `BookingRepository`); the method is stubbed to always find zero bookings. `processOne(bookingId)` still runs and still does the actual off-session charge if invoked directly (its own `paidStatus` guard was also removed, not replaced), so manual/test invocation per §11.1 still exercises the charge logic — it just never fires automatically.
   - **Follow-up work, not done here:** rebuild these three behaviors against `Booking.BookingStatus` instead of a separate `paidStatus` (e.g., guard on `status == PENDING_DEPOSIT`, transition to `PENDING_CONFIRMED`/`CONFIRMED` on success, requery the cron's sweep off `BookingStatus`) — this was deliberately deferred rather than done as part of reconciling the rebase, so it hasn't happened yet.
6. **React frontend not implemented** — see §12 for exactly what needs to change and why it's currently blocked on gap #4 above. The frontend's simulated auth (never validated server-side against this backend) also blocks real end-to-end browser testing until real login is exercised against a running instance of this API.
7. ~~**CORS is not configured** on this backend at all (no `CorsConfigurationSource` bean anywhere) — blocks browser-based frontend calls to *any* route, not just payments.~~ **Fixed 2026-08-11**: `SecurityConfig` now wires a real `CorsConfigurationSource` bean (new `CorsProperties`, `app.cors.allowed-origins`/`APP_CORS_ALLOWED_ORIGINS`, defaults to the local Vite dev/preview origins), verified against a running instance — allowed origins get `200`+`Access-Control-Allow-Origin` on preflight, disallowed origins get `403`. See [`SPEC-api-index.md`](./SPEC-api-index.md) §2.4 for the full note. **Still open:** the default only covers local dev — deployment must set `APP_CORS_ALLOWED_ORIGINS` to the real deployed frontend origin(s), nothing here guesses that value.

---

## 11. Verification

### 11.1 Checklist

**Updated 2026-08-11 — three items below are struck through and replaced.** They asserted behavior against the now-removed `Booking.paidStatus` field (§10 item 5) and can no longer pass as originally written; each has a note on what to check instead until the deferred refactor happens.

- [x] `./mvnw clean compile` from `heavy-rental-spring-rest-api/` builds clean — verified 2026-08-11 post-rebase, plus `./mvnw test` (existing `AuthenticationIntegrationTest`/`RestApiApplicationTests`) against a live Postgres
- [x] Deposit, partial: `POST /api/bookings` → real `bookingId` → `POST /api/payments/deposit-intent` — verified 2026-08-11, reaches Stripe correctly (fails only on the placeholder API key, no real Stripe credentials in this environment)
- [ ] Deposit, remainder: confirm with Stripe test card `4242 4242 4242 4242` via Stripe.js, confirm `Payment.status → SUCCESS` ~~and `Booking.paidStatus → DEPOSIT`~~ after the webhook fires — still needs a real `STRIPE_API_KEY`. *(Updated: only the `Payment` row transition is checkable today — the `Booking` side no-ops, see §10 item 5.)*
- [ ] ~~Deposit re-call after success → `409`~~ *(Updated: this guard was removed, not replaced — re-calling `deposit-intent` on an already-paid booking today creates a duplicate `PaymentIntent`/`Payment` instead of a `409`. Blocked until §10 item 5's refactor.)*
- [ ] Deposit call by a non-owner, non-admin → `403`
- [ ] Webhook with a bad/missing signature → `400`, no state change
- [ ] Webhook redelivery of an already-applied event → no-op, confirmed via unchanged `updated`/`paidAt` timestamps
- [ ] ~~Cron: set a booking's `start_date` to tomorrow, `paid_status` to `DEPOSIT` with a real saved payment method, trigger `chargeBalancesDueTomorrow()` manually, confirm the balance charge succeeds and `Booking.paidStatus → FULL`~~ *(Updated: `chargeBalancesDueTomorrow()`'s sweep query is stubbed to always find zero bookings — see §10 item 5. To exercise the actual charge logic today, call `BalanceChargeSchedulerService.processOne(bookingId)` directly instead of the cron entry point; the `Booking.remainingBalance → 0` transition still works, `paidStatus → FULL` does not, since that field no longer exists.)*
- [ ] Cron failure path: use a Stripe off-session-decline test payment method, confirm `Payment.requiresManualFollowUp` / `Booking.needsManualFollowUp` are set and a second manual invocation does **not** retry (still applies to `processOne` directly)
- [ ] Cron double-run: invoke `processOne(bookingId)` twice in a row for the same booking, confirm no duplicate `BALANCE` `Payment` row is created

### 11.2 Automated tests

None exist yet for this feature. See `SPEC-auth-login-logout.md` §8.2 / `SPEC-tests.md` for the pattern to follow (`*IntegrationTest`, requires reachable Postgres).

### 11.3 Manual testing

See [`STRIPE_WEBHOOK_TESTING.md`](../STRIPE_WEBHOOK_TESTING.md) for the full `stripe listen` walkthrough (install, login, forward events, wire up the signing secret, trigger real vs. synthetic events, and test the cron path).

Quick reference for the deposit call once you have an access token (see `SPEC-auth-login-logout.md` §8.4 for how to get one):

```bash
curl -s -X POST http://localhost:8080/api/payments/deposit-intent \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"bookingId": 1}'
```

---

## 12. Frontend integration (`heavy-rental-react-web-portal`)

This section replaces the `FRONTEND_CHANGES.md` handoff doc referenced by earlier versions of this spec's header table, which was written but never actually committed to this repo. Written in-file instead, since that's the only copy that's ever existed.

### 12.1 Current state — checkout is 100% client-simulated

Nothing in the frontend's checkout flow calls this backend today:

- `src/features/checkout/payment.ts` — `SimulatedPayment` and `generateFakePaymentIntentId()` fabricate a fake `pi_...` id entirely client-side, explicitly as "a client-side-only stand-in... never sent anywhere" per that file's own comment.
- `src/features/checkout/DepositCheckout.tsx` — `handlePay()` uses `setTimeout` to fake a processing delay, and a hardcoded test-decline card number (`4000000000000002`) to simulate a failure, mirroring Stripe's own `4000...0002` convention without calling Stripe at all.

The frontend's auth flow, by contrast, **is** already wired for real (§12.2) — it's specifically the payment step that remains simulated.

### 12.2 What's already correct and needs no change

`src/app/api.ts`'s `login()` already implements this spec's exact interim → login sequence (`GET /api/auth/getBearerToken` as plain text → `POST /api/auth/login` with that as Bearer → `LoginResponse`), and `request()` already injects `Authorization: Bearer <token>` on every call once `setAuthToken()` has been called after login. Any new `paymentApi.*` call added per §12.3 gets this for free — no new auth plumbing needed.

### 12.3 What needs to change, concretely

1. **`src/app/api.ts`** — add a `paymentApi.createDepositIntent(bookingId)` calling `POST /api/payments/deposit-intent` (via the existing `request()` helper), returning `{ clientSecret, paymentIntentId }` per §6.1.
2. **`src/features/checkout/payment.ts`** — once §10 item 4 (booking creation) exists and returns a real `bookingId`, replace `generateFakePaymentIntentId()` with the real call from (1), and use the real `paymentIntentId`/`clientSecret` it returns instead of a fabricated one.
3. **`src/features/checkout/DepositCheckout.tsx`** — replace `handlePay()`'s `setTimeout`-based fake processing and hardcoded decline card with actual Stripe.js: load `@stripe/stripe-js`, call `stripe.confirmPayment(clientSecret)` (or `confirmCardPayment`, depending on which Stripe.js integration pattern is chosen) against the real `clientSecret` from (1)/(2), and drive the existing `summary`/`payment`/`processing`/`failed` step states off Stripe's actual response instead of a timer.
4. **Stripe publishable key** — `stripe.publishable.key` exists in this backend's `application.properties` (driven by `STRIPE_PUBLISHABLE_KEY`) but is **not consumed server-side at all** (§4.1) — it exists purely so this value can be read into the frontend's own build config. The frontend needs its own env var (e.g. `VITE_STRIPE_PUBLISHABLE_KEY`) carrying the same value; nothing currently wires this across the two repos, it has to be set independently in each.

### 12.4 What blocks this today

No backend blockers remain. Both gaps previously listed here are resolved:

- **Booking creation (§10 item 4, fixed 2026-08-11)** — `POST /api/bookings` exists and is verified end-to-end into `deposit-intent`. Steps (2)/(3) above can now use a real, freshly-created `bookingId` instead of only the pre-seeded rows in `data.sql`.
- **CORS (§10 item 7, fixed 2026-08-11)** — but only for the origins listed in `app.cors.allowed-origins`/`APP_CORS_ALLOWED_ORIGINS`, which defaults to local Vite dev/preview only. Whoever deploys this needs to set that env var to the real frontend origin(s) before a deployed browser build can call this API at all.

What's left is entirely on the frontend side (§12.3) plus the real-Stripe-sandbox testing this spec still hasn't done (§11) — the backend's code path is proven to reach Stripe correctly, but no real card has actually been charged in test mode yet.

---

## 13. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.0.0 | 2026-08-06 | Initial as-built SPEC: deposit PaymentIntent creation, webhook (succeeded/failed), daily off-session balance-charge cron (02:00 Asia/Singapore, single-attempt failure policy, in-app-flag-only follow-up). Documents `PaymentService`, `PaymentWebhookService`, `BalanceChargeSchedulerService`, `StripeWebhookController`, `CurrentUserService`, and the `User`/`Payment`/`Booking` field additions. Not yet runtime-verified (§10). |
| 1.1.0 | 2026-08-11 | Rebased branch `hr-27-payment-checkout` onto `develop` locally (not pushed). Per explicit direction, `develop`'s already-merged `Booking`/booking-endpoint model won every conflict; this feature's `Booking.paidStatus` usages were deleted (not reinstated) across `PaymentService`/`PaymentWebhookService`/`BalanceChargeSchedulerService`/`BookingRepository`, each marked `// TODO(stripe-refactor)` — see new §10 item 5 for the three concrete behavior changes this causes (no re-init guard, no booking-status transition on success, cron disabled). §10 items renumbered/expanded (booking-creation gap promoted to its own item 4; CORS gap added as item 7). §11.1 checklist updated to strike through and annotate the three assertions that depended on the removed field. New §12 added (frontend integration guidance, replacing the never-committed `FRONTEND_CHANGES.md` reference in the header table) covering current state, what's already correct, concrete required changes, and the two blockers (booking creation, CORS) preventing any of it from working end-to-end yet. Header table's `Status`/`Depends on`/`Environment context`/`Related docs` rows updated to match. Change control renumbered §12 → §13 accordingly. |
| 1.2.0 | 2026-08-11 | §10 item 7 (CORS) fixed on this branch — see [`SPEC-api-index.md`](./SPEC-api-index.md) 1.4.0 for the implementation details (`CorsProperties`, `CorsConfigurationSource` bean in `SecurityConfig`, verified against a running instance). §12.4's blocker list reduced from two items to one (booking creation only); §10 item 7 and the header table's `Environment context` row updated from "not configured" to describe the fix and its remaining local-dev-only default. |
| 1.3.0 | 2026-08-11 | §10 item 4 (no booking-creation endpoint — the last remaining blocker) fixed on this branch. `POST /api/bookings` implemented; full contract in [`SPEC-api-index.md`](./SPEC-api-index.md) §2.2.1. Verified against a running instance: a real created booking's `bookingId` fed straight into `POST /api/payments/deposit-intent` reached Stripe correctly, failing only on the placeholder API key — proving the deposit flow is wired end-to-end for the first time. §12.4 now lists zero backend blockers. Header table's `Depends on` row updated. A pre-existing, unrelated bug found while verifying this (missing sequence sync on 12 of 13 seeded tables, also silently breaking `POST /api/equipment`) was fixed in `data.sql` — see [`SPEC-api-index.md`](./SPEC-api-index.md) 1.5.0 for details, not duplicated here since it isn't specific to payments. |
