# Design: pricing-postal-distance (draft)

## Wire contract (upstream, OneMap API — onemap.gov.sg)

Two calls, both under `onemap.base-url` (default `https://www.onemap.gov.sg`):

```
POST /api/auth/post/getToken
Body: {"email": "...", "password": "..."}
Response: {"access_token": "<jwt>", "expiry_timestamp": "<epoch-seconds-as-string>"}
```

```
GET /api/common/elastic/search?searchVal={postalCode}&returnGeom=Y&getAddrDetails=Y&pageNum=1
Header: Authorization: Bearer <access_token>
Response: {
  "found": 1,
  "totalNumPages": 1,
  "results": [
    {"POSTAL": "619094", "ADDRESS": "20 JURONG PORT ROAD SINGAPORE 619094", "LATITUDE": "1.32...", "LONGITUDE": "103.70..."}
  ]
}
```
`found: 0` (empty `results`) means the postal code doesn't resolve — this is the `Optional.empty()` / `INVALID` case, not an error. `LATITUDE`/`LONGITUDE` are returned as strings; parse to `double` in `OneMapClient`. The token is short-lived (~3 days); `OneMapAuthService` caches it and refetches proactively once within `onemap.token-refresh-buffer` (default 6h) of expiry, rather than on every call.

## Approach

