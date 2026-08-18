package com.heavy_rental.rest_api.client.onemap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;

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
 * BDD: repeated OneMap search failures open the {@code onemap} circuit breaker; subsequent calls
 * fail fast with {@code Kind.UNAVAILABLE} without hitting WireMock again — same shape as
 * {@code HaystackCircuitBreakerTest}. A token-fetch failure is also proven to trip the same
 * breaker, since {@link OneMapClient} wraps the whole "get token then search" operation.
 */
@DisplayName("OneMap circuit breaker")
class OneMapCircuitBreakerTest {

	private static final String TOKEN_PATH = "/api/auth/post/getToken";
	private static final String SEARCH_PATH = "/api/common/elastic/search";

	private WireMockServer wireMock;
	private OneMapClient client;
	private CircuitBreaker circuitBreaker;

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

		long expiry = Instant.now().plus(Duration.ofDays(3)).getEpochSecond();
		wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"access_token\":\"test-token\",\"expiry_timestamp\":\"" + expiry + "\"}")));
		wireMock.stubFor(get(urlPathEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"internal_error\"}")));

		circuitBreaker = CircuitBreaker.of("onemap-cb-test", CircuitBreakerConfig.custom()
				.failureRateThreshold(50f)
				.slidingWindowSize(4)
				.minimumNumberOfCalls(4)
				.waitDurationInOpenState(Duration.ofSeconds(60))
				.recordException(ex -> ex instanceof OneMapException ome && ome.isRetryable())
				.build());
		Bulkhead bulkhead = Bulkhead.of("onemap-cb-bh", BulkheadConfig.custom().maxConcurrentCalls(10).build());

		OneMapAuthService authService = new OneMapAuthService(properties, RestClient.builder());
		client = new OneMapClient(properties, RestClient.builder(), authService, circuitBreaker, bulkhead);
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	@Test
	@DisplayName("Scenario: repeated 5xx opens the circuit; subsequent calls fail fast without calling OneMap")
	void circuitOpens_thenFailsFastWithoutCallingOneMap() {
		for (int i = 0; i < 4; i++) {
			final String postalCode = "60000" + i;
			OneMapException ex = assertThrows(OneMapException.class, () -> client.geocode(postalCode));
			assertEquals(OneMapException.Kind.UPSTREAM, ex.getKind());
		}

		assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

		int countBefore = wireMock.findAll(getRequestedFor(urlPathEqualTo(SEARCH_PATH))).size();

		OneMapException openEx = assertThrows(OneMapException.class, () -> client.geocode("600999"));
		assertEquals(OneMapException.Kind.UNAVAILABLE, openEx.getKind());
		assertEquals("onemap_unavailable", openEx.getErrorCode());

		int countAfter = wireMock.findAll(getRequestedFor(urlPathEqualTo(SEARCH_PATH))).size();
		assertEquals(countBefore, countAfter, "open circuit must not call OneMap");
	}
}
