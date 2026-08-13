# Feature Specification: Rental Plan Cart & Checkout Flow (Real Backend)

**Feature Area**: heavy-rental-react-web-portal
**Created**: 2026-08-13
**Status**: Planned — not started
**Input**: A design conversation establishing that the current real-backend checkout (`createDepositBooking()` booking directly from ephemeral client cart state) should be replaced with a persisted `RentalPlan` lifecycle: `draft` → `quoted` → `converted`, with a Spring-only `estimate` price shown during cart-building and a Haystack-backed `quote` shown at checkout.

## Overview

Today, `npm run dev:api`'s checkout path books directly from `CartContext`'s in-memory cart ([App.tsx:1453-1460](../src/App.tsx#L1453-L1460)) — no `RentalPlan` is created or read during real-backend checkout, `CartContext` has no persistence (`Spec-rest-api-reference.md` traced this: cart is lost on logout, reload, or navigating away from the Customer Portal), and `POST /api/rentalPlans/{id}/quote` / the item-level plan routes are documented as backend-live but frontend-unwired (`Spec-rest-api-reference.md` §2.4).

This feature replaces that with a persisted cart: adding an item to cart creates (or reuses) a `RentalPlan` server-side and adds a `RentalPlanItem`, an `estimate` call shows a fast non-authoritative price, "Get Quote" fetches a Haystack-backed authoritative price and moves the plan to `quoted`, and "Proceed to Payment" converts the plan into a real `Booking`. This is a substantially different flow from what `Spec-stripe-payment-checkout.md` built — that spec's `onBeginPayment`/`createDepositBooking()` call site is what PR 3 below rewires.

## Clarifications

### Session 2026-08-13

- Q: How many active (non-`converted`) rental plans can a customer have at once? → A: Exactly one. "Active" means not yet `converted` — a plan is active in `draft` or `quoted`.
- Q: What happens on "add to cart" if the customer has no active plan yet? → A: A new `RentalPlan` is created (`status = draft`), then the item is added to it.
- Q: What price is shown while building the cart (pre-quote)? → A: `POST /api/pricing/estimate` (`Spec-rest-api-reference.md` §8.2) — Spring-only, never reaches Haystack, called after every item add so the cart price stays current. This price is explicitly non-authoritative.
- Q: What happens when "Get Quote" is clicked? → A: `POST /api/rentalPlans/{id}/quote` is called (reaches Haystack, per `Spec-rest-api-reference.md` §8.1). On success the plan's status becomes `quoted` and its price is now authoritative.
- Q: The `PlanStatus` enum has four values (`draft`, `saved`, `quoted`, `converted`) but the design only uses three — why not remove `saved`? → A: The enum class is not being touched. `saved` stays defined but unused.
- Q: How is 24-hour quote validity tracked without adding a new field? → A: `RentalPlan.updatedAt` (an existing field, distinct from `createdAt`) is repurposed as the quote timestamp. `createdAt` is untouched and keeps its normal meaning (true row-creation time). Checkout is gated on `status == quoted AND now - updatedAt <= 24h`. If a quote is stale (`status == quoted` but past 24h), the customer must click "Get Quote" again — `updatedAt` refreshes, `status` stays `quoted`.
- Q: What happens if the customer adds or removes an item after the plan is already `quoted`? → A: The quote is invalidated — `status` reverts to `draft` and `updatedAt` refreshes. This must happen inside the item-add/item-remove endpoints themselves (server-side), not be assumed/simulated by the frontend, since it's the only way to guarantee a `quoted` plan's price always matches its current item set.
- Q: What happens when "Proceed to Payment" is clicked? → A: The plan converts to a `Booking` — ownership and `status == quoted` are checked, items/dates/pricing are derived from the plan's own persisted records (not re-submitted by the client), the plan's `status` becomes `converted`, and the new `Booking`'s status starts at `PENDING_DEPOSIT`. This mirrors the existing `POST /api/bookings` contract's optional `rentalPlanId` field, which apparently already anticipated this.
- Q: What happens after the deposit payment succeeds? → A: `Booking.status` should move to `PENDING_CONFIRMED`. **This does not currently happen anywhere in the backend** — `Spec-stripe-payment-checkout.md`'s own known-gaps list already flagged this (the backend doesn't update `Booking.status` after a successful payment). Not new scope discovered here, but directly blocks the last step of this workflow, so it's tracked in §2 below alongside the rest.
- Q: Does the "checkout" step (before "Proceed to Payment") need its own no-commit pricing call? → A: Effectively already resolved by this design — `quote` (a prior, separate step) already is the no-commit preview; the checkout screen just displays the plan's already-quoted price, and only "Proceed to Payment" commits by calling `createBooking`. Spring Boot's own open question #4 (below) should be treated as most likely satisfied by this design, pending their confirmation, rather than genuinely open.

## 2. Backend (Spring Boot) dependencies

This portal's PRs 2 and 3 (below) cannot be completed or meaningfully tested until the corresponding backend work lands. Spring Boot has scoped their side as **one PR**, covering B1-B3 below. B4 is their own open question. **B5-B8 are not currently in their stated scope** and need to be raised — without them this workflow cannot be completed even after their PR merges.

### Already in Spring Boot's stated PR scope

- **B1** — `BookingService.createBooking`: when `rentalPlanId` is present, require ownership + `status == QUOTED`, derive items/dates/pricing from the plan's own records (not the request body), set `rentalPlan.status = CONVERTED` and save it.
- **B2** — Fix the day-count mismatch so quote and booking pricing agree.
- **B3** — Real 24-hour quote-validity check using `updatedAt` as the `quotedAt` proxy, enforced in `createBooking`: expired → `409`, forcing a re-quote.
- **B4** (their open question) — Whether "checkout" needs a genuine no-commit preview endpoint. Per the Clarifications above, this is likely already satisfied by treating `quote` as that preview step — recommend confirming with them rather than leaving it open.

### Not currently in their stated scope — needs raising

- **B5** — `POST /api/pricing/estimate` (§8.2 of `Spec-rest-api-reference.md`) does not exist yet and isn't in their 4-item list. PR 1 below cannot show any cart price without it.
- **B6** — `Booking.status → PENDING_CONFIRMED` on successful deposit payment. Previously flagged in `Spec-stripe-payment-checkout.md` as a known gap; still not in Spring Boot's current list. Blocks the last step of this workflow.
- **B7** — Confirm `POST /api/rentalPlans/{id}/quote` itself sets `status = QUOTED` and refreshes `updatedAt` at the moment of quoting (B3 only covers `createBooking` *reading* that timestamp later — something has to *write* it at quote time).
- **B8** — Confirm `RentalPlan` read responses (`GET /api/rentalPlans`, `GET /api/rentalPlans/{id}`, and the `POST .../quote` response) actually include `status` and `updatedAt` in the JSON body — the frontend needs both to compute/display quote-expiry state without guessing or making an extra call.
- **B9** — Confirm whether `GET /api/rentalPlans` can filter to "the caller's current active (non-`converted`) plan," or whether the frontend must fetch all of a user's plans and filter client-side to find it.
- **B10** — Confirm item-mutation-while-`quoted` reverts `status` to `draft` and refreshes `updatedAt` inside `POST/DELETE /api/rentalPlans/{id}/items...` themselves (per the Clarifications above) — this needs to be backend-enforced, not frontend-assumed.

## 3. Execution plan — three web-portal PRs

Recommended over one combined PR: PR 1 depends only on B5 (a single new endpoint) and otherwise uses backend routes that are already live today (`Spec-rest-api-reference.md` §2.4) — it can ship and be tested independently of Spring Boot's bundled PR. PRs 2 and 3 are both tightly coupled to that bundled PR and to each other (quote-validity and conversion are two halves of one mechanism), so they stay together as sequential-but-linked units rather than being split further.

### PR 1 — Cart persistence + estimate pricing

- Add `rentalPlanApi.get()`, `.addItem()`, `.removeItem()` to `src/app/api.ts`, wiring the already-live `POST /api/rentalPlans`, `POST /api/rentalPlans/{id}/items`, `DELETE /api/rentalPlans/{id}/items/{itemId}` routes (currently unwired per §2.4 of `Spec-rest-api-reference.md`).
- Add `pricingApi.estimate()` for `POST /api/pricing/estimate` once B5 exists.
- Rework "add to cart": look up the customer's current active plan (B9-dependent — fetch-and-filter if no server-side filter exists); create one (`status = draft`) if none exists; add the item via the items endpoint; call `estimate` and display the returned price as non-authoritative.
- Rework "remove from cart" to call the item-delete endpoint.
- Replace (or significantly rework) `CartContext`'s pure in-memory model, since the source of truth for cart contents becomes the persisted `RentalPlan`, not local React state alone.

### PR 2 — "Get Quote" wiring

- Wire "Get Quote" to `POST /api/rentalPlans/{id}/quote`; on success, show the plan's status as "Quoted" and display the authoritative price.
- Add 24h-validity UI: compute expiry from the plan's `updatedAt` (B8-dependent); if stale, show a clear "quote expired — get a new one" state and disable checkout until re-quoted.
- Depends on B7 and B8.

### PR 3 — Checkout & payment conversion rewire

- Replace `createDepositBooking()`'s current direct-cart booking call ([DepositCheckout.tsx](../src/features/checkout/DepositCheckout.tsx), [App.tsx:1453-1460](../src/App.tsx#L1453-L1460)) with a call that submits the plan's `rentalPlanId` per B1's contract, instead of resubmitting cart items.
- Handle the new `409` (expired quote, B3) with a specific "your quote expired" message routing back into PR 2's re-quote flow, not a generic error.
- Once B6 lands, revisit `ConfirmationScreen.tsx`'s deliberate independence from booking status (`Spec-stripe-payment-checkout.md` FR-008) to reflect `PENDING_CONFIRMED` where appropriate.
- Depends on B1, B2, B3, and ideally B6.

## Dependencies & Assumptions

- Assumes Spring Boot's single bundled PR covers B1-B3 as stated, and that B5-B8/B10 get raised and scheduled separately — this plan does not assume they're already covered.
- Assumes `POST /api/bookings`'s existing optional `rentalPlanId` field is the intended mechanism for conversion (not a new route) — consistent with `Spec-rest-api-reference.md`'s existing documentation of that contract.
- Assumes the item-level plan routes (`POST/DELETE .../items`) already exist and work today per `Spec-rest-api-reference.md` §2.4; if that's inaccurate, PR 1 blocks on them too.

## Out of Scope

- Mock-mode (`npm run dev:mock`/`dev`) checkout — unaffected, continues creating a `RentalPlan` + `Booking` in one shot at checkout as it does today.
- Deciding B4 unilaterally — flagged as likely-resolved-by-design but left for Spring Boot to confirm, not decided here.
- Fixing the day-count mismatch (B2) or the payment-status gap (B6) from the frontend side — both are backend-owned fixes this plan depends on, not frontend work.

## Change Log

- 2026-08-13: Initial plan written. Establishes the `draft`/`quoted`/`converted` lifecycle backed by `updatedAt`-as-`quotedAt` (not `createdAt`, and not a new field), cross-references Spring Boot's stated PR scope (B1-B4) against this workflow's actual requirements, flags four additional backend items not in their stated scope (B5, B6, B7, B8/B9/B10 grouped), and sequences the web-portal side into three PRs by dependency rather than one combined PR.