1. **`client/onemap/`** (new package, mirrors `client/haystack`'s established shape):
   - `OneMapProperties` (`@ConfigurationProperties(prefix="onemap")`, plain mutable class like `HaystackProperties`) — `baseUrl`, `email`, `password`, nested `Timeouts` (`connect` 3s / `read` 5s — shorter than haystack's, since every OneMap call here has a cheap fallback and sits on the synchronous quote/validation path), nested `Resilience` (CB failure-rate/window/min-calls/wait, one `bulkheadMaxConcurrent`), `tokenRefreshBuffer`.
   - `Coordinates` — `record(double latitude, double longitude, String address)`.
   - `OneMapException` — same shape as `HaystackException`: `status`, `errorCode`, `Kind` enum (`CLIENT`/`UPSTREAM`/`TIMEOUT`/`UNAVAILABLE`/`TRANSPORT`), `isRetryable()`.
   - `dto/` — `OneMapTokenRequest`, `OneMapTokenResponse`, `OneMapSearchResponse`, `OneMapSearchResult`.
   - `OneMapAuthService` — thread-safe cached-token holder. No existing precedent in this codebase for token caching; new design: `AtomicReference<CachedToken>` (`record CachedToken(String token, Instant expiresAt)`) with double-checked-locking refresh so concurrent callers don't all hit `/getToken` at once when stale. Injectable `java.time.Clock` (default `Clock.systemUTC()`) purely so tests can control "now" deterministically.
   - `OneMapClient` — `Optional<Coordinates> geocode(String postalCode)`. Throws `OneMapException` only when OneMap itself is broken (network/5xx/timeout/CB-open/bulkhead-full); returns `Optional.empty()` when OneMap responds with no match — these are semantically different and callers (`DistanceService` vs `PostalCodeService`) treat them differently. Wraps the call with `Bulkhead.decorateSupplier(...)` → `CircuitBreaker.decorateSupplier(...)`, same pattern as `HaystackPricingClient.quote()`.
     - **In-memory cache**: `ConcurrentHashMap<String, Optional<Coordinates>>`, keyed by the 6-digit postal code. Deliberately not a new caching dependency (no `spring-boot-starter-cache`/Caffeine exists in this app today, and none is warranted just for this) — Singapore postal-code→coordinate mappings are effectively immutable, and the realistic key space this app touches (one fixed origin + whatever destination postal codes customers enter) is small. Only definitive results are cached (a success or a confirmed not-found); an `OneMapException` is **never** cached, so a transient OneMap outage self-heals on the very next call.
     - **No Resilience4j `Retry`** (deliberate deviation from `HaystackPricingClient`, which does retry): the business consequence of a failed geocode is "fall back to a configurable constant," not "the customer's action fails." An in-call retry only adds latency to every cold-cache request for no correctness benefit, since a transient failure already self-heals on the next call (failures aren't cached).
     - **One shared `Bulkhead`**, not per-operation like haystack's four: there's only one operation shape here (`geocode`), used by two callers (`DistanceService`, `PostalCodeService`) — splitting it would isolate nothing real.
   - `OneMapClientConfig` (`@Configuration @EnableConfigurationProperties(OneMapProperties.class)`) — mirrors `HaystackClientConfig`: `RestClient.Builder`, a dedicated `onemap` `CircuitBreaker` (independent failure domain from `haystack`'s — one external system failing shouldn't fail-fast the other), `Bulkhead`, `OneMapAuthService` bean, `OneMapClient` bean. Duplicates the small `buildRestClient(builder, baseUrl, connect, read)` factory rather than reaching into `HaystackClientConfig` (package-private `static` there) — keeps `client/onemap` self-contained.

2. **`util/PostalCodeUtil`** (new package `com.heavy_rental.rest_api.util`) — `isWellFormed(String)` (`^\d{6}$`) and `extractTrailing6Digits(String siteAddress)` (Java equivalent of `Booking.sitePostalCode`'s SQL `@Formula` substring logic: last 6 characters, `null` if too short or not well-formed after stripping). Used by `RentalPlanService`, `DistanceService`, `PostalCodeService` — three call sites justify a shared utility over a third copy of the regex. **Not** retrofitted into `RentalPlanCreateRequest`/`CreateBookingRequest`/`BookingUpdateRequest`'s existing `@Pattern` validation — out of scope, keeps this change's blast radius contained to what it needs.

3. **`DistanceService`** (new, `service/`):
   ```java
   public double resolveDistanceKm(RentalPlan plan) {
       if (!pricingProperties.distanceLookupEnabled()) {
           return pricingProperties.defaultDistanceKm();
       }
       String destination = plan.getSitePostalCode();
       if (!PostalCodeUtil.isWellFormed(destination)) {
           return pricingProperties.defaultDistanceKm(); // no OneMap call at all
       }
       try {
           var origin = oneMapClient.geocode(pricingProperties.originPostalCode());
           var dest = oneMapClient.geocode(destination);
           if (origin.isEmpty() || dest.isEmpty()) {
               return pricingProperties.defaultDistanceKm();
           }
           return haversineKm(origin.get(), dest.get());
       } catch (OneMapException ex) {
           log.warn("distance lookup unavailable for plan {} ({}: {}) — using default distance",
                   plan.getId(), ex.getErrorCode(), ex.getMessage());
           return pricingProperties.defaultDistanceKm();
       }
   }
   ```
   Fallback-catching lives **inside** `DistanceService`, not in `DynamicPricingService`'s existing `catch (HaystackException ex)` block — each service owns its own failure domain and never throws for it, mirroring the existing `DefaultPricingClient` fallback pattern. This keeps `DynamicPricingService`'s call site a one-line, always-succeeds call, and keeps both services independently unit-testable.

4. **Wiring**:
   - `PricingProperties` (record) gains two new components: `@DefaultValue("629462") String originPostalCode` and `@DefaultValue("true") boolean distanceLookupEnabled` — an operational kill-switch, mirroring the existing precedent of `haystack.retry.ingest-enabled` ("keep false until confirmed on target env") for gating a new external-call path independently of the broader `pricing.dynamic-enabled` flag it lives under. Two existing test call sites (`DynamicPricingServiceTest.java:51,75`, currently `new PricingProperties(bool, double)`) need updating for the new arity.
   - `DynamicPricingService.fetchResults()` (line 101): `pricingProperties.defaultDistanceKm()` → `distanceService.resolveDistanceKm(plan)`.
   - `RentalPlanService.create()` (line 80), right after `plan.setSiteAddress(request.siteAddress())`: `plan.setSitePostalCode(PostalCodeUtil.extractTrailing6Digits(request.siteAddress()))`. `RentalPlanCreateRequest.siteAddress` is already `@NotBlank` + `@Pattern(".*\\d{6}$")`-validated, so extraction should always succeed here; `DistanceService` already treats a `null` defensively as "use default."

5. **`GET /api/postalCodes/{postalCode}`** (new controller):
   - `PostalCodeValidationResponse` — `record(String status, String postalCode, String address, String message)`, `@JsonInclude(NON_NULL)`.
   - `PostalCodeService.validate(String raw)`:
     - Not `^\d{6}$` → `throw new ResponseStatusException(BAD_REQUEST, "Postal code must be exactly 6 digits")` — existing `RestExceptionHandler` idiom, no new exception handler needed → `400 {"error":"bad_request","message":"..."}`.
     - `oneMapClient.geocode(postalCode)` present → `200 {"status":"VALID","postalCode":"...","address":"..."}`.
     - Empty → `200 {"status":"INVALID","postalCode":"...","message":"No address found for this postal code"}`.
     - `OneMapException` → `503 {"status":"UNAVAILABLE","postalCode":"...","message":"Postal code lookup is temporarily unavailable — you may continue"}`.
   - `PostalCodeController` — `@RestController @RequestMapping("/api/postalCodes")`, `GET /{postalCode}`. No `SecurityConfig` change (falls under the existing `anyRequest().hasAnyAuthority("ROLE_USER","ROLE_ADMIN")` default).

## Fallback semantics (must decide, locked here)

Same principle as `dynamic-plan-quote-pricing`'s design.md: **never let a quote fail because an external geocoding dependency is unavailable.** Every OneMap failure mode inside `DistanceService` (missing/malformed postal code, empty geocode result, `OneMapException` of any `Kind`, or `pricing.distance-lookup-enabled=false`) falls back to `pricing.default-distance-km` silently to the caller, logged at `WARN` with the plan id for ops visibility. The postal-code **validation** endpoint is the one place a OneMap problem *is* surfaced to the caller (as `503 UNAVAILABLE`) — but even there, the product decision (see proposal.md) is that the frontend must not hard-block the user on that response, only on a genuine `INVALID`.

## Rollout

- `pricing.distance-lookup-enabled` (env `PRICING_DISTANCE_LOOKUP_ENABLED`, default `true`) — independent kill-switch for the OneMap-backed distance lookup specifically, separate from `pricing.dynamic-enabled` which gates the whole dynamic-pricing path. If flipped off, `DistanceService` returns `pricing.default-distance-km` without ever calling OneMap, e.g. if OneMap needs to be pulled out of the request path in an incident without disabling dynamic pricing entirely.
- No flag needed for the postal-code validation endpoint itself — a `503` on OneMap trouble is already a safe, self-describing failure mode for a GET the frontend already tolerates non-blockingly.

## Correlation / logging

No `X-Correlation-Id` threading for OneMap calls (unlike haystack) — these are lookups, not the primary business call the request exists to make, and failures are always absorbed locally. Plan id (for `DistanceService`) or the raw postal code (for `PostalCodeService`) is enough context in `WARN` logs to debug a specific incident.

## Follow-on: optional `siteAddress` at plan creation

See proposal.md for the "why." Verified by reading every call site before proposing this — no
production code beyond the DTO annotation needs to change:

1. **`RentalPlanCreateRequest`** (`dto/RentalPlanCreateRequest.java`) — remove `@NotBlank(message =
   "Site address is required")` from `siteAddress`, keep `@Pattern(regexp = "^.*\\d{6}$", ...)`
   as-is. Jakarta Bean Validation's `@Pattern` only validates non-null values by spec — `null`
   already passes today without any change; an empty string `""` still fails the regex (does not
   end in 6 digits) and stays rejected, matching "omitted or null succeeds, garbage still doesn't."
   The compact constructor's `siteAddress == null ? null : siteAddress.strip()` is already
   null-safe.
2. **`RentalPlanService.create()`** — no change. `plan.setSiteAddress(request.siteAddress())` and
   `plan.setSitePostalCode(PostalCodeUtil.extractTrailing6Digits(request.siteAddress()))` already
   handle `null` correctly (`extractTrailing6Digits(null)` returns `null`, already covered by
   `PostalCodeUtilTest`).
3. **`DistanceService.resolveDistanceKm()`** — no change. A plan with `sitePostalCode == null`
   already hits the `!PostalCodeUtil.isWellFormed(destinationPostalCode)` branch and falls back to
   `pricing.default-distance-km`, same as today's malformed-address case.
4. **`RentalPlanServiceTest`** — new test: `create()` with `siteAddress` omitted (pass `null` in
   the record constructor) succeeds, and the saved `RentalPlan` has `siteAddress == null` and
   `sitePostalCode == null` (extend the `ArgumentCaptor<RentalPlan>` pattern already used for the
   `sitePostalCode` extraction test).
5. **`openspec/specs/rental-plan-quote/spec.md` FR-RP-008** — reword to:
   > `POST /api/rentalPlans` `siteAddress` is OPTIONAL. WHEN PROVIDED, it MUST be non-blank and end
   > with a 6-digit postal code... Leading/trailing whitespace MUST be stripped before validation.
   > A present-but-invalid address MUST return `400` `validation_failed` before the one-active-plan
   > check or any persist. `RentalPlan.siteAddress`/`sitePostalCode` remain nullable.

   Split the existing "Missing postal code rejected" scenario into two: rename it "Malformed
   postal code rejected" (drop "or missing" from the GIVEN/WHEN, since that's no longer true), and
   add a new "Scenario: Omitted address accepted" (POST with no `siteAddress` → `201`, plan created
   with `siteAddress: null`).

No change needed to `RentalPlanResponse` — `siteAddress` is already a plain nullable `String`
field there; `null` serializes the same unremarkable way any other nullable response field does.

## Follow-on: `PATCH /api/rentalPlans/{id}` (secondary, optional)

Only build this if explicitly confirmed in scope — proposal.md flags it as optional/non-blocking.

1. **`RentalPlanUpdateRequest`** (new `dto/`) — mirrors `BookingUpdateRequest`'s shape: single
   nullable `siteAddress` field, `@Pattern(regexp = "^.*\\d{6}$", ...)` applied only when non-null
   (Bean Validation's default null-passes behavior handles this for free, same as the create DTO
   post-relaxation) — no `@NotBlank`, since omitting the field (or sending `null`) should be a
   no-op, not an error, given PATCH semantics.
2. **`RentalPlanService.updateSiteAddress(Long id, RentalPlanUpdateRequest request, String
   customerEmail)`** (new method):
   - Load plan, verify ownership (`404` if not owner — same convention as every other
     plan-scoped method in this service), `409`/appropriate error if `CONVERTED`/`CANCELLED`
     (mirrors `cancel()`'s existing terminal-state guards — an update to a plan that's already
     become a booking or been cancelled doesn't make sense).
   - Set `siteAddress` + re-derive `sitePostalCode` via `PostalCodeUtil.extractTrailing6Digits(...)`
     (same call already used in `create()`).
   - **If current status is `QUOTED`**: also set status → `DRAFT`, `totalAmount` → `null` — copy
     the exact revert pattern `addItem()`/`removeItem()` already use for FR-RP-002/FR-RP-003, for
     the same reason (a stored total that no longer matches what a fresh quote would compute is a
     latent pricing bug, not a cosmetic issue).
   - Refresh `updatedAt`, save, return `toResponse(plan)`.
3. **`RentalPlanController`** — `@PatchMapping("/{id}")`, `@RequestBody @Valid
   RentalPlanUpdateRequest`, delegates to the new service method. No `SecurityConfig` change
   (falls under the existing per-plan ownership pattern already enforced inside the service, same
   as every other `/api/rentalPlans/**` route).
4. **Tests**: `RentalPlanServiceTest` additions — happy path on `DRAFT`, happy path on `QUOTED`
   (asserts revert-to-`DRAFT` + cleared `totalAmount`), non-owner → `404`, `CONVERTED`/`CANCELLED`
   → rejected. Consider whether this is also the moment to add the first
   `RentalPlanControllerIntegrationTest` (none exists today for this controller at all) — not
   required, but this is new API surface, unlike the rest of this change's work which extended
   existing routes.
5. **`openspec/specs/rental-plan-quote/spec.md`** — new `FR-RP-011 Update site address` requirement
   documenting the route, the ownership/terminal-state rules, and the QUOTED-reverts-to-DRAFT rule,
   with BDD scenarios for each.
6. **Docs**: `openspec/specs/api-index/contracts/routes.md` gains a `PATCH /api/rentalPlans/{id}`
   row; `postman/` collection gains a request in folder 7 (Rental Plans) + a `README.md` mention.
