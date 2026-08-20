package com.heavy_rental.rest_api.client.haystack;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsRequest;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsResponse;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecRequest;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecResponse;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryRequest;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryResponse;
import com.heavy_rental.rest_api.client.haystack.IngestMultipartCommand;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * BDD/TDD: WireMock specs for {@link HaystackRecommenderClient}.
 * Covers FR-S2B-001 (timeouts/client), FR-S2B-003/004 (headers), FR-S2B-006 (4xx/5xx mapping),
 * Call 1 multipart, Call 2 quote DTO, Call 3 answer DTO.
 */
@DisplayName("HaystackRecommenderClient — wire contract & headers")
class HaystackRecommenderClientTest {

	private WireMockServer wireMock;
	private HaystackRecommenderClient client;
	private HaystackProperties properties;

	@BeforeEach
	void setUp() {
		wireMock = new WireMockServer(wireMockConfig().dynamicPort());
		wireMock.start();

		properties = new HaystackProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		properties.getTimeouts().setHealthRead(Duration.ofSeconds(2));
		properties.getTimeouts().setQaRead(Duration.ofSeconds(2));
		properties.getTimeouts().setRecommendRead(Duration.ofSeconds(2));
		properties.getTimeouts().setIngestRead(Duration.ofSeconds(2));
		properties.getRetry().setIngestEnabled(false);
		properties.getRetry().setIngestMaxAttempts(2);
		properties.getRetry().setRecommendMaxAttempts(1);
		properties.getRetry().setQaMaxAttempts(1);
		properties.getResilience().setCircuitBreakerMinimumNumberOfCalls(50);
		properties.getResilience().setCircuitBreakerSlidingWindowSize(50);

		client = buildClient(properties);
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	private static HaystackRecommenderClient buildClient(HaystackProperties properties) {
		CircuitBreaker cb = CircuitBreaker.of("haystack-test", CircuitBreakerConfig.custom()
				.failureRateThreshold(properties.getResilience().getCircuitBreakerFailureRateThreshold())
				.slidingWindowSize(properties.getResilience().getCircuitBreakerSlidingWindowSize())
				.minimumNumberOfCalls(properties.getResilience().getCircuitBreakerMinimumNumberOfCalls())
				.waitDurationInOpenState(Duration.ofSeconds(30))
				.recordException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Bulkhead ingestBh = Bulkhead.of("ingest-test", BulkheadConfig.custom()
				.maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build());
		Bulkhead recommendBh = Bulkhead.of("recommend-test", BulkheadConfig.custom()
				.maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build());
		Bulkhead qaBh = Bulkhead.of("qa-test", BulkheadConfig.custom()
				.maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build());
		Retry ingestRetry = Retry.of("ingest-retry", RetryConfig.custom()
				.maxAttempts(properties.getRetry().isIngestEnabled()
						? properties.getRetry().getIngestMaxAttempts()
						: 1)
				.waitDuration(Duration.ofMillis(10))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Retry recommendRetry = Retry.of("recommend-retry", RetryConfig.custom()
				.maxAttempts(properties.getRetry().getRecommendMaxAttempts())
				.waitDuration(Duration.ofMillis(10))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Retry qaRetry = Retry.of("qa-retry", RetryConfig.custom()
				.maxAttempts(properties.getRetry().getQaMaxAttempts())
				.waitDuration(Duration.ofMillis(10))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		return new HaystackRecommenderClient(
				properties,
				RestClient.builder(),
				new ObjectMapper(),
				cb,
				ingestBh,
				recommendBh,
				qaBh,
				ingestRetry,
				recommendRetry,
				qaRetry);
	}

	@Test
	@DisplayName("Scenario: Call 1 happy path sends Idempotency-Key and X-Correlation-Id and maps lean body")
	void ingestHappyPath_sendsHeadersAndMapsLeanBody() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "ingest_id": "ing_test_1",
								  "user_id": "42",
								  "user_requirement_summary": "Need scissors lift",
								  "tentative_start_date": "2026-09-01",
								  "tentative_end_date": "2026-09-12",
								  "needs_summary": [],
								  "expected_budget": null,
								  "warnings": []
								}
								""")));

		IngestFromProjectSpecResponse resp = client.ingest(
				new IngestFromProjectSpecRequest("42", "Demo", "Need scissors lift", "2026-09-01", "2026-09-12"),
				"key-1",
				"corr-1");

		assertEquals("ing_test_1", resp.ingestId());
		assertEquals("42", resp.userId());
		assertEquals("Need scissors lift", resp.userRequirementSummary());

		wireMock.verify(postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.withHeader(HaystackRecommenderClient.HEADER_IDEMPOTENCY_KEY, equalTo("key-1"))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-1"))
				.withRequestBody(containing("project_text")));
	}

	@Test
	@DisplayName("Scenario: Call 2 recommend maps quoteRef/items and sends correlation")
	void recommendHappyPath_sendsCorrelationAndMapsQuote() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "user_id": "42",
								  "ingest_id": "ing_test_1",
								  "query": "Need scissors lift",
								  "quoteRef": "QUO-1001",
								  "confidenceScore": 0.91,
								  "days": 10,
								  "estimatedTotal": 1500.00,
								  "specSummary": "Indoor elevated access",
								  "rationale": "Scissor lift fits height/access",
								  "items": [
								    {
								      "rankOrder": 1,
								      "matchScore": 0.95,
								      "reason": "Fits indoor height",
								      "quantity": 1,
								      "equipment": { "id": "asset-3", "name": "Genie GS-1930", "category": "Scissor Lift" },
								      "baseDailyRate": 150.00,
								      "lineTotal": 1500.00
								    }
								  ],
								  "warnings": []
								}
								""")));

		GetAssetRecommendationsResponse resp = client.recommend(
				new GetAssetRecommendationsRequest("42", "ing_test_1", "Need scissors lift", 5),
				"corr-rec");

		assertEquals("QUO-1001", resp.quoteRef());
		assertNotNull(resp.items());
		assertEquals(1, resp.items().size());
		assertEquals("asset-3", resp.items().get(0).equipment().id());
		assertEquals("Genie GS-1930", resp.items().get(0).equipment().name());
		assertEquals(new BigDecimal("0.95"), resp.items().get(0).matchScore());
		assertEquals("Fits indoor height", resp.items().get(0).reason());
		assertEquals(Integer.valueOf(1), resp.items().get(0).quantity());
		assertEquals(new BigDecimal("150.00"), resp.items().get(0).baseDailyRate());
		assertEquals(new BigDecimal("1500.00"), resp.items().get(0).lineTotal());

		wireMock.verify(postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-rec")));
	}

	@Test
	@DisplayName("Scenario: Call 2 maps collapsed quantity from realistic Haystack JSON (FR-P-013)")
	void recommend_mapsCollapsedQuantityFromHaystackPayload() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "user_id": "42",
								  "ingest_id": "ing_test_1",
								  "query": "Need three forklifts",
								  "quoteRef": "QUO-136",
								  "confidenceScore": 0.8,
								  "days": 10,
								  "estimatedTotal": 5318.4,
								  "items": [
								    {
								      "rankOrder": 3,
								      "matchScore": 0.8,
								      "reason": "Matched forklift to Hyster H4.2FT Forklift",
								      "lineTotal": 5318.4,
								      "quantity": 3,
								      "needId": "need_3",
								      "mlPredictedPrice": 177.28,
								      "equipment": {
								        "id": "27",
								        "name": "Hyster H4.2FT Forklift",
								        "category": "Fork Lift",
								        "baseDailyRate": 177.28,
								        "capacity": 4200.0,
								        "extra": {
								          "availability": "available",
								          "currency": "SGD"
								        }
								      }
								    }
								  ],
								  "warnings": []
								}
								""")));

		GetAssetRecommendationsResponse resp = client.recommend(
				new GetAssetRecommendationsRequest("42", "ing_test_1", "Need three forklifts", 5),
				"corr-qty");

		assertEquals(1, resp.items().size());
		assertEquals(Integer.valueOf(3), resp.items().get(0).quantity());
		assertEquals(new BigDecimal("5318.4"), resp.items().get(0).lineTotal());
		assertEquals("27", resp.items().get(0).equipment().id());
		assertEquals("Hyster H4.2FT Forklift", resp.items().get(0).equipment().name());
	}

	@Test
	@DisplayName("Scenario: Call 3 chatbot uses .../query path and maps answer")
	void queryHappyPath_usesCall3PathAndMapsAnswer() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_QUERY))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "answer": "Capacity is 20 tons",
								  "sources_used": ["vector_store"]
								}
								""")));

		ProjectKnowledgeQueryResponse resp = client.queryProjectKnowledge(
				new ProjectKnowledgeQueryRequest("42", "ing_test_1", "What capacity?", 5, null),
				"corr-qa");

		assertEquals("Capacity is 20 tons", resp.answer());
		assertNotNull(resp.sourcesUsed());
		assertEquals(1, resp.sourcesUsed().size());

		wireMock.verify(postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_QUERY))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-qa")));
	}

	@Test
	@DisplayName("Scenario: Call 1 multipart ingest sends headers and maps lean body")
	void ingestMultipart_sendsHeadersAndMapsLeanBody() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "ingest_id": "ing_mp_1",
								  "user_id": "42",
								  "user_requirement_summary": "From file",
								  "needs_summary": [],
								  "warnings": []
								}
								""")));

		IngestFromProjectSpecResponse resp = client.ingestMultipart(
				new IngestMultipartCommand(
						"42",
						"Demo",
						"Optional text",
						"2026-09-01",
						"2026-09-12",
						"hello project".getBytes(),
						"spec.txt",
						"text/plain"),
				"key-mp",
				"corr-mp");

		assertEquals("ing_mp_1", resp.ingestId());
		wireMock.verify(postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.withHeader(HaystackRecommenderClient.HEADER_IDEMPOTENCY_KEY, equalTo("key-mp"))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-mp"))
				.withHeader("Content-Type", containing("multipart/form-data")));
	}

	@Test
	@DisplayName("Scenario: Health call propagates X-Correlation-Id")
	void health_sendsCorrelation() {
		wireMock.stubFor(get(urlEqualTo(HaystackRecommenderClient.PATH_HEALTH))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"status\":\"ok\",\"database\":\"up\"}")));

		var health = client.health("corr-health");
		assertEquals("ok", health.status());
		wireMock.verify(getRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_HEALTH))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-health")));
	}

	@Test
	@DisplayName("Scenario: FastAPI 400 is mapped and not success-retried")
	void ingest4xx_mapsClientErrorAndDoesNotRetry() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(400)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"bad_request\",\"message\":\"empty project\"}")));

		HaystackException ex = assertThrows(HaystackException.class, () -> client.ingest(
				new IngestFromProjectSpecRequest("42", null, "x", null, null),
				"key-4xx",
				"corr-4xx"));

		assertEquals(HaystackException.Kind.CLIENT, ex.getKind());
		assertEquals("bad_request", ex.getErrorCode());
		assertTrue(ex.getMessage().contains("empty project"));
		wireMock.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST)));
	}

	@Test
	@DisplayName("Scenario: FastAPI 500 maps to retryable UPSTREAM kind")
	void ingest5xx_mapsUpstream() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(500)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"internal_error\",\"message\":\"boom\"}")));

		HaystackException ex = assertThrows(HaystackException.class, () -> client.ingest(
				new IngestFromProjectSpecRequest("42", null, "x", null, null),
				"key-5xx",
				"corr-5xx"));

		assertEquals(HaystackException.Kind.UPSTREAM, ex.getKind());
		assertTrue(ex.isRetryable());
	}
}
