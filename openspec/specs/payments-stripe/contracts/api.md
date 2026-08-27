# Contract: Stripe payments

| Field | Value |
|-------|--------|
| **Capability** | payments-stripe |
| **Status** | As-built |

## Config

```properties
stripe.api.key=${STRIPE_API_KEY}
stripe.publishable.key=${STRIPE_PUBLISHABLE_KEY}
stripe.webhook.secret=${STRIPE_WEBHOOK_SECRET}
```

Currency: `"sgd"` (hardcoded as-built).

## `POST /api/payments/deposit-intent`

```http
Authorization: Bearer <access-jwt>
Content-Type: application/json

{ "bookingId": 1 }
```

**Success:** `{ "clientSecret": "...", "paymentIntentId": "pi_..." }` (field names per `PaymentIntentResponse`).

**409** if a non-FAIL DEPOSIT or FULL_PAYMENT payment already exists on the booking.

## `POST /api/payments/full-payment-intent`

```http
Authorization: Bearer <access-jwt>
Content-Type: application/json

{ "bookingId": 1 }
```

One-shot payment for `Booking.totalAmount` — no deposit/balance split, no `setup_future_usage` (no later off-session charge to make).

**GST-inclusive:** charged amount is `totalAmount * 1.09` (GST_RATE = 0.09). Confirmed deliberate: deposit/balance never collect GST, so full payment costs 9% more in absolute terms than deposit+balance for the same booking.

**Success:** `{ "clientSecret": "...", "paymentIntentId": "pi_...", "amount": 1090.00 }` (field names per `FullPaymentIntentResponse` — note this is a distinct DTO from `PaymentIntentResponse`, since it carries the GST-inclusive amount the frontend needs to display).

**409** if a non-FAIL DEPOSIT, BALANCE, or FULL_PAYMENT payment already exists on the booking.

On webhook success, the booking goes straight to `CONFIRMED` with `remainingBalance = 0` (skips `PENDING_CONFIRMED`), so `BalanceChargeSchedulerService` — which only queries `PENDING_CONFIRMED` — never picks it up. A failed full payment does not set manual follow-up (same as a failed deposit).

## `POST /api/payments/webhook`

```http
Stripe-Signature: t=...,v1=...
Content-Type: application/json
```

Public path (no JWT). Events: `payment_intent.succeeded`, `payment_intent.payment_failed`.

## Schedulers

| Job | When | Role |
|-----|------|------|
| `BalanceChargeSchedulerService` | cron `0 0 2 * * *` `Asia/Singapore` | Off-session 70% balance for `PENDING_CONFIRMED` bookings starting tomorrow |
| `PaymentReconciliationSchedulerService` | every 15 minutes | Re-check `PENDING` payments older than 10 minutes against Stripe (missed-webhook backstop) |

## Related

- Booking create / deposit amounts: api-index / booking capability  
- Proxy note: deposit does **not** go through haystack
