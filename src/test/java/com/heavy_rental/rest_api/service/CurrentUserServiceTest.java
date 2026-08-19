package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.UserRepository;

// HR-189: the ownership-bypass logic BookingServiceTest mocks away — verified directly here.
@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

	@Mock private UserRepository userRepository;

	private CurrentUserService service;
	private User owner;
	private Jwt jwt;

	@BeforeEach
	void setUp() {
		service = new CurrentUserService(userRepository);

		owner = new User();
		owner.setId(1L);
		owner.setEmail("mei.lin@example.sg");

		jwt = mock(Jwt.class);
	}

	private void stubRoles(String... roles) {
		when(jwt.getClaim("roles")).thenReturn(List.of(roles));
	}

	@Test
	void assertOwnerOrAdmin_ownerPasses() {
		stubRoles("ROLE_USER");
		when(jwt.getSubject()).thenReturn(owner.getEmail());

		assertThatCode(() -> service.assertOwnerOrAdmin(jwt, owner)).doesNotThrowAnyException();
	}

	@Test
	void assertOwnerOrAdmin_adminBypassesOwnership() {
		stubRoles("ROLE_ADMIN");

		assertThatCode(() -> service.assertOwnerOrAdmin(jwt, owner)).doesNotThrowAnyException();
	}

	@Test
	void assertOwnerOrAdmin_driverDoesNotBypassOwnership() {
		stubRoles("ROLE_DRIVER");
		when(jwt.getSubject()).thenReturn("someone.else@example.sg");

		assertThatThrownBy(() -> service.assertOwnerOrAdmin(jwt, owner))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
						.isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void assertOwnerOrAdmin_nonOwnerRejected() {
		stubRoles("ROLE_USER");
		when(jwt.getSubject()).thenReturn("someone.else@example.sg");

		assertThatThrownBy(() -> service.assertOwnerOrAdmin(jwt, owner))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
						.isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void assertOwnerOrStaff_ownerPasses() {
		stubRoles("ROLE_USER");
		when(jwt.getSubject()).thenReturn(owner.getEmail());

		assertThatCode(() -> service.assertOwnerOrStaff(jwt, owner)).doesNotThrowAnyException();
	}

	@Test
	void assertOwnerOrStaff_adminBypassesOwnership() {
		stubRoles("ROLE_ADMIN");

		assertThatCode(() -> service.assertOwnerOrStaff(jwt, owner)).doesNotThrowAnyException();
	}

	@Test
	void assertOwnerOrStaff_driverBypassesOwnership() {
		stubRoles("ROLE_DRIVER");

		assertThatCode(() -> service.assertOwnerOrStaff(jwt, owner)).doesNotThrowAnyException();
	}

	@Test
	void assertOwnerOrStaff_nonOwnerCustomerRejected() {
		stubRoles("ROLE_USER");
		when(jwt.getSubject()).thenReturn("someone.else@example.sg");

		assertThatThrownBy(() -> service.assertOwnerOrStaff(jwt, owner))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
						.isEqualTo(HttpStatus.FORBIDDEN));
	}
}
