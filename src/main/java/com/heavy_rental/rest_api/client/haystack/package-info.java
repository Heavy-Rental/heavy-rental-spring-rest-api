/**
 * Outbound Spring client for <strong>haystack-fast-api</strong> (S2b / Phase 2 C1).
 *
 * <h2>Call model (normative)</h2>
 * <table>
 *   <caption>Haystack hops</caption>
 *   <tr><th>Call</th><th>Path</th><th>Role</th></tr>
 *   <tr><td>1</td><td>{@code POST .../submitprojectspecification}</td><td>Ingest (JSON or multipart)</td></tr>
 *   <tr><td>2</td><td>{@code POST .../project-knowledge/getassetrecommendations}</td><td>Recommend / quote</td></tr>
 *   <tr><td>3</td><td>{@code POST .../project-knowledge/query}</td><td>Chatbot Q&amp;A</td></tr>
 *   <tr><td>—</td><td>{@code GET /health}</td><td>Liveness</td></tr>
 * </table>
 *
 * <h2>Resilience</h2>
 * Programmatic Resilience4j: shared circuit breaker {@code haystack}; bulkheads
 * ingest / recommend / qa; limited retries with exponential backoff.
 * Call 1 sends {@code Idempotency-Key} (reuse on retry; prod default retry off until S2a).
 * All calls send {@code X-Correlation-Id}.
 *
 * <h2>Hard rules</h2>
 * <ul>
 *   <li>Never re-ingest when Call 2 or Call 3 fails</li>
 *   <li>Never invent equipment or prices when CB open / bulkhead full / timeout</li>
 *   <li>4xx is not a success-path retry</li>
 * </ul>
 *
 * <h2>TDD / BDD</h2>
 * Behaviour is specified by OpenSpec FR-S2B-* and Feasibility plan §7 scenarios.
 * Change behaviour only with a failing test first (WireMock/unit), then implementation.
 * Executable scenarios live under {@code src/test/.../client/haystack} and
 * {@code .../service/Recommender*}, {@code .../controller/RecommendationControllerIntegrationTest}.
 *
 * @see com.heavy_rental.rest_api.service.RecommenderSagaService
 * @see com.heavy_rental.rest_api.controller.RecommendationController
 */
package com.heavy_rental.rest_api.client.haystack;
