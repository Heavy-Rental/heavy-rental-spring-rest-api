package com.heavy_rental.rest_api.client.haystack;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecRequest;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecResponse;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Plan §7 scenario 1: delayed ingest &gt; read timeout; retry reuses same {@code Idempotency-Key}.
 */
/**
 * BDD: plan §7 #1 — delayed ingest greater than read timeout; retry same key (FR-S2B-001/003).
 */
@DisplayName("Haystack ingest timeout retry")
class HaystackTimeoutRetryTest {

	private WireMockServer wireMock;
	private HaystackRecommenderClient client;

	@BeforeEach
	void setUp() {
		wireMock = new WireMockServer(wireMockConfig().dynamicPort());
		wireMock.start();

		HaystackProperties properties = new HaystackProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		// Short read timeout so WireMock fixed delay forces client timeout
		properties.getTimeouts().setIngestRead(Duration.ofMillis(400));
		properties.getTimeouts().setHealthRead(Duration.ofSeconds(2));
		properties.getTimeouts().setQaRead(Duration.ofSeconds(2));
		properties.getTimeouts().setRecommendRead(Duration.ofSeconds(2));
		properties.getRetry().setIngestEnabled(true);
		properties.getRetry().setIngestMaxAttempts(2);
		properties.getResilience().setCircuitBreakerMinimumNumberOfCalls(100);
		properties.getResilience().setCircuitBreakerSlidingWindowSize(100);

		CircuitBreaker cb = CircuitBreaker.of("timeout-cb", CircuitBreakerConfig.custom()
				.minimumNumberOfCalls(100)
				.slidingWindowSize(100)
				.recordException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Bulkhead bh = Bulkhead.of("timeout-bh", BulkheadConfig.custom().maxConcurrentCalls(10).build());
		Retry ingestRetry = Retry.of("timeout-ingest", RetryConfig.custom()
				.maxAttempts(2)
				.waitDuration(Duration.ofMillis(50))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Retry noRetry = Retry.of("timeout-none", RetryConfig.custom().maxAttempts(1).build());

		client = new HaystackRecommenderClient(
				properties, RestClient.builder(), new ObjectMapper(),
				cb, bh, bh, bh, ingestRetry, noRetry, noRetry);
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	@DisplayName("Scenario: Ingest times out once then retries with the same Idempotency-Key")
	@Test
	void ingestTimeout_thenRetry_reusesSameIdempotencyKey() {
		// First attempt: server delays longer than client read timeout → TIMEOUT (retryable)
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.inScenario("timeout-retry")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse()
						.withFixedDelay(1_200)
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{"ingest_id":"ing_slow","user_id":"1","user_requirement_summary":"slow",
								 "needs_summary":[],"warnings":[]}
								"""))
				.willSetStateTo("fast"));

		// Second attempt: fast success
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.inScenario("timeout-retry")
				.whenScenarioStateIs("fast")
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{"ingest_id":"ing_timeout_retry","user_id":"1","user_requirement_summary":"ok",
								 "needs_summary":[],"warnings":[]}
								""")));

		IngestFromProjectSpecResponse resp = client.ingest(
				new IngestFromProjectSpecRequest("1", null, "text", null, null),
				"timeout-key-uuid",
				"corr-timeout");

		assertEquals("ing_timeout_retry", resp.ingestId());
		wireMock.verify(2, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.withHeader(HaystackRecommenderClient.HEADER_IDEMPOTENCY_KEY, equalTo("timeout-key-uuid"))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-timeout")));
	}

	@DisplayName("Scenario: Ingest timeout without retry maps to recommender_timeout")
	@Test
	void ingestTimeout_withoutRetry_surfacesTimeout() {
		HaystackProperties properties = new HaystackProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		properties.getTimeouts().setIngestRead(Duration.ofMillis(300));
		properties.getTimeouts().setHealthRead(Duration.ofSeconds(2));
		properties.getTimeouts().setQaRead(Duration.ofSeconds(2));
		properties.getTimeouts().setRecommendRead(Duration.ofSeconds(2));
		properties.getRetry().setIngestEnabled(false);
		properties.getResilience().setCircuitBreakerMinimumNumberOfCalls(100);
		properties.getResilience().setCircuitBreakerSlidingWindowSize(100);

		CircuitBreaker cb = CircuitBreaker.of("to-only-cb", CircuitBreakerConfig.custom()
				.minimumNumberOfCalls(100)
				.slidingWindowSize(100)
				.recordException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Bulkhead bh = Bulkhead.of("to-only-bh", BulkheadConfig.custom().maxConcurrentCalls(10).build());
		Retry noRetry = Retry.of("to-only-retry", RetryConfig.custom().maxAttempts(1).build());
		HaystackRecommenderClient noRetryClient = new HaystackRecommenderClient(
				properties, RestClient.builder(), new ObjectMapper(),
				cb, bh, bh, bh, noRetry, noRetry, noRetry);

		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withFixedDelay(1_000)
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"ingest_id\":\"x\",\"user_id\":\"1\"}")));

		HaystackException ex = org.junit.jupiter.api.Assertions.assertThrows(
				HaystackException.class,
				() -> noRetryClient.ingest(
						new IngestFromProjectSpecRequest("1", null, "text", null, null),
						"k",
						"c"));

		assertEquals(HaystackException.Kind.TIMEOUT, ex.getKind());
		assertEquals("recommender_timeout", ex.getErrorCode());
		assertTrue(ex.isRetryable());
	}
}
