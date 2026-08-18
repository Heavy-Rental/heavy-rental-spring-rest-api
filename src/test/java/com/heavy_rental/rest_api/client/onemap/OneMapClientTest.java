package com.heavy_rental.rest_api.client.onemap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * BDD/TDD: WireMock specs for {@link OneMapClient} — postal code geocoding (see
 * {@code openspec/changes/pricing-postal-distance/}). Circuit-breaker-open behavior is covered
 * separately by {@link OneMapCircuitBreakerTest}; token caching/refresh by
 * {@link OneMapAuthServiceTest}.
 */
@DisplayName("OneMapClient — wire contract & caching")
class OneMapClientTest {

	private static final String TOKEN_PATH = "/api/auth/post/getToken";
	private static final String SEARCH_PATH = "/api/common/elastic/search";

	private WireMockServer wireMock;
	private OneMapClient client;

	@BeforeEach
	void setUp() {
		wireMock = new WireMockServer(wireMockConfig().dynamicPort());
		wireMock.start();

		OneMapProperties properties = new OneMapProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.setEmail("test@example.com");
		properties.setPassword("secret");
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		properties.getTimeouts().setRead(Duration.ofSeconds(2));
		properties.getResilience().setCircuitBreakerMinimumNumberOfCalls(50);
		properties.getResilience().setCircuitBreakerSlidingWindowSize(50);

		stubValidToken();

		OneMapAuthService authService = new OneMapAuthService(properties, RestClient.builder());
		CircuitBreaker cb = CircuitBreaker.of("onemap-test", CircuitBreakerConfig.custom()
				.failureRateThreshold(properties.getResilience().getCircuitBreakerFailureRateThreshold())
				.slidingWindowSize(properties.getResilience().getCircuitBreakerSlidingWindowSize())
				.minimumNumberOfCalls(properties.getResilience().getCircuitBreakerMinimumNumberOfCalls())
				.waitDurationInOpenState(Duration.ofSeconds(30))
				.recordException(ex -> ex instanceof OneMapException ome && ome.isRetryable())
				.build());
		Bulkhead bulkhead = Bulkhead.of("onemap-test", BulkheadConfig.custom()
				.maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build());

		client = new OneMapClient(properties, RestClient.builder(), authService, cb, bulkhead);
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	private void stubValidToken() {
		long expiry = Instant.now().plus(Duration.ofDays(3)).getEpochSecond();
		wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"access_token\":\"test-token\",\"expiry_timestamp\":\"" + expiry + "\"}")));
	}

	private static String foundBody() {
		return """
				{
				  "found": 1,
				  "totalNumPages": 1,
				  "pageNum": 1,
				  "results": [
				    {
				      "SEARCHVAL": "20 JURONG PORT ROAD SINGAPORE 619094",
				      "BLK_NO": "20",
				      "ROAD_NAME": "JURONG PORT ROAD",
				      "BUILDING": "NIL",
				      "ADDRESS": "20 JURONG PORT ROAD SINGAPORE 619094",
				      "POSTAL": "619094",
				      "X": "15297.02",
				      "Y": "33434.88",
				      "LATITUDE": "1.3186451330849",
				      "LONGITUDE": "103.719175822788"
				    }
				  ]
				}
				""";
	}

	@Test
	@DisplayName("Scenario: happy path resolves coordinates and sends a bearer token")
	void geocode_happyPath_resolvesCoordinatesAndSendsBearerToken() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(foundBody())));

		Optional<Coordinates> result = client.geocode("619094");

		assertTrue(result.isPresent());
		assertEquals(1.3186451330849, result.get().latitude(), 1e-9);
		assertEquals(103.719175822788, result.get().longitude(), 1e-9);
		assertEquals("20 JURONG PORT ROAD SINGAPORE 619094", result.get().address());

		wireMock.verify(getRequestedFor(urlPathEqualTo(SEARCH_PATH))
				.withHeader("Authorization", equalTo("Bearer test-token")));
	}

	@Test
	@DisplayName("Scenario: no match returns empty, not an exception")
	void geocode_noMatch_returnsEmpty() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"found\":0,\"totalNumPages\":0,\"pageNum\":1,\"results\":[]}")));

		Optional<Coordinates> result = client.geocode("000000");

		assertTrue(result.isEmpty());
	}

	@Test
	@DisplayName("Scenario: repeat lookups of the same postal code are served from cache")
	void geocode_repeatCall_hitsCache() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(foundBody())));

		client.geocode("619094");
		client.geocode("619094");
		client.geocode("619094");

		wireMock.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
	}

	@Test
	@DisplayName("Scenario: two different postal codes reuse the same cached token")
	void geocode_differentPostalCodes_reuseCachedToken() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(foundBody())));

		client.geocode("619094");
		client.geocode("629462");

		wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
	}

	@Test
	@DisplayName("Scenario: OneMap 500 maps to a retryable UPSTREAM kind, not cached")
	void geocode_5xx_mapsUpstreamAndIsNotCached() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"internal_error\"}")));

		OneMapException ex = assertThrows(OneMapException.class, () -> client.geocode("619094"));
		assertEquals(OneMapException.Kind.UPSTREAM, ex.getKind());
		assertTrue(ex.isRetryable());

		// A failure must not be cached — a fresh call re-hits WireMock, proving no stale entry stuck.
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(foundBody())));
		Optional<Coordinates> retried = client.geocode("619094");
		assertTrue(retried.isPresent());
	}

	@Test
	@DisplayName("Scenario: OneMap 400 maps to a non-retryable CLIENT kind")
	void geocode_4xx_mapsClientError() {
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"bad_request\"}")));

		OneMapException ex = assertThrows(OneMapException.class, () -> client.geocode("619094"));
		assertEquals(OneMapException.Kind.CLIENT, ex.getKind());
		assertFalse(ex.isRetryable());
	}
}
