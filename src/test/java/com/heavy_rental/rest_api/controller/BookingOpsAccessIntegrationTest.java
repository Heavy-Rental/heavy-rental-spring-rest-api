package com.heavy_rental.rest_api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.entity.User.UserRole;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// HR-189: the mobile ops app (bookings/deliveries/returns) must be usable by ADMIN/DRIVER, never
// ROLE_USER — and a ROLE_USER caller on /api/bookings must only ever see their own bookings, not
// every customer's. Covers the SecurityConfig matchers and BookingService ownership scoping
// end-to-end (real JWTs, real security filter chain), since BookingServiceTest mocks those out.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookingOpsAccessIntegrationTest {

	private static final String PASSWORD = "password123"; // nosemgrep: generic.secrets.security.detected-generic-secret

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	private String customerToken;
	private String adminToken;
	private String driverToken;
	private Long ownBookingId;
	private Long otherCustomerBookingId;

	@BeforeEach
	void setUp() throws Exception {
		User customer = registerUser(UserRole.USER);
		User otherCustomer = registerUser(UserRole.USER);
		User admin = registerUser(UserRole.ADMIN);
		User driver = registerUser(UserRole.DRIVER);

		customerToken = login(customer.getEmail());
		adminToken = login(admin.getEmail());
		driverToken = login(driver.getEmail());

		ownBookingId = saveBooking(customer).getId();
		otherCustomerBookingId = saveBooking(otherCustomer).getId();
	}

	@Test
	void customerSeesOnlyTheirOwnBookings() throws Exception {
		mockMvc.perform(get("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].bookingId").value(ownBookingId));
	}

	@Test
	void customerCannotGetAnotherCustomersBookingById() throws Exception {
		mockMvc.perform(get("/api/bookings/" + otherCustomerBookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void adminSeesEveryCustomersBookings() throws Exception {
		mockMvc.perform(get("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
	}

	@Test
	void driverSeesEveryCustomersBookings() throws Exception {
		mockMvc.perform(get("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
	}

	@Test
	void driverCanGetAnyCustomersBookingById() throws Exception {
		mockMvc.perform(get("/api/bookings/" + otherCustomerBookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(otherCustomerBookingId));
	}

	@Test
	void customerForbiddenFromDeliveries() throws Exception {
		mockMvc.perform(get("/api/deliveries").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void adminAllowedOnDeliveries() throws Exception {
		mockMvc.perform(get("/api/deliveries").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk());
	}

	@Test
	void driverAllowedOnDeliveries() throws Exception {
		mockMvc.perform(get("/api/deliveries").header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken))
				.andExpect(status().isOk());
	}

	@Test
	void customerForbiddenFromReturns() throws Exception {
		mockMvc.perform(get("/api/returns").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	@Test
	void adminAllowedOnReturns() throws Exception {
		mockMvc.perform(get("/api/returns").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isOk());
	}

	@Test
	void driverAllowedOnReturns() throws Exception {
		mockMvc.perform(get("/api/returns").header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken))
				.andExpect(status().isOk());
	}

	@Test
	void driverCanLogOut() throws Exception {
		mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Logged out successfully"));
	}

	private User registerUser(UserRole role) {
		String email = "booking_ops_test_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		return userRepository.save(User.builder()
				.name("Booking Ops Test " + UUID.randomUUID().toString().substring(0, 8))
				.password(passwordEncoder.encode(PASSWORD))
				.email(email)
				.role(role)
				.enabled(true)
				.build());
	}

	private Booking saveBooking(User owner) {
		Booking booking = new Booking();
		booking.setCustomer(owner);
		booking.setStartDate(LocalDate.of(2026, 9, 1));
		booking.setEndDate(LocalDate.of(2026, 9, 5));
		booking.setStatus(Booking.BookingStatus.PENDING_DEPOSIT);
		booking.setSiteAddress("1 Test St, 123456");
		return bookingRepository.save(booking);
	}

	private String login(String email) throws Exception {
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
							""".formatted(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode node = objectMapper.readTree(loginResult.getResponse().getContentAsString());
		return node.get("accessToken").asString();
	}
}
