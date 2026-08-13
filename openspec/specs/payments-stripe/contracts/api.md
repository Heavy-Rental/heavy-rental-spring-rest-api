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

## `POST /api/payments/webhook`

```http
Stripe-Signature: t=...,v1=...
Content-Type: application/json
```

Public path (no JWT). Events: `payment_intent.succeeded`, `payment_intent.payment_failed`.

## Scheduler

`BalanceChargeSchedulerService` — cron `0 0 2 * * *` zone `Asia/Singapore` (as-built design).

## Related

- Booking create / deposit amounts: api-index / booking capability  
- Proxy note: deposit does **not** go through haystack
