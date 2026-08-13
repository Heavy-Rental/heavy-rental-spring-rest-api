package com.heavy_rental.rest_api.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.heavy_rental.rest_api.client.haystack.HaystackRecommenderClient;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.entity.User.UserRole;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.heavy_rental.rest_api.security.JwtService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan §7.1 — full portal recommender flow with JWT + WireMock haystack.
 */
/**
 * BDD/TDD: portal HTTP with JWT + WireMock haystack — FR-S2B-007/009 (plan §7.1 MockMvc).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RecommendationController portal integration")
class RecommendationControllerIntegrationTest {

	private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		WIRE_MOCK.start();
	}

	@DynamicPropertySource
	static void haystackProps(DynamicPropertyRegistry registry) {
		registry.add("haystack.base-url", WIRE_MOCK::baseUrl);
		registry.add("haystack.timeouts.connect", () -> "2s");
		registry.add("haystack.timeouts.health-read", () -> "2s");
		registry.add("haystack.timeouts.ingest-read", () -> "5s");
		registry.add("haystack.timeouts.recommend-read", () -> "5s");
		registry.add("haystack.timeouts.qa-read", () -> "5s");
		registry.add("haystack.retry.ingest-enabled", () -> "false");
		registry.add("haystack.retry.recommend-max-attempts", () -> "1");
		registry.add("haystack.retry.qa-max-attempts", () -> "1");
		registry.add("haystack.resilience.circuit-breaker-minimum-number-of-calls", () -> "100");
		registry.add("haystack.resilience.circuit-breaker-sliding-window-size", () -> "100");
	}

	@AfterAll
	static void stopWireMock() {
		WIRE_MOCK.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private ObjectMapper objectMapper;

	private String email;
	private String accessToken;

	@BeforeEach
	void setUpUserAndStubs() {
		WIRE_MOCK.resetAll();
		email = "rec_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		userRepository.save(User.builder()
				.name("Rec User " + UUID.randomUUID().toString().substring(0, 8))
				.password(passwordEncoder.encode("password123"))
				.email(email)
				.role(UserRole.USER)
				.enabled(true)
				.build());
		accessToken = jwtService.generateToken(
				email,
				List.of("ROLE_USER"),
				Instant.now(),
				JwtService.TOKEN_TYPE_ACCESS).getTokenValue();

		stubIngestAndRecommend();
		stubKnowledgeQuery();
	}

	private void stubIngestAndRecommend() {
		WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
						urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "ingest_id": "ing_it_1",
								  "user_id": "u1",
								  "user_requirement_summary": "Need excavator",
								  "needs_summary": [],
								  "warnings": []
								}
								""")));
		WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
						urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "user_id": "u1",
								  "ingest_id": "ing_it_1",
								  "quoteRef": "QUO-IT-1",
								  "confidenceScore": 0.9,
								  "days": 5,
								  "estimatedTotal": 2000.00,
								  "specSummary": "Excavation",
								  "rationale": "Matches capacity",
								  "items": [
								    {
								      "rankOrder": 1,
								      "matchScore": 0.9,
								      "reason": "Matches capacity",
								      "quantity": 1,
								      "lineTotal": 2000.00,
								      "equipment": {
								        "id": "1",
								        "name": "CAT 320",
								        "category": "Excavator",
								        "baseDailyRate": 400.00
								      }
								    }
								  ],
								  "warnings": []
								}
								""")));
	}

	private void stubKnowledgeQuery() {
		WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
						urlEqualTo(HaystackRecommenderClient.PATH_QUERY))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "answer": "Working height depends on platform choice.",
								  "sources_used": ["kg"]
								}
								""")));
	}

	@DisplayName("Scenario: Unauthenticated project-spec returns 401")
	@Test
	void unauthenticated_projectSpec_returns401() throws Exception {
		mockMvc.perform(post("/api/recommendations/project-spec")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"projectText\":\"x\"}"))
				.andExpect(status().isUnauthorized());
	}

	@DisplayName("Scenario: JSON submit returns nested quote items (FR-S2B-010), persists session, knowledge-query is Call 3")
	@Test
	void jsonSubmit_returnsQuote_andPersistsSession() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/recommendations/project-spec")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
						.header(HaystackRecommenderClient.HEADER_CORRELATION_ID, "corr-it-json")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "projectText": "Need excavator for foundation dig",
								  "startDate": "2026-10-01",
								  "endDate": "2026-10-06",
								  "topK": 3
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationId").isNumber())
				.andExpect(jsonPath("$.ingestId").value("ing_it_1"))
				.andExpect(jsonPath("$.quoteRef").value("QUO-IT-1"))
				.andExpect(jsonPath("$.items[0].rankOrder").value(1))
				.andExpect(jsonPath("$.items[0].matchScore").value(0.9))
				.andExpect(jsonPath("$.items[0].reason").value("Matches capacity"))
				.andExpect(jsonPath("$.items[0].quantity").value(1))
				.andExpect(jsonPath("$.items[0].lineTotal").value(2000.00))
				.andExpect(jsonPath("$.items[0].equipment.id").value(1))
				.andExpect(jsonPath("$.items[0].equipment.name").value("CAT 320"))
				.andExpect(jsonPath("$.items[0].equipment.category").value("Excavator"))
				.andExpect(jsonPath("$.items[0].equipment.baseDailyRate").value(400.00))
				// FR-S2B-010: must not flatten equipment into legacy top-level fields
				.andExpect(jsonPath("$.items[0].equipmentId").doesNotExist())
				.andExpect(jsonPath("$.items[0].equipmentName").doesNotExist())
				.andExpect(jsonPath("$.answer").doesNotExist())
				.andReturn();

		WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-it-json")));
		WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-it-json")));
		WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_QUERY)));

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		long id = body.get("recommendationId").asLong();

		mockMvc.perform(get("/api/recommendations/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingestId").value("ing_it_1"));

		mockMvc.perform(post("/api/recommendations/" + id + "/knowledge-query")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"query\":\"What height?\",\"topK\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value("Working height depends on platform choice."));

		WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_QUERY)));
	}

	@DisplayName("Scenario: Multipart project-spec submit returns Call 2 quote")
	@Test
	void multipartSubmit_returnsQuote() throws Exception {
		MockMultipartFile file = new MockMultipartFile(
				"file",
				"spec.txt",
				"text/plain",
				"Need scissors lift indoor".getBytes());

		mockMvc.perform(multipart("/api/recommendations/project-spec")
						.file(file)
						.param("projectText", "Optional caption")
						.param("topK", "2")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
						.header(HaystackRecommenderClient.HEADER_CORRELATION_ID, "corr-it-mp")
						.contentType(MediaType.MULTIPART_FORM_DATA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quoteRef").value("QUO-IT-1"))
				.andExpect(jsonPath("$.ingestId").value("ing_it_1"));

		WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_INGEST))
				.withHeader(HaystackRecommenderClient.HEADER_CORRELATION_ID, equalTo("corr-it-mp")));
		WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(HaystackRecommenderClient.PATH_RECOMMEND)));
	}
}
