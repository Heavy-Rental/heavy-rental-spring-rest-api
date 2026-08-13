# Stripe Payments — Source of Truth

## Purpose

30% deposit PaymentIntent (client-initiated), signature-verified webhook, and daily off-session 70% balance charge one day before rental start.

**Status:** **As-built** on payment checkout branch lineage; verify against live code for `paidStatus` model drift  
**HTTP / config:** [`contracts/api.md`](./contracts/api.md)  
**Auth:** deposit-intent requires access JWT (owner or admin); webhook is public + Stripe signature

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

A daily scheduled job (Asia/Singapore) MUST find bookings starting tomorrow that still need balance payment, create at most one balance attempt, charge off-session using the saved deposit payment method, and on decline mark manual follow-up without automatic retry.

#### Scenario: One attempt only
- GIVEN a prior FAIL balance payment for the booking
- WHEN the scheduler runs
- THEN it does not create another automatic charge attempt

### Requirement: FR-PAY-004 Currency and deposit rate ownership

Currency is SGD as-built. Deposit rate MUST live at booking-creation time (`Booking.depositAmount` / remaining balance), not recomputed ad hoc inside PaymentIntent creation.

## Known gaps

- `Booking.paidStatus` may have been folded/removed on some branches — confirm against entity before assuming transitions  
- Sandbox verification may still be pending  
- No email/Slack on balance failure (in-app flag only)

## Out of scope

- FULL_PAYMENT one-shot endpoint  
- PayNow / non-card methods  
- Frontend Stripe.js implementation details
