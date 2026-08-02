package com.heavy_rental.rest_api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.heavy_rental.rest_api.security.JwtService;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Test
	void getBearerTokenReturnsPlainTokenWithUuidAndGeneratedAt() throws Exception {
		Instant before = Instant.now().minusSeconds(5);

		MvcResult tokenResult = mockMvc.perform(get("/api/auth/getBearerToken"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
				.andReturn();

		Instant after = Instant.now().plusSeconds(5);

		String token = tokenResult.getResponse().getContentAsString();
		Assertions.assertFalse(token.isBlank());
		Assertions.assertFalse(token.startsWith("Bearer "));
		Assertions.assertFalse(token.startsWith("{"));

		Jwt jwt = jwtDecoder.decode(token);
		Assertions.assertDoesNotThrow(() -> UUID.fromString(jwt.getSubject()));
		Assertions.assertTrue(JwtService.rolesFrom(jwt).contains("ROLE_USER"));
		Assertions.assertNotNull(jwt.getId());
		Assertions.assertNotNull(jwt.getExpiresAt());

		String generatedAtClaim = jwt.getClaimAsString("generatedAt");
		Assertions.assertNotNull(generatedAtClaim);
		Instant generatedAt = Instant.parse(generatedAtClaim);
		Assertions.assertFalse(generatedAt.isBefore(before));
		Assertions.assertFalse(generatedAt.isAfter(after));
	}

	@Test
	void getBearerTokenIssuesUniqueSubjects() throws Exception {
		String token1 = mockMvc.perform(get("/api/auth/getBearerToken"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String token2 = mockMvc.perform(get("/api/auth/getBearerToken"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		Jwt jwt1 = jwtDecoder.decode(token1);
		Jwt jwt2 = jwtDecoder.decode(token2);
		Assertions.assertNotEquals(jwt1.getSubject(), jwt2.getSubject());
	}

	@Test
	void protectedEndpointWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/auth/user"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("unauthorized"));
	}
}
