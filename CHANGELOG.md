# Changelog

Release notes for this module live as **OpenSpec change packs** under [`openspec/changes/`](openspec/changes/) (proposal + OpenSPDD REASONS `design.md` + ADR + spec deltas). Living behavior is in [`openspec/specs/`](openspec/specs/). Historical plain-language logs: [`openspec/changes/archive/2026-08-docs-changelog/`](openspec/changes/archive/2026-08-docs-changelog/).

## 2026-08-27 — Documentation / specification sync

Folded as-built code into living OpenSpec, OpenSPDD, and ADRs (no product behavior change):

- Dynamic plan-quote pricing (`changes/dynamic-plan-quote-pricing/`) — FR-RP-004/006, FR-PROXY-001/005, ADR, REASONS canvas. Module default `pricing.dynamic-enabled=true`.
- OneMap postal distance + validation (`changes/pricing-postal-distance/`) — new `postal-code-validation` capability, FR-RP-012, ADR, REASONS canvas.
- Integrator overview [`DOCUMENTATION.md`](DOCUMENTATION.md) and this README aligned with `/api/assets`, Google login, full-payment intent, `ROLE_DRIVER` ops, optional plan `siteAddress`, and flag-gated quote pricing.
- `POST /api/pricing/estimate` remains **design only**.

## As-built change packs (already in living specs)

| Pack | Topic |
|------|--------|
| `changes/archive/2026-08-12-s2b-resilient-haystack-client/` | S2b Haystack recommender client |
| `changes/archive/2026-08-13-rental-plan-checkout-conversion/` | Plan → booking checkout |
| `changes/dynamic-plan-quote-pricing/` | Flag-gated Haystack quote pricing |
| `changes/pricing-postal-distance/` | OneMap distance + postal validation |
| `changes/2026-08-20-call2-quote-quantity-passthrough/` | FR-S2B-011 quantity pass-through |
