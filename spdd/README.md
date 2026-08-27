# OpenSPDD — Structured Prompt-Driven Development

| Field | Value |
|-------|--------|
| **Standard** | **OpenSPDD** (REASONS canvas) |
| **Location** | `spdd/prompt/` |
| **Role** | First-class **generation / implementation prompts** |
| **Not** | Full product API source of truth (that is OpenSpec under `openspec/`) |

## Discipline

1. **Behavior diverges** → update the REASONS canvas **first**, then code.  
2. **Pure refactor** → code first, then sync Structure / Operations on the canvas.  
3. OpenSpec holds MUST/SHALL requirements; SPDD holds the single prompt used to generate or realign code.

## REASONS sections

| Letter | Meaning |
|--------|---------|
| **R** | Requirements — problem, DoD, scope-out |
| **E** | Entities — domain + DTOs |
| **A** | Approach — strategy and trade-offs |
| **S** | Structure — packages / layers |
| **O** | Operations — ordered implementation steps |
| **N** | Norms — coding standards |
| **S** | Safeguards — non-negotiable MUST NOTs |

## Canvas inventory

| Canvas | Change | Status |
|--------|--------|--------|
| [`prompt/S2b-resilient-haystack-recommender-client.md`](./prompt/S2b-resilient-haystack-recommender-client.md) | S2b haystack recommender client | **As-built** (incl. FR-S2B-011 quantity pass-through) |
| [`../openspec/changes/2026-08-20-call2-quote-quantity-passthrough/design.md`](../openspec/changes/2026-08-20-call2-quote-quantity-passthrough/design.md) | Call 2 collapsed quantity pass-through | **As-built** REASONS + ADR |
| [`../openspec/changes/dynamic-plan-quote-pricing/design.md`](../openspec/changes/dynamic-plan-quote-pricing/design.md) | Flag-gated haystack quote pricing | **As-built** REASONS + ADR |
| [`../openspec/changes/pricing-postal-distance/design.md`](../openspec/changes/pricing-postal-distance/design.md) | OneMap distance + postal validation | **As-built** REASONS + ADR |

## Related

- OpenSpec SoT: [`../openspec/specs/`](../openspec/specs/) (index [`../openspec/project.md`](../openspec/project.md))  
- Recommender: [`../openspec/specs/haystack-recommender/spec.md`](../openspec/specs/haystack-recommender/spec.md) · [`contracts/portal-api.md`](../openspec/specs/haystack-recommender/contracts/portal-api.md)  
- Quote pricing: [`../openspec/specs/rental-plan-quote/spec.md`](../openspec/specs/rental-plan-quote/spec.md) · [`../openspec/specs/spring-proxy-endpoints/spec.md`](../openspec/specs/spring-proxy-endpoints/spec.md)  
- Postal validation: [`../openspec/specs/postal-code-validation/spec.md`](../openspec/specs/postal-code-validation/spec.md)  
- Archived S2b change: [`../openspec/changes/archive/2026-08-12-s2b-resilient-haystack-client/`](../openspec/changes/archive/2026-08-12-s2b-resilient-haystack-client/)  

## New work

Optional: add a new canvas under `prompt/` when running OpenSPDD generate for a large change.  
Every change still needs OpenSpec `changes/<id>/` (or updates to living `specs/`) for requirements.
