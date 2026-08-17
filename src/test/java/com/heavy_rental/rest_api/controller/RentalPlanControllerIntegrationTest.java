package com.heavy_rental.rest_api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.entity.User.UserRole;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.heavy_rental.rest_api.security.JwtService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BDD/TDD: portal HTTP for {@code POST /api/rentalPlans} and {@code PATCH /api/rentalPlans/{id}}
 * (see {@code openspec/changes/pricing-postal-distance/} "Follow-on: optional siteAddress at plan
 * creation" / "PATCH /api/rentalPlans/{id}"). {@link com.heavy_rental.rest_api.service.RentalPlanServiceTest}
 * already proves the service-layer behavior with directly-constructed DTOs, which bypasses
 * {@code @Valid} entirely — this is the one place that actually proves the Bean Validation changes
 * have the intended effect through Spring's real validation pipeline, not just documented semantics.
 * <p>
 * No WireMock needed — neither {@code create()} nor {@code updateSiteAddress()} touches haystack
 * or OneMap.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RentalPlanController portal integration")
class RentalPlanControllerIntegrationTest {

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

    private String accessToken;

    @BeforeEach
    void setUpUser() {
        String email = "rentalplan_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        userRepository.save(User.builder()
                .name("Rental Plan User " + UUID.randomUUID().toString().substring(0, 8))
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

    @DisplayName("Scenario: siteAddress omitted succeeds — the \"Skip for now\" cart flow")
    @Test
    void create_siteAddressOmitted_returns201WithNullSiteAddress() throws Exception {
        mockMvc.perform(post("/api/rentalPlans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startDate": "2026-10-01",
                                  "endDate": "2026-10-05"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.siteAddress").doesNotExist());
    }

    @DisplayName("Scenario: siteAddress present but malformed is still rejected — validation strictness unchanged")
    @Test
    void create_siteAddressMalformed_returns400() throws Exception {
        mockMvc.perform(post("/api/rentalPlans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startDate": "2026-10-01",
                                  "endDate": "2026-10-05",
                                  "siteAddress": "not a real address"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @DisplayName("Scenario: valid siteAddress still succeeds — unchanged happy path")
    @Test
    void create_siteAddressValid_returns201WithSiteAddress() throws Exception {
        mockMvc.perform(post("/api/rentalPlans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startDate": "2026-10-01",
                                  "endDate": "2026-10-05",
                                  "siteAddress": "20 Jurong Port Road, 619094"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.siteAddress").value("20 Jurong Port Road, 619094"));
    }

    private long createPlanViaSkipForNow() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rentalPlans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startDate": "2026-10-01",
                                  "endDate": "2026-10-05"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    @DisplayName("Scenario: PATCH sets siteAddress on a plan created via \"Skip for now\"")
    @Test
    void updateSiteAddress_validAddress_returns200WithAddressSet() throws Exception {
        long id = createPlanViaSkipForNow();

        mockMvc.perform(patch("/api/rentalPlans/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "siteAddress": "20 Jurong Port Road, 619094" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteAddress").value("20 Jurong Port Road, 619094"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @DisplayName("Scenario: PATCH with a malformed siteAddress is rejected")
    @Test
    void updateSiteAddress_malformedAddress_returns400() throws Exception {
        long id = createPlanViaSkipForNow();

        mockMvc.perform(patch("/api/rentalPlans/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "siteAddress": "not a real address" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @DisplayName("Scenario: PATCH on another customer's plan returns 404")
    @Test
    void updateSiteAddress_notOwner_returns404() throws Exception {
        long id = createPlanViaSkipForNow();

        String otherEmail = "other_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        userRepository.save(User.builder()
                .name("Other User")
                .password(passwordEncoder.encode("password123"))
                .email(otherEmail)
                .role(UserRole.USER)
                .enabled(true)
                .build());
        String otherAccessToken = jwtService.generateToken(
                otherEmail, List.of("ROLE_USER"), Instant.now(), JwtService.TOKEN_TYPE_ACCESS).getTokenValue();

        mockMvc.perform(patch("/api/rentalPlans/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "siteAddress": "20 Jurong Port Road, 619094" }
                                """))
                .andExpect(status().isNotFound());
    }
}
