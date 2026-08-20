#!/usr/bin/env bash
# Starts the backend and Stripe webhook forwarding together as one command, so a
# deposit/balance payment completed locally actually updates Payment/Booking status
# (HR-203) without a separate manual step to forget. Each process keeps its own
# output; this doesn't touch the app/build itself — see dev-webhook-listen.sh and
# `./mvnw spring-boot:run` individually if you'd rather run them apart.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v stripe >/dev/null 2>&1; then
  echo "Warning: Stripe CLI not found on PATH — webhook forwarding will not run, so" >&2
  echo "deposit/balance payments won't update booking status locally. Install it:" >&2
  echo "https://docs.stripe.com/stripe-cli" >&2
fi

"$SCRIPT_DIR/dev-webhook-listen.sh" &
LISTEN_PID=$!

cleanup() {
  kill "$LISTEN_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

cd "$SCRIPT_DIR/.."
./mvnw spring-boot:run
