# Specification: REST API Index

| Field | Value |
|-------|--------|
| **Document type** | Cross-cutting index — not a feature contract itself |
| **Status** | As-built across `develop` + two unmerged sibling branches (see §2) |
| **Module** | `heavy-rental-spring-rest-api` |
| **Related specs** | [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md), [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md), [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md), [`SPEC-entity-repository.md`](./SPEC-entity-repository.md) |
| **Environment context** | [`SPEC-project-environment.md`](./SPEC-project-environment.md) (read first) |

This document is the **single place to see the entire REST surface** — every route, which client it's for, what branch it lives on, and which feature spec (if any) owns its detailed contract. It does not restate request/response shapes already documented elsewhere; it points to them.

Each feature-scoped SPEC (auth, equipment, …) remains the source of truth for *its own* contract, per this project's existing convention (see e.g. `SPEC-request-bearer-token.md` §7, which was deliberately split out of a combined file to keep contracts independently owned). This index exists only to solve a different problem: with contracts scattered one-per-file, there was no single place to answer "what's the full API surface, and who's it for" — that gap is what this file closes.

**When a route is added, removed, or reassigned to a different client, update this index in the same change set** — same discipline `SPEC-entity-repository.md` already commits to for its own content.

---

## 1. Status legend

| Status | Meaning |
|---|---|
| ✅ Merged → `develop` | Live on `develop` today |
| 🔀 Branch `HR-80` (this branch) | On `HR-80-implement-endpoints-for-bookings-deliveries-and-returns`, not yet merged to `develop` |
| 🔀 Branch `HR-72` (sibling) | On `HR-72-add-browse-equipment-to-rest-api`, not yet merged to `develop`, **diverged independently of HR-80** (see §4) |
| 🧱 Stub | Route exists and returns `200`, but has no real backing entity/logic — placeholder so a frontend call doesn't 404 |
| ⏳ Not started | Branch name/intent exists; no code written yet |

---

## 2. Endpoint index

### 2.1 Auth — shared by both clients

