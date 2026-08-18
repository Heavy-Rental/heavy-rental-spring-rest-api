package com.heavy_rental.rest_api.client.haystack;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * BDD: FR-S2B-003 — ingest retries reuse the same Idempotency-Key (plan §7 #5).
 */
@DisplayName("Haystack ingest retry — Idempotency-Key")
class HaystackRetryIdempotencyTest {

	private WireMockServer wireMock;
	private HaystackRecommenderClient client;

	@BeforeEach
	void setUp() {
		wireMock = new WireMockServer(wireMockConfig().dynamicPort());
		wireMock.start();

		HaystackProperties properties = new HaystackProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		properties.getTimeouts().setIngestRead(Duration.ofSeconds(2));
		properties.getTimeouts().setHealthRead(Duration.ofSeconds(2));
		properties.getTimeouts().setQaRead(Duration.ofSeconds(2));
		properties.getTimeouts().setRecommendRead(Duration.ofSeconds(2));
		properties.getRetry().setIngestEnabled(true);
		properties.getRetry().setIngestMaxAttempts(2);
		properties.getResilience().setCircuitBreakerMinimumNumberOfCalls(100);
		properties.getResilience().setCircuitBreakerSlidingWindowSize(100);

		CircuitBreaker cb = CircuitBreaker.of("retry-cb", CircuitBreakerConfig.custom()
				.minimumNumberOfCalls(100)
				.slidingWindowSize(100)
				.recordException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Bulkhead bh = Bulkhead.of("retry-bh", BulkheadConfig.custom().maxConcurrentCalls(10).build());
		Retry ingestRetry = Retry.of("retry-ingest", RetryConfig.custom()
				.maxAttempts(2)
				.waitDuration(Duration.ofMillis(20))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Retry noRetry = Retry.of("retry-none", RetryConfig.custom().maxAttempts(1).build());

		client = new HaystackRecommenderClient(
				properties, RestClient.builder(), new ObjectMapper(),
				cb, bh, bh, bh, ingestRetry, noRetry, noRetry);
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	@DisplayName("Scenario: Ingest 5xx retry reuses the same Idempotency-Key and correlation id")
	@Test
	void ingestRetry_reusesSameIdempotencyKey() {
		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.inScenario("retry")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse()
						.withStatus(500)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"internal_error\",\"message\":\"temp\"}"))
				.willSetStateTo("second"));

		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.inScenario("retry")
				.whenScenarioStateIs("second")
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{"ingest_id":"ing_retry","user_id":"1","user_requirement_summary":"ok",
								 "needs_summary":[],"warnings":[]}
								""")));

		IngestFromProjectSpecResponse resp = client.ingest(
				new IngestFromProjectSpecRequest("1", null, "text", null, null),
				"same-key-uuid",
				"corr-retry");

		assertEquals("ing_retry", resp.ingestId());
		wireMock.verify(2, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.withHeader(HaystackRecommenderClient.HEADER_IDEMPOTENCY_KEY, equalTo("same-key-uuid"))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-retry")));
	}
}
