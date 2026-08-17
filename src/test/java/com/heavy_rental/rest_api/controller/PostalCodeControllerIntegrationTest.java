package com.heavy_rental.rest_api.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.entity.User.UserRole;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.heavy_rental.rest_api.security.JwtService;

/**
 * BDD/TDD: portal HTTP for {@code GET /api/postalCodes/{postalCode}} with JWT + WireMock OneMap
 * (see {@code openspec/changes/pricing-postal-distance/}). Mirrors
 * {@link RecommendationControllerIntegrationTest}'s shape.
 * <p>
 * Each scenario uses a distinct postal code so {@code OneMapClient}'s in-memory cache (a Spring
 * singleton, shared across all test methods in this class) never serves a stale result from an
 * earlier test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PostalCodeController portal integration")
class PostalCodeControllerIntegrationTest {

    private static final String TOKEN_PATH = "/api/auth/post/getToken";
    private static final String SEARCH_PATH = "/api/common/elastic/search";

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void onemapProps(DynamicPropertyRegistry registry) {
        registry.add("onemap.base-url", WIRE_MOCK::baseUrl);
        registry.add("onemap.timeouts.connect", () -> "2s");
        registry.add("onemap.timeouts.read", () -> "2s");
        // High enough that one test's simulated failure never trips the CB for the next test —
        // same defensive convention as RecommendationControllerIntegrationTest's haystack overrides.
        registry.add("onemap.resilience.circuit-breaker-minimum-number-of-calls", () -> "100");
        registry.add("onemap.resilience.circuit-breaker-sliding-window-size", () -> "100");
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

    private String accessToken;

    @BeforeEach
    void setUpUserAndStubs() {
        WIRE_MOCK.resetAll();
        String email = "postal_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        userRepository.save(User.builder()
                .name("Postal User " + UUID.randomUUID().toString().substring(0, 8))
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
    }

    private void stubValidToken() {
        long expiry = Instant.now().plusSeconds(3 * 24 * 3600).getEpochSecond();
        WIRE_MOCK.stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"it-token\",\"expiry_timestamp\":\"" + expiry + "\"}")));
    }

    @DisplayName("Scenario: unauthenticated request returns 401")
    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/postalCodes/619094"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("Scenario: real postal code resolves to VALID with a resolved address")
    @Test
    void validPostalCode_returns200Valid() throws Exception {
        stubValidToken();
        WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "found": 1,
                                  "results": [
                                    {
                                      "ADDRESS": "20 JURONG PORT ROAD SINGAPORE 619094",
                                      "POSTAL": "619094",
                                      "LATITUDE": "1.3186451330849",
                                      "LONGITUDE": "103.719175822788"
                                    }
                                  ]
                                }
                                """)));

        mockMvc.perform(get("/api/postalCodes/619094")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.postalCode").value("619094"))
                .andExpect(jsonPath("$.address").value("20 JURONG PORT ROAD SINGAPORE 619094"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @DisplayName("Scenario: well-formed but non-existent postal code resolves to INVALID")
    @Test
    void noMatchPostalCode_returns200Invalid() throws Exception {
        stubValidToken();
        WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"found\":0,\"results\":[]}")));

        mockMvc.perform(get("/api/postalCodes/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.postalCode").value("999999"))
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @DisplayName("Scenario: malformed postal code (not 6 digits) is rejected before ever calling OneMap")
    @Test
    void malformedPostalCode_returns400() throws Exception {
        mockMvc.perform(get("/api/postalCodes/12345")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
    }

    @DisplayName("Scenario: OneMap unreachable maps to 503 UNAVAILABLE, not a hard error")
    @Test
    void oneMapDown_returns503Unavailable() throws Exception {
        stubValidToken();
        WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"internal_error\"}")));

        mockMvc.perform(get("/api/postalCodes/888888")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.postalCode").value("888888"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
