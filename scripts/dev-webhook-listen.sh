#!/usr/bin/env bash
# Forwards Stripe test-mode webhook events to the local backend. Required for any local
# checkout test to actually update Payment/Booking status after a deposit or balance
# charge succeeds — without this running, PaymentWebhookService never fires (HR-203).
# Requires STRIPE_API_KEY in the environment (Stripe test-mode secret key). Do not
# commit keys; export them from your local shell or a gitignored .env.
set -euo pipefail

if [ -z "${STRIPE_API_KEY:-}" ]; then
  echo "STRIPE_API_KEY is not set. Export your Stripe test-mode secret key." >&2
  exit 1
fi

exec stripe listen --api-key "$STRIPE_API_KEY" --forward-to localhost:8080/api/payments/webhook
