package com.heavy_rental.rest_api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.entity.User.UserRole;
import com.heavy_rental.rest_api.repository.AssetCategoryRepository;
import com.heavy_rental.rest_api.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AssetAdminIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AssetCategoryRepository assetCategoryRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	private String adminToken;
	private String userToken;
	private Long categoryId;

	@BeforeEach
	void setUp() throws Exception {
		categoryId = assetCategoryRepository.findAll().stream()
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Seed data must contain at least one asset category"))
				.getId();

		adminToken = registerAndLogin(UserRole.ADMIN);
		userToken = registerAndLogin(UserRole.USER);
	}

	@Test
	void adminCanCreateAsset() throws Exception {
		mockMvc.perform(post("/api/assets")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(assetJson(uniqueName(), "SN-1", categoryId, "GOOD")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.serialno").value("SN-1"))
				.andExpect(jsonPath("$.lastConditionUpdatedAt").isNotEmpty());
	}

	@Test
	void nonAdminCannotCreateAsset() throws Exception {
		mockMvc.perform(post("/api/assets")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(assetJson(uniqueName(), "SN-1", categoryId, "GOOD")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void adminCanReplaceAsset() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");

		mockMvc.perform(put("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(assetJson(uniqueName(), "SN-2", categoryId, "FAIR")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.serialno").value("SN-2"));
	}

	@Test
	void nonAdminCannotReplaceAsset() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");

		mockMvc.perform(put("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(assetJson(uniqueName(), "SN-2", categoryId, "FAIR")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void adminCanPatchAsset() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");

		mockMvc.perform(patch("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("location", "Jurong Yard"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.location").value("Jurong Yard"));
	}

	@Test
	void nonAdminCannotPatchAsset() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");

		mockMvc.perform(patch("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("location", "Jurong Yard"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void adminCanDeleteAsset() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");

		mockMvc.perform(delete("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void nonAdminCannotDeleteAsset() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");

		mockMvc.perform(delete("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void adminCanUploadImage() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");
		String base64 = Base64.getEncoder().encodeToString("fake-image-bytes".getBytes());

		mockMvc.perform(put("/api/assets/" + id + "/image")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("image", base64))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.img").value("data:image/jpeg;base64," + base64));
	}

	@Test
	void nonAdminCannotUploadImage() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");
		String base64 = Base64.getEncoder().encodeToString("fake-image-bytes".getBytes());

		mockMvc.perform(put("/api/assets/" + id + "/image")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("image", base64))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void getAssetsRemainsAccessibleToNonAdmin() throws Exception {
		mockMvc.perform(get("/api/assets")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
				.andExpect(status().isOk());
	}

	@Test
	void conditionChangeStampsLastConditionUpdatedAt() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");
		String firstStamp = getLastConditionUpdatedAt(id);

		Thread.sleep(5);
		mockMvc.perform(patch("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("condition", "EXCELLENT"))))
				.andExpect(status().isOk());
		String secondStamp = getLastConditionUpdatedAt(id);

		Assertions.assertNotNull(firstStamp);
		Assertions.assertNotNull(secondStamp);
		Assertions.assertNotEquals(firstStamp, secondStamp);
	}

	@Test
	void noOpConditionPatchDoesNotChangeStamp() throws Exception {
		long id = createAsset(uniqueName(), "GOOD");
		String firstStamp = getLastConditionUpdatedAt(id);

		Thread.sleep(5);
		mockMvc.perform(patch("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("condition", "GOOD"))))
				.andExpect(status().isOk());
		String secondStamp = getLastConditionUpdatedAt(id);

		Assertions.assertEquals(firstStamp, secondStamp);
	}

	@Test
	void duplicateNameOnCreateReturns409() throws Exception {
		String name = uniqueName();
		createAsset(name, "GOOD");

		mockMvc.perform(post("/api/assets")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(assetJson(name, "SN-DUP", categoryId, "GOOD")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("conflict"));
	}

	@Test
	void duplicateNameOnReplaceReturns409() throws Exception {
		String nameA = uniqueName();
		String nameB = uniqueName();
		createAsset(nameA, "GOOD");
		long idB = createAsset(nameB, "GOOD");

		mockMvc.perform(put("/api/assets/" + idB)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(assetJson(nameA, "SN-B", categoryId, "GOOD")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("conflict"));
	}

	private long createAsset(String name, String condition) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/assets")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(assetJson(name, "SN-" + UUID.randomUUID().toString().substring(0, 8), categoryId, condition)))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
	}

	private String getLastConditionUpdatedAt(long id) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/assets/" + id)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString()).get("lastConditionUpdatedAt");
		return node == null || node.isNull() ? null : node.asString();
	}

	private String assetJson(String name, String serialno, Long categoryId, String condition) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("serialno", serialno);
		body.put("categoryId", categoryId);
		body.put("baseDailyRate", 100);
		body.put("minDailyRate", 90);
		body.put("maxDailyRate", 120);
		if (condition != null) {
			body.put("condition", condition);
		}
		return objectMapper.writeValueAsString(body);
	}

	private String uniqueName() {
		return "Test Asset " + UUID.randomUUID();
	}

	private String registerAndLogin(UserRole role) throws Exception {
		String email = "asset_admin_test_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		String password = "password123";
		userRepository.save(User.builder()
				.name("Asset Test User " + UUID.randomUUID().toString().substring(0, 8))
				.password(passwordEncoder.encode(password))
				.email(email)
				.role(role)
				.enabled(true)
				.build());

		String interim = mockMvc.perform(get("/api/auth/getBearerToken"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"%s"}
							""".formatted(email, password)))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode node = objectMapper.readTree(loginResult.getResponse().getContentAsString());
		return node.get("accessToken").asString();
	}
}
