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
| [`prompt/S2b-resilient-haystack-recommender-client.md`](./prompt/S2b-resilient-haystack-recommender-client.md) | S2b haystack recommender client | **As-built** |

## Related

- OpenSpec SoT: [`../openspec/specs/haystack-recommender/spec.md`](../openspec/specs/haystack-recommender/spec.md)  
- Portal contract: [`../openspec/specs/haystack-recommender/contracts/portal-api.md`](../openspec/specs/haystack-recommender/contracts/portal-api.md)  
- Archived change: [`../openspec/changes/archive/2026-08-12-s2b-resilient-haystack-client/`](../openspec/changes/archive/2026-08-12-s2b-resilient-haystack-client/)  

## New work

Optional: add a new canvas under `prompt/` when running OpenSPDD generate for a large change.  
Every change still needs OpenSpec `changes/<id>/` (or updates to living `specs/`) for requirements.
