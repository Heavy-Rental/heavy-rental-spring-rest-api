package com.heavy_rental.rest_api.client.haystack;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteRequest;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteRequestItem;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteResponse;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * BDD/TDD: WireMock specs for {@link HaystackPricingClient} — rental-plan quote pricing
 * (see {@code openspec/changes/dynamic-plan-quote-pricing/}). Independent of
 * {@link HaystackRecommenderClient}'s ingest/recommend/Q&amp;A saga.
 */
@DisplayName("HaystackPricingClient — wire contract & headers")
class HaystackPricingClientTest {

	private WireMockServer wireMock;
	private HaystackPricingClient client;

	@BeforeEach
	void setUp() {
		wireMock = new WireMockServer(wireMockConfig().dynamicPort());
		wireMock.start();

		HaystackProperties properties = new HaystackProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		properties.getTimeouts().setPricingRead(Duration.ofSeconds(2));
		properties.getResilience().setCircuitBreakerMinimumNumberOfCalls(50);
		properties.getResilience().setCircuitBreakerSlidingWindowSize(50);

		client = buildClient(properties);
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	private static HaystackPricingClient buildClient(HaystackProperties properties) {
		CircuitBreaker cb = CircuitBreaker.of("haystack-pricing-test", CircuitBreakerConfig.custom()
				.failureRateThreshold(properties.getResilience().getCircuitBreakerFailureRateThreshold())
				.slidingWindowSize(properties.getResilience().getCircuitBreakerSlidingWindowSize())
				.minimumNumberOfCalls(properties.getResilience().getCircuitBreakerMinimumNumberOfCalls())
				.waitDurationInOpenState(Duration.ofSeconds(30))
				.recordException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		Bulkhead bulkhead = Bulkhead.of("pricing-test", BulkheadConfig.custom()
				.maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build());
		Retry retry = Retry.of("pricing-retry", RetryConfig.custom()
				.maxAttempts(1)
				.waitDuration(Duration.ofMillis(10))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build());
		return new HaystackPricingClient(properties, RestClient.builder(), new ObjectMapper(), cb, bulkhead, retry);
	}

	private static PricingQuoteRequest sampleRequest() {
		return new PricingQuoteRequest(
				"55",
				LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 5),
				20.0,
				List.of(new PricingQuoteRequestItem("101", 4L)));
	}

	@Test
	@DisplayName("Scenario: happy path sends X-Correlation-Id and maps per-item pricing fields")
	void quoteHappyPath_sendsCorrelationAndMapsPricing() {
		wireMock.stubFor(post(urlEqualTo(HaystackPricingClient.PATH_QUOTE))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "rental_plan_id": "55",
								  "currency": "SGD",
								  "deposit_rate": 0.30,
								  "degraded": false,
								  "results": [
								    {
								      "item_id": "101",
								      "asset_id": 4,
								      "daily_rate": 182.40,
								      "total_price": 912.00,
								      "was_clamped": true,
								      "min_daily_rate": 120.00,
								      "max_daily_rate": 260.00,
								      "model_version": "prod-2026-08-01",
								      "degraded": false
								    }
								  ],
								  "warnings": []
								}
								""")));

		PricingQuoteResponse resp = client.quote(sampleRequest(), "corr-price-1");

		assertEquals(1, resp.results().size());
		var item = resp.results().get(0);
		assertEquals("101", item.itemId());
		assertEquals(new BigDecimal("182.40"), item.dailyRate());
		assertEquals(new BigDecimal("912.00"), item.totalPrice());
		assertTrue(item.wasClamped());
		assertTrue(item.isUsable());
		assertNull(item.error());

		wireMock.verify(postRequestedFor(urlEqualTo(HaystackPricingClient.PATH_QUOTE))
				.withHeader(HaystackPricingClient.HEADER_CORRELATION_ID, equalTo("corr-price-1"))
				.withRequestBody(containing("\"rental_plan_id\":\"55\"")));
	}

	@Test
	@DisplayName("Scenario: per-item error does not fail the batch")
	void quote_perItemError_doesNotFailBatch() {
		wireMock.stubFor(post(urlEqualTo(HaystackPricingClient.PATH_QUOTE))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "rental_plan_id": "55",
								  "currency": "SGD",
								  "deposit_rate": 0.30,
								  "degraded": false,
								  "results": [
								    { "item_id": "101", "asset_id": 4, "error": "asset_not_found", "degraded": false, "was_clamped": false }
								  ],
								  "warnings": []
								}
								""")));

		PricingQuoteResponse resp = client.quote(sampleRequest(), "corr-price-2");

		assertNotNull(resp.results());
		assertEquals(1, resp.results().size());
		var item = resp.results().get(0);
		assertEquals("asset_not_found", item.error());
		assertFalse(item.isUsable());
	}

	@Test
	@DisplayName("Scenario: FastAPI 500 maps to retryable UPSTREAM kind")
	void quote5xx_mapsUpstream() {
		wireMock.stubFor(post(urlEqualTo(HaystackPricingClient.PATH_QUOTE))
				.willReturn(aResponse()
						.withStatus(500)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"internal_error\",\"message\":\"boom\"}")));

		HaystackException ex = assertThrows(HaystackException.class,
				() -> client.quote(sampleRequest(), "corr-price-5xx"));

		assertEquals(HaystackException.Kind.UPSTREAM, ex.getKind());
		assertTrue(ex.isRetryable());
	}

	@Test
	@DisplayName("Scenario: FastAPI 400 is mapped as a client error and not success-retried")
	void quote4xx_mapsClientError() {
		wireMock.stubFor(post(urlEqualTo(HaystackPricingClient.PATH_QUOTE))
				.willReturn(aResponse()
						.withStatus(400)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"bad_request\",\"message\":\"empty items\"}")));

		HaystackException ex = assertThrows(HaystackException.class,
				() -> client.quote(sampleRequest(), "corr-price-4xx"));

		assertEquals(HaystackException.Kind.CLIENT, ex.getKind());
		assertEquals("bad_request", ex.getErrorCode());
		assertFalse(ex.isRetryable());
		wireMock.verify(1, postRequestedFor(urlEqualTo(HaystackPricingClient.PATH_QUOTE)));
	}
}