| Method | Path | Client | Roles allowed | Status | Contract |
|---|---|---|---|---|---|
| `GET` | `/api/auth/getBearerToken` | Shared | Public | ✅ Merged → `develop` | [`SPEC-request-bearer-token.md`](./SPEC-request-bearer-token.md) |
| `POST` | `/api/auth/login` | Shared | `ROLE_INTERIM` | ✅ Merged → `develop` | [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) |
| `POST` | `/api/auth/logout` | Shared | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` | [`SPEC-auth-login-logout.md`](./SPEC-auth-login-logout.md) |

There is currently **one** login flow. No `platform`/`audience` field or web-vs-mobile distinction exists anywhere in the request, the JWT claims, or `SecurityConfig` — see §4.

### 2.2 Mobile — bookings, deliveries, returns, payments

Per branch author: every route in this section is for the **mobile** client.

| Method | Path | Roles allowed | Status | Contract |
|---|---|---|---|---|
| `GET` | `/api/bookings` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-80` (this branch) | [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) §5.2 |
| `GET` | `/api/bookings/{bookingId}` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-80` (this branch) | §5.2 |
| `PUT` | `/api/bookings/{bookingId}` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-80` (this branch) | §5.2 |
| `GET` | `/api/deliveries` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-80` (this branch) | §5.2 |
| `PATCH` | `/api/deliveries/{bookingId}/status` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-80` (this branch) | §5.2 |
| `GET` | `/api/returns` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-80` (this branch) | §5.2 |
| `PATCH` | `/api/returns/{bookingId}/status` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-80` (this branch) | §5.2 |
| `POST` | `/api/payments/create-payment-intent` | `ROLE_USER`, `ROLE_ADMIN` | ✅ Merged → `develop` (predates this branch — `HR-60`) | None — no feature spec exists for `PaymentController`/`PaymentService`; out of scope for `SPEC-booking-delivery-return-api.md` too (see that file's §2.2) |

Seven of these eight routes now have a dedicated feature SPEC — [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md), written per the standalone-spec criterion in `SPEC-project-environment.md` §9.1. Payments remains undocumented (predates this branch, never had a spec written for it). `SPEC-entity-repository.md` still documents the underlying `Booking`/`Payment`/`DeliveryRecord`/`ReturnRecord` *entities* (§3.2/§10.7 of that file), but not their REST layer.

### 2.3 Web — equipment browse, depots, rental plans

Per branch author: every route in this section is for the **web** client. **None of this section exists in the current working tree** — `EquipmentController`, `DepotController`, and `RentalPlanController` all live only on `origin/HR-72-add-browse-equipment-to-rest-api`, which diverged from `develop` at the same commit `HR-80` did (`584346f`, "HR-66 Populating Data") and has not been merged either direction.

| Method | Path | Roles allowed | Status | Contract |
|---|---|---|---|---|
| `GET` | `/api/equipment` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-72` (sibling) | [`SPEC-equipment-browse-api.md`](./SPEC-equipment-browse-api.md) §7.1 |
| `GET` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-72` (sibling) | §7.2 |
| `POST` | `/api/equipment` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-72` (sibling) | §7.3 |
| `PUT` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-72` (sibling) | §7.4 |
| `PATCH` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-72` (sibling) | §7.4 |
| `DELETE` | `/api/equipment/{id}` | `ROLE_USER`, `ROLE_ADMIN` | 🔀 Branch `HR-72` (sibling) | §7.5 |
| `GET` | `/api/depots` | `ROLE_USER`, `ROLE_ADMIN` | 🧱 Stub, on branch `HR-72` | None — always returns `[]`; no `Depot` entity exists (delivery site fields live on `Booking`/`RentalPlan` directly) |
| `GET` | `/api/rental-plans` | `ROLE_USER`, `ROLE_ADMIN` | 🧱 Stub, on branch `HR-72` | None — always returns `[]`; `RentalPlan`/`RentalPlanRepository` exist but nothing is wired to them yet |

### 2.4 Admin — operations dashboard

Per branch author: this route is for the **admin operations portal** (Overview tab). No other admin routes exist in the current working tree — the Users tab (`UserController`) is separate, unmerged work on a teammate's branch, not covered by this index yet.

| Method | Path | Client | Roles allowed | Status | Contract |
|---|---|---|---|---|---|
| `GET` | `/api/monthly-utilization` | Admin | `ROLE_ADMIN` only (not `ROLE_USER`) | 🔀 Branch `hr-40-equipment-utilization-tracker` (this branch) | None — no dedicated feature spec yet; see [`CHANGES-monthly-utilization.md`](./CHANGES-monthly-utilization.md) for design and verification detail |

Returns trailing 6 calendar months (oldest → newest) of `{id, month, utilization, revenue}` for the Overview tab's revenue chart and utilization stat. Verified accurate against raw `Payment`/`BookingItem`/`Asset` data (independent recomputation, all 6 months matched exactly) and confirmed end-to-end through the real web portal. Not yet committed as of this writing.

### 2.5 Planned, not started

| Item | Status | Notes |
|---|---|---|
| `platform` attribute on `LoginRequest` | ⏳ Not started | Branch `HR-85-implement-platform-attribute-in-login-request-body` exists but has **zero commits beyond `HR-77`** — it's an unstarted placeholder, not a design that's been written down anywhere yet. Likely the intended mechanism for distinguishing web vs mobile at login (see §4) once work begins. |

---

## 3. Correcting an assumption this index was built to check

Before drafting this file, the working assumption was that **web auth was already built separately in `HR-72`**. Checked directly against that branch's diff and its own spec (`SPEC-equipment-browse-api.md` §2.2, §9): **HR-72 makes zero changes to `Authentication.java`, `AuthService`, `SecurityConfig`, or `LoginRequest`.** Its own spec says so explicitly: *"No `SecurityConfig` changes — the existing catch-all `hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` rule already covers these new routes."* Web and mobile, as of both branches today, authenticate through the **exact same** `getBearerToken` → `login` → `logout` flow in §2.1. There is no second, web-specific auth implementation anywhere in this repository's history.

---

## 4. Web/mobile separation — current state and open question

**Current state:** none. One JWT scheme, one role model (`ROLE_USER`/`ROLE_ADMIN`/`ROLE_DRIVER` from `User.role`), one blanket `SecurityConfig` rule (`anyRequest().hasAnyAuthority("ROLE_USER","ROLE_ADMIN")`) covering every business route regardless of which client it's "for." Nothing today stops a web session token from calling `/api/deliveries/{id}/status`, or a mobile session token from calling `/api/equipment`. The web/mobile split in §2.2/§2.3 is a documentation convention (which team owns which route), not an enforced boundary.

One concrete consequence worth flagging here since it sits exactly on this seam: **`ROLE_DRIVER` cannot call any protected route today**, including the mobile delivery/return endpoints in §2.2 that `DeliveryRecord`/`ReturnRecord` (`SPEC-entity-repository.md` §5.10–5.11) were modeled around. `data.sql` seeds a `DRIVER` user (`Ah Tan`) specifically as the `driver_id` on those tables, but `SecurityConfig`'s blanket rule only grants `ROLE_USER`/`ROLE_ADMIN`. If the mobile client's actual end users include drivers, this blocks them outright — independent of any web/mobile question, this is a bug worth its own fix.

**Open question — should separation be enforced?** Not resolved here; recorded so it isn't lost. Two considerations worth weighing before deciding:

- Enforcing it would mean tagging tokens with a client/audience claim (this is likely what the still-unstarted `HR-85` `platform` field is for) and adding per-route matchers in `SecurityConfig` keyed off it — a real design change, not a doc change.
- The role model (`USER`/`ADMIN`/`DRIVER`) may already be doing most of the job a client-based split would do, if mobile's real audience is drivers and web's is customers/admins — in which case fixing the `ROLE_DRIVER` gap above and authorizing by role (as `SecurityConfig` already does) could make a separate client dimension redundant. Worth confirming who mobile's actual users are before building a second axis of access control alongside role.

Recommendation if/when this gets picked up: don't build the enforcement until that question is answered — it's easy to add a claim + matcher later, harder to unwind if it's added speculatively and turns out to duplicate the role check.

---

## 5. Known issues — mobile endpoints (§2.2)

Moved to [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) §6, which is now the primary write-up for these (role/ownership checks, multi-asset data loss, missing `DeliveryRecord`/`ReturnRecord` persistence, N+1 queries, plus one added since — `PUT /api/bookings/{id}`'s full-replace-not-partial-merge semantics). Originally verified and recorded here from a PR review; relocated once the booking/delivery/return feature got its own spec, since re-explaining the same findings in two files risks them drifting apart the way `SPEC-entity-repository.md`'s controller-wiring claims drifted from the code before this change (§6 below). Kept as a one-line pointer here rather than a full section for that reason.

---

## 6. Companion spec corrections made in this change

`SPEC-entity-repository.md` and `SPEC-seed-data.md` contained claims that predate this branch's controllers (and, in two cases, predate even `develop`'s current state). Both were corrected in this same change set per each file's own "update together" convention — see their change-control tables for specifics. Summary:

- `SPEC-entity-repository.md` §3.2 / §10.7 said *"no entity beyond `User` is wired to a controller"* — false today: `Booking` (this branch) and `Payment` (already on `develop` via `HR-60`, never documented) both have live controllers.
- `SPEC-entity-repository.md` said `ddl-auto=create-drop` (four places) — the project has run `ddl-auto=update` since `SPEC-seed-data.md`'s seeding design was built; the entity-repository doc was never updated to match.
- `SPEC-entity-repository.md` §5.7/§6.2 and `SPEC-seed-data.md` §6.6 still listed `Booking.BookingStatus` as `PENDING, CONFIRMED, MOBILISED, COMPLETED, CANCELLED` and documented a `Booking.PaidStatus` field — both changed by `HR-77` (already merged to `develop`, so this drift predates `HR-80`), which split `PENDING` into `PENDING_DEPOSIT`/`PENDING_CONFIRMED` and removed `PaidStatus` entirely.

**Known git-conflict note:** `HR-72` independently edited the same §3.2 sentence, the same §8 repository-catalog table, and the same §10 note #7 in `SPEC-entity-repository.md` (to add its own `Asset`/`Equipment` correction), and also edited `SPEC-seed-data.md`. Both branches' edits are additive corrections to the same stale claims, for different entities — expect a textual merge conflict when `HR-72` and `HR-80` are reconciled, resolved by keeping both branches' additions rather than picking one side.

---

## 7. Change control

| Version | Date | Notes |
|---------|------|--------|
| 1.0.0 | 2026-08-09 | Initial index: consolidates auth (`develop`), bookings/deliveries/returns/payments (`HR-80`, this branch), and equipment/depots/rental-plans (`HR-72`, unmerged sibling branch) into one endpoint table with client ownership, role gates, and branch status. Documents the web/mobile separation as an open, unresolved question rather than deciding it. Corrects the mistaken premise that `HR-72` includes a separate web auth implementation. |
| 1.1.0 | 2026-08-09 | Added §5, a verified known-issues backlog for the mobile endpoints from a PR review: no role/ownership checks on booking/delivery/return routes (5.1), silent multi-asset data loss in `GET /api/deliveries`/`GET /api/returns` via `BookingMapper.primaryAsset()` — reproducible today against seed booking id 1 (5.2), `DeliveryRecord`/`ReturnRecord` never persisted, cross-referenced from `SPEC-entity-repository.md` §3.2 (5.3), and N+1 queries on the three list endpoints (5.4). Documentation only — none of these were fixed in this change; each item records its own recommended fix and deferral status. Renumbered old §5/§6 to §6/§7 accordingly. |
| 1.2.0 | 2026-08-09 | New [`SPEC-booking-delivery-return-api.md`](./SPEC-booking-delivery-return-api.md) written (per the standalone-spec criterion added to `SPEC-project-environment.md` §9.1) to be the actual contract for the seven `HR-80` routes in §2.2, which previously had none. §2.2's `Contract` column now points to it instead of showing `—`. §5's detailed known-issues writeup moved there (that file's §6) and replaced here with a one-line pointer, to avoid maintaining the same findings in two places. |
| 1.3.0 | 2026-08-11 | Added new §2.4 "Admin — operations dashboard" for `GET /api/monthly-utilization` (branch `hr-40-equipment-utilization-tracker`), the first admin-portal route in this index. Old §2.4 "Planned, not started" renumbered to §2.5. |
