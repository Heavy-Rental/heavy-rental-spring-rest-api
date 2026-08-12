package com.heavy_rental.rest_api.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.heavy_rental.rest_api.client.haystack.HaystackProperties;
import com.heavy_rental.rest_api.client.haystack.HaystackRecommenderClient;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecRequest;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecResponse;
import com.heavy_rental.rest_api.entity.AIRecommendation;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.AIRecommendationRepository;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * BDD: plan §7 #4/#6/#8 — dual-hop WireMock (Call 1 + Call 2 paths), shared correlation,
 * quote body, and no re-ingest on Call 2 failure.
 * <p>
 * Real {@link HaystackRecommenderClient} + real {@link RecommenderSagaService}; mock JWT user + repo.
 * TDD: change dual-hop behaviour only with a failing scenario here first.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommenderSaga dual-hop WireMock")
class RecommenderSagaWireMockTest {

	private WireMockServer wireMock;
	private HaystackRecommenderClient client;
	private RecommenderSagaService saga;

	@Mock
	private AIRecommendationRepository recommendationRepository;
	@Mock
	private CurrentUserService currentUserService;
	@Mock
	private Jwt jwt;

	private User user;

	@BeforeEach
	void setUp() {
		wireMock = new WireMockServer(wireMockConfig().dynamicPort());
		wireMock.start();

		HaystackProperties properties = new HaystackProperties();
		properties.setBaseUrl(wireMock.baseUrl());
		properties.getTimeouts().setConnect(Duration.ofSeconds(2));
		properties.getTimeouts().setIngestRead(Duration.ofSeconds(5));
		properties.getTimeouts().setRecommendRead(Duration.ofSeconds(5));
		properties.getTimeouts().setQaRead(Duration.ofSeconds(5));
		properties.getTimeouts().setHealthRead(Duration.ofSeconds(2));
		properties.getRetry().setIngestEnabled(false);
		properties.getRetry().setRecommendMaxAttempts(1);
		properties.getRetry().setQaMaxAttempts(1);
		properties.getResilience().setCircuitBreakerMinimumNumberOfCalls(100);
		properties.getResilience().setCircuitBreakerSlidingWindowSize(100);

		CircuitBreaker cb = CircuitBreaker.of("saga-wm-cb", CircuitBreakerConfig.custom()
				.minimumNumberOfCalls(100)
				.slidingWindowSize(100)
				.recordException(ex -> false)
				.build());
		Bulkhead bh = Bulkhead.of("saga-wm-bh", BulkheadConfig.custom().maxConcurrentCalls(10).build());
		Retry noRetry = Retry.of("saga-wm-retry", RetryConfig.custom().maxAttempts(1).build());

		client = new HaystackRecommenderClient(
				properties, RestClient.builder(), new ObjectMapper(),
				cb, bh, bh, bh, noRetry, noRetry, noRetry);
		saga = new RecommenderSagaService(client, recommendationRepository, currentUserService);

		user = new User();
		user.setId(7L);
		user.setName("Alex");
		user.setEmail("alex@example.com");
	}

	@AfterEach
	void tearDown() {
		wireMock.stop();
	}

	@DisplayName("Scenario: Dual-hop happy path — one ingest + one getassetrecommendations with shared correlation and quote body")
	@Test
	void dualHop_happyPath_quoteBody_sharedCorrelation_correctPaths() {
		when(currentUserService.getUser(jwt)).thenReturn(user);
		when(recommendationRepository.save(any())).thenAnswer(inv -> {
			AIRecommendation r = inv.getArgument(0);
			if (r.getId() == null) {
				r.setId(42L);
			}
			return r;
		});

		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "ingest_id": "ing_wire_1",
								  "user_id": "7",
								  "user_requirement_summary": "Need excavator for foundation",
								  "needs_summary": [],
								  "warnings": ["soft soil"]
								}
								""")));

		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "user_id": "7",
								  "ingest_id": "ing_wire_1",
								  "query": "Need excavator for foundation",
								  "quoteRef": "QUO-WIRE-1",
								  "confidenceScore": 0.88,
								  "days": 10,
								  "estimatedTotal": 4500.00,
								  "specSummary": "Foundation dig",
								  "rationale": "CAT 320 class excavator",
								  "items": [
								    {
								      "rankOrder": 1,
								      "equipment": { "id": "asset-1", "name": "CAT 320", "category": "Excavator" },
								      "baseDailyRate": 450.00,
								      "lineTotal": 4500.00
								    }
								  ],
								  "warnings": []
								}
								""")));

		SubmitProjectSpecResponse resp = saga.submitProjectSpec(
				jwt,
				new SubmitProjectSpecRequest(
						"Need excavator for foundation works",
						null, null, null, null, 5),
				"corr-dual-hop");

		assertEquals(42L, resp.recommendationId());
		assertEquals("ing_wire_1", resp.ingestId());
		assertEquals("corr-dual-hop", resp.correlationId());
		assertEquals("QUO-WIRE-1", resp.quoteRef());
		assertNotNull(resp.items());
		assertEquals(1, resp.items().size());
		assertEquals("asset-1", resp.items().get(0).equipmentId());

		// Exactly one Call 1 and one Call 2 on correct paths
		wireMock.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-dual-hop"))
				.withHeader(HaystackRecommenderClient.HEADER_IDEMPOTENCY_KEY, matchingNonBlank()));
		wireMock.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-dual-hop"))
				.withRequestBody(matchingJsonPath("$.ingest_id", equalTo("ing_wire_1")))
				.withRequestBody(matchingJsonPath("$.user_id", equalTo("7"))));
		// Call 3 not used on submit
		wireMock.verify(0, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_QUERY)));
	}

	@DisplayName("Scenario: Dual-hop Call 2 500 — exactly one ingest and persisted ingest_id")
	@Test
	void dualHop_call2_500_doesNotReIngest_persistsIngestId() {
		when(currentUserService.getUser(jwt)).thenReturn(user);
		ArgumentCaptor<AIRecommendation> saved = ArgumentCaptor.forClass(AIRecommendation.class);
		when(recommendationRepository.save(any())).thenAnswer(inv -> {
			AIRecommendation r = inv.getArgument(0);
			r.setId(9L);
			return r;
		});

		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "ingest_id": "ing_keep",
								  "user_id": "7",
								  "user_requirement_summary": "summary",
								  "needs_summary": [],
								  "warnings": []
								}
								""")));

		wireMock.stubFor(post(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.willReturn(aResponse()
						.withStatus(500)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":\"internal_error\",\"message\":\"recommend down\"}")));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> saga.submitProjectSpec(
						jwt,
						new SubmitProjectSpecRequest("project text", null, null, null, null, null),
						"corr-fail"));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
		wireMock.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST)));
		wireMock.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-fail")));
		// Session was persisted with ingest_id before Call 2 failed
		org.mockito.Mockito.verify(recommendationRepository).save(saved.capture());
		assertEquals("ing_keep", saved.getValue().getIngestId());
	}

	/** WireMock matcher for non-blank Idempotency-Key header value. */
	private static com.github.tomakehurst.wiremock.matching.StringValuePattern matchingNonBlank() {
		return com.github.tomakehurst.wiremock.client.WireMock.matching(".+");
	}
}
