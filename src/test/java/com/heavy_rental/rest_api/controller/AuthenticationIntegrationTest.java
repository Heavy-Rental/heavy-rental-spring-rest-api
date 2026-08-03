package com.heavy_rental.rest_api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.heavy_rental.rest_api.security.JwtService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	private String username;
	private String password;

	@BeforeEach
	void createUser() {
		username = "user_" + UUID.randomUUID().toString().substring(0, 8);
		password = "password123";
		userRepository.save(User.builder()
				.username(username)
				.password(passwordEncoder.encode(password))
				.email(username + "@example.com")
				.role("ROLE_USER")
				.enabled(true)
				.build());
	}

	@Test
	void getBearerTokenReturnsInterimJwt() throws Exception {
		Instant before = Instant.now().minusSeconds(5);

		MvcResult tokenResult = mockMvc.perform(get("/api/auth/getBearerToken"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
				.andReturn();

		Instant after = Instant.now().plusSeconds(5);
		String token = tokenResult.getResponse().getContentAsString();
		Assertions.assertFalse(token.isBlank());
		Assertions.assertFalse(token.startsWith("Bearer "));

		Jwt jwt = jwtDecoder.decode(token);
		Assertions.assertDoesNotThrow(() -> UUID.fromString(jwt.getSubject()));
		Assertions.assertEquals(JwtService.TOKEN_TYPE_INTERIM, JwtService.tokenTypeFrom(jwt));
		Assertions.assertTrue(JwtService.rolesFrom(jwt).contains("ROLE_INTERIM"));
		Assertions.assertFalse(JwtService.rolesFrom(jwt).contains("ROLE_USER"));

		Instant generatedAt = Instant.parse(jwt.getClaimAsString(JwtService.CLAIM_GENERATED_AT));
		Assertions.assertFalse(generatedAt.isBefore(before));
		Assertions.assertFalse(generatedAt.isAfter(after));
	}

	@Test
	void interimTokenCannotCallLogout() throws Exception {
		String interim = mintInterim();

		mockMvc.perform(post("/api/auth/logout")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void interimTokenCannotCallProtectedBusinessPath() throws Exception {
		String interim = mintInterim();

		// No dedicated business controller yet; any non-login auth path requiring USER fails for interim
		mockMvc.perform(get("/api/auth/user")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void loginWithoutBearerReturns401() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"%s"}
								""".formatted(username, password)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("unauthorized"));
	}

	@Test
	void loginWithInterimAndBadPasswordReturns401() throws Exception {
		String interim = mintInterim();

		mockMvc.perform(post("/api/auth/login")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"wrong-password"}
								""".formatted(username)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_credentials"));
	}

	@Test
	void loginWithInterimAndValidCredentialsReturnsAccessToken() throws Exception {
		String interim = mintInterim();

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"%s"}
								""".formatted(username, password)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.expiresIn").isNumber())
				.andReturn();

		String accessToken = readAccessToken(loginResult);
		Jwt accessJwt = jwtDecoder.decode(accessToken);
		Assertions.assertEquals(username, accessJwt.getSubject());
		Assertions.assertEquals(JwtService.TOKEN_TYPE_ACCESS, JwtService.tokenTypeFrom(accessJwt));
		Assertions.assertTrue(JwtService.rolesFrom(accessJwt).contains("ROLE_USER"));
	}

	@Test
	void loginWithAccessTokenReturns403() throws Exception {
		String accessToken = loginAndGetAccessToken();

		mockMvc.perform(post("/api/auth/login")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"%s"}
								""".formatted(username, password)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void interimTokenCannotBeReusedAfterLogin() throws Exception {
		String interim = mintInterim();

		mockMvc.perform(post("/api/auth/login")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"%s"}
								""".formatted(username, password)))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/login")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"%s"}
								""".formatted(username, password)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutRevokesAccessToken() throws Exception {
		String accessToken = loginAndGetAccessToken();

		mockMvc.perform(post("/api/auth/logout")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Logged out successfully"));

		mockMvc.perform(post("/api/auth/logout")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void protectedEndpointWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/auth/user"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("unauthorized"));
	}

	private String mintInterim() throws Exception {
		return mockMvc.perform(get("/api/auth/getBearerToken"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
	}

	private String loginAndGetAccessToken() throws Exception {
		String interim = mintInterim();
		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"username":"%s","password":"%s"}
								""".formatted(username, password)))
				.andExpect(status().isOk())
				.andReturn();
		return readAccessToken(loginResult);
	}

	private String readAccessToken(MvcResult result) throws Exception {
		JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
		return node.get("accessToken").asString();
	}
}
