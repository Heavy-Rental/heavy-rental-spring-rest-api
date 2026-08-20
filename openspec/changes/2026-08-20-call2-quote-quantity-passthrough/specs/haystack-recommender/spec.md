## ADDED Requirements

### Requirement: Pass through Call 2 collapsed quote quantity (FR-S2B-011)

The system MUST map Haystack Call 2 `items[].quantity` onto portal `items[].quantity` without change. After upstream FR-P-013, unit-need siblings that share parent need + `equipment.id` are already collapsed on the quote envelope: `quantity` is the duplicate count (3 copies → `3`) and `lineTotal` is the sum of grouped unit totals. Spring MUST NOT re-collapse rows, MUST NOT default omitted quantity to 1, and MUST NOT invent quantity from `lineTotal`, rental days, daily rate, or Call 1 `needsSummary`.

Jackson MUST bind `quantity` from a realistic Call 2 body that also includes unknown fields (`needId`, `mlPredictedPrice`, `equipment.extra`) and float `capacity`. Those extra fields MAY be ignored.

Portal JSON field name remains `quantity`. No new item field is required for this requirement.

#### Scenario: Collapsed forklift quantity passes through
- GIVEN Call 2 returns four quote lines with quantities 1, 1, **3**, 1 (Haystack FR-P-013 collapse; forklift `lineTotal` 5318.4)
- WHEN the portal project-spec response is built
- THEN `items[2].quantity` is 3
- AND `items[2].lineTotal` is 5318.4
- AND the other three lines keep quantity 1

#### Scenario: Realistic FAST API JSON still binds quantity
- GIVEN Call 2 JSON for one item includes `quantity: 3`, `needId`, `mlPredictedPrice`, `equipment.extra`, and `capacity: 4200.0`
- WHEN the haystack client deserializes the recommend response
- THEN `items[0].quantity` is 3
- AND the item is not dropped because of unknown fields

#### Scenario: Portal JSON exposes collapsed quantity
- GIVEN WireMock stubs Call 2 with `quantity: 3`
- WHEN `POST /api/recommendations/project-spec` succeeds
- THEN the response JSON has `items[0].quantity` equal to 3

#### Scenario: Omitted quantity stays null
- GIVEN Call 2 omits `quantity` on an item
- WHEN the item is mapped for the portal
- THEN portal `items[].quantity` is null
- AND the system does not default it to 1
