package com.heavy_rental.rest_api.client.haystack;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecRequest;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * BDD: FR-S2B-002/008 — N× 500 opens circuit; fail-fast recommender_unavailable (plan §7 #2).
 */
@DisplayName("Haystack circuit breaker")
class HaystackCircuitBreakerTest {

	private WireMockServer wireMock;
	private HaystackRecommenderClient client;
	private CircuitBreaker circuitBreaker;

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
		properties.getRetry().setIngestEnabled(false);

		circuitBreaker = CircuitBreaker.of("cb-test", CircuitBreakerConfig.custom()
				.failureRateThreshold(50f)
				.slidingWindowSize(4)
				.minimumNumberOfCalls(4)
				.waitDurationInOpenState(Duration.ofSeconds(60))
				.recordException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());

		Bulkhead bh = Bulkhead.of("cb-bh", BulkheadConfig.custom().maxConcurrentCalls(10).build());
		Retry noRetry = Retry.of("cb-retry", RetryConfig.custom().maxAttempts(1).build());

		client = new HaystackRecommenderClient(
				properties, RestClient.builder(), new ObjectMapper(),
				circuitBreaker, bh, bh, bh, noRetry, noRetry, noRetry);

		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(500)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"internal_error\",\"message\":\"down\"}")));
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	@DisplayName("Scenario: Circuit opens after repeated 5xx and subsequent calls fail fast without HTTP")
	@Test
	void circuitOpens_thenFailFastWithoutCallingHaystack() {
		IngestFromProjectSpecRequest body = new IngestFromProjectSpecRequest("1", null, "x", null, null);

		for (int i = 0; i < 4; i++) {
			final String key = "k-" + i;
			HaystackException ex = assertThrows(HaystackException.class,
					() -> client.ingest(body, key, "c"));
			assertEquals(HaystackException.Kind.UPSTREAM, ex.getKind());
		}

		assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

		int countBefore = wireMock.findAll(postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))).size();

		HaystackException openEx = assertThrows(HaystackException.class,
				() -> client.ingest(body, "k-open", "c"));
		assertEquals(HaystackException.Kind.UNAVAILABLE, openEx.getKind());
		assertEquals("recommender_unavailable", openEx.getErrorCode());
		assertTrue(openEx.getMessage().toLowerCase().contains("circuit"));

		int countAfter = wireMock.findAll(postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))).size();
		assertEquals(countBefore, countAfter, "open circuit must not call haystack");
	}
}
