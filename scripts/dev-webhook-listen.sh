#!/usr/bin/env bash
# Forwards Stripe test-mode webhook events to the local backend. Required for any local
# checkout test to actually update Payment/Booking status after a deposit or balance
# charge succeeds — without this running, PaymentWebhookService never fires (HR-203).
# Uses the shared team test-mode key already committed as the default stripe.api.key in
# application.properties, so no personal `stripe login` is needed to run this.
set -euo pipefail

STRIPE_API_KEY="${STRIPE_API_KEY:-sk_test_51U0cYS3czsg5HEQd1fxwO9cUfpNJyd0P2CwPZLf89msUmmUYmuNR0CygBPD19KyfstcA2IZJEJReb2YYqa2OIB7z00O7vuROWd}"

exec stripe listen --api-key "$STRIPE_API_KEY" --forward-to localhost:8080/api/payments/webhook
