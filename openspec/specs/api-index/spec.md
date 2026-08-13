# REST API Index — Source of Truth

## Purpose

Discovery layer for the full Spring REST surface: every route, client audience, roles, and pointer to the owning OpenSpec capability. **Not** a place to restate full request/response contracts.

**Status:** **As-built** (route map)  
**Route tables:** [`contracts/routes.md`](./contracts/routes.md)

## Requirements

### Requirement: FR-IDX-001 Single discovery map

The system documentation MUST maintain one route index that lists method, path, client (web/mobile/shared/admin), roles, and owning capability. When a route is added, removed, or reassigned, this index MUST be updated in the same change set.

#### Scenario: Find contract for a path
- GIVEN an engineer needs the contract for `/api/equipment`
- WHEN they open this capability's route table
- THEN they find a pointer to equipment-browse (not a second full copy of the contract)

### Requirement: FR-IDX-002 Point to OpenSpec, not new SPECs

Owning contracts MUST link to `openspec/specs/<capability>/` (or an active change). New product routes MUST NOT invent docs outside OpenSpec for living contracts.

### Requirement: FR-IDX-003 Document proxy truth

Routes that are commonly mistaken for haystack proxies (e.g. rental plan quote) MUST be described accurately in the index and spring-proxy-endpoints capability.

#### Scenario: Quote is Spring-only
- GIVEN `POST /api/rentalPlans/{id}/quote`
- WHEN documented in the index
- THEN it is not claimed to call haystack as-built

## Related

- [`../../project.md`](../../project.md)  
- [`../../AGENTS.md`](../../AGENTS.md)  
- [`../spring-proxy-endpoints/spec.md`](../spring-proxy-endpoints/spec.md)
