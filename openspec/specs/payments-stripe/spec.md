# Stripe Payments — Source of Truth

## Purpose

30% deposit PaymentIntent (client-initiated), signature-verified webhook, and daily off-session 70% balance charge one day before rental start.

**Status:** **As-built**. Payment state lives on `Payment` rows and `Booking.status` — there is no `Booking.paidStatus` column.  
**HTTP / config:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** deposit-intent and full-payment-intent require access JWT (owner or admin); webhook is public + Stripe signature

## Requirements

### Requirement: FR-PAY-001 Deposit intent server-side amount

`POST /api/payments/deposit-intent` with `{ bookingId }` MUST verify the caller owns the booking (or is admin), compute/use server-side `Booking.depositAmount` (never trust client amount), create a Stripe PaymentIntent with `setup_future_usage=off_session`, persist a PENDING `Payment`, and return `clientSecret` + `paymentIntentId`.

#### Scenario: Owner creates deposit intent
- GIVEN an unpaid booking owned by the caller
- WHEN deposit-intent is posted
- THEN Stripe PI is created for deposit amount
- AND response includes clientSecret

#### Scenario: Non-owner rejected
- GIVEN a booking owned by another user and non-admin caller
- WHEN deposit-intent is posted
- THEN forbidden/not-found per service rules

### Requirement: FR-PAY-005 Full-payment one-shot intent

`POST /api/payments/full-payment-intent` with `{ bookingId }` MUST verify the caller owns the booking (or is admin), compute/use server-side `Booking.totalAmount` (never trust client amount), create a Stripe PaymentIntent for `totalAmount * (1 + GST_RATE)` (GST_RATE = 0.09; no `setup_future_usage`), persist a PENDING `FULL_PAYMENT` `Payment` with the GST-inclusive amount, and return `clientSecret` + `paymentIntentId` + the GST-inclusive `amount`. **Confirmed deliberate:** deposit/balance never collect GST, so paying in full costs 9% more in absolute terms than deposit+balance for the same booking (109% vs 100% of `totalAmount`) — not an oversight. MUST reject (`409`) if a non-FAIL DEPOSIT, BALANCE, or FULL_PAYMENT payment already exists on the booking; the deposit-intent guard likewise MUST reject if a FULL_PAYMENT already exists, so a booking can't be paid twice. On webhook success, the booking transitions directly to CONFIRMED with `remainingBalance = 0`, skipping PENDING_CONFIRMED — so the booking is never picked up by the balance-charge scheduler (which only queries PENDING_CONFIRMED). A failed full payment does not set manual follow-up (same as a failed deposit — the customer sees the failure live at checkout).

#### Scenario: Owner pays in full
- GIVEN an unpaid booking owned by the caller with `totalAmount` = 1000
- WHEN full-payment-intent is posted
- THEN Stripe PI is created for 1090.00 (GST-inclusive)
- AND response includes clientSecret and amount = 1090.00

#### Scenario: Full payment succeeds
- GIVEN a PENDING FULL_PAYMENT payment
- WHEN payment_intent.succeeded is applied
- THEN booking status becomes CONFIRMED and remainingBalance is 0
- AND the booking is not selected by the balance-charge scheduler

#### Scenario: Double payment rejected
- GIVEN a booking with an existing non-FAIL DEPOSIT, BALANCE, or FULL_PAYMENT payment
- WHEN full-payment-intent or deposit-intent is posted again
- THEN `409` is returned and no new Stripe PaymentIntent is created

### Requirement: FR-PAY-002 Webhook signature and idempotency

`POST /api/payments/webhook` MUST verify `Stripe-Signature` with the webhook secret (`400` if invalid). On `payment_intent.succeeded` / `payment_intent.payment_failed`, update the matching `Payment` only if still PENDING (idempotent redelivery). Failed balance charges MUST set manual follow-up flags and MUST NOT auto-retry.

#### Scenario: Invalid signature
- GIVEN a webhook without valid signature
- WHEN posted
- THEN `400`

#### Scenario: Redelivery no-op
- GIVEN payment already SUCCESS
- WHEN succeeded event redelivered
- THEN no double application

### Requirement: FR-PAY-003 Balance auto-charge scheduler

A daily scheduled job (Asia/Singapore, cron `0 0 2 * * *`) MUST find bookings starting tomorrow that still need balance payment (`PENDING_CONFIRMED`), create at most one balance attempt, charge off-session using the saved deposit payment method, and on decline mark manual follow-up without automatic retry. Each booking MUST be processed in its own transaction so one failure does not abort the batch.

#### Scenario: One attempt only
- GIVEN a prior FAIL balance payment for the booking
- WHEN the scheduler runs
- THEN it does not create another automatic charge attempt

### Requirement: FR-PAY-006 Pending-payment reconciliation

A periodic backstop (`PaymentReconciliationSchedulerService`, every 15 minutes) MUST re-check payments still `PENDING` more than 10 minutes after creation directly against Stripe, so a missed webhook cannot strand a booking at `PENDING_DEPOSIT` indefinitely.

#### Scenario: Stale pending payment is reconciled
- GIVEN a PENDING payment whose `createdAt` is older than 10 minutes
- WHEN the reconciliation job runs
- THEN Stripe is queried and the matching webhook success/failure path is applied if the intent has a terminal state

### Requirement: FR-PAY-004 Currency and deposit rate ownership

Currency is SGD as-built. Deposit rate MUST live at booking-creation time (`Booking.depositAmount` / remaining balance), not recomputed ad hoc inside PaymentIntent creation. GST (9%) applies only on the full-payment path, computed at charge time in `PaymentService`, not stored on `Booking`.

## Known gaps

- No `Booking.paidStatus` column as-built — payment state lives on `Payment` rows and booking `status`  
- No email/Slack on balance failure (in-app flag only)

## Out of scope

- PayNow / non-card methods  
- Frontend Stripe.js implementation details
