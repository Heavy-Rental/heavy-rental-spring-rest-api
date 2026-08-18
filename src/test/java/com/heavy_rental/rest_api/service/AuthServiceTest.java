package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.heavy_rental.rest_api.dto.GoogleLoginRequest;
import com.heavy_rental.rest_api.dto.LoginResponse;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.heavy_rental.rest_api.security.GoogleTokenVerifier;
import com.heavy_rental.rest_api.security.JwtService;
import com.heavy_rental.rest_api.security.TokenDenylist;

// Unit tests for AuthService.loginWithGoogle: the mobile ops app is staff-only, so a first-time
// Google sign-in must provision a ROLE_DRIVER account (never ROLE_USER), while an existing
// account keeps whatever role it already has.
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock private JwtService jwtService;
	@Mock private AuthenticationManager authenticationManager;
	@Mock private TokenDenylist tokenDenylist;
	@Mock private UserRepository userRepository;
	@Mock private PasswordEncoder passwordEncoder;
	@Mock private GoogleTokenVerifier googleTokenVerifier;

	private AuthService service;
	private Jwt interimJwt;

	@BeforeEach
	void setUp() {
		service = new AuthService(
				jwtService, authenticationManager, tokenDenylist, userRepository, passwordEncoder, googleTokenVerifier);

		interimJwt = mock(Jwt.class);
		when(interimJwt.getClaim(JwtService.CLAIM_TOKEN_TYPE)).thenReturn(JwtService.TOKEN_TYPE_INTERIM);
		// Only exercised by tests that reach a successful login (the denylist call); lenient so
		// the rejection-path tests don't need to stub bookkeeping they never touch.
		lenient().when(interimJwt.getId()).thenReturn("interim-jti");
		lenient().when(interimJwt.getExpiresAt()).thenReturn(Instant.now().plusSeconds(60));
	}

	@Test
	void newGoogleAccountIsProvisionedAsDriver() {
		String email = "new.driver@example.com";
		GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
				.setEmail(email)
				.setEmailVerified(true);
		payload.set("name", "New Driver");

		when(googleTokenVerifier.verify("valid-id-token")).thenReturn(payload);
		when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
		when(passwordEncoder.encode(anyString())).thenReturn("hashed");
		when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
		Jwt accessJwt = mock(Jwt.class);
		when(accessJwt.getTokenValue()).thenReturn("access-token-value");
		when(jwtService.generateToken(eq(email), any(), any(), eq(JwtService.TOKEN_TYPE_ACCESS)))
				.thenReturn(accessJwt);

		LoginResponse response = service.loginWithGoogle(new GoogleLoginRequest("valid-id-token"), interimJwt);

		ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(savedUser.capture());
		assertThat(savedUser.getValue().getRole()).isEqualTo(User.UserRole.DRIVER);
		assertThat(savedUser.getValue().getEmail()).isEqualTo(email);
		assertThat(response.accessToken()).isEqualTo("access-token-value");

		ArgumentCaptor<List<String>> roles = ArgumentCaptor.forClass(List.class);
		verify(jwtService).generateToken(eq(email), roles.capture(), any(), eq(JwtService.TOKEN_TYPE_ACCESS));
		assertThat(roles.getValue()).containsExactly("ROLE_DRIVER");
	}

	@Test
	void existingAccountKeepsItsRoleOnGoogleSignIn() {
		String email = "existing.admin@example.com";
		GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
				.setEmail(email)
				.setEmailVerified(true);

		User existing = User.builder()
				.id(1L)
				.name("Existing Admin")
				.email(email)
				.password("irrelevant")
				.role(User.UserRole.ADMIN)
				.enabled(true)
				.build();

		when(googleTokenVerifier.verify("valid-id-token")).thenReturn(payload);
		when(userRepository.findByEmail(email)).thenReturn(Optional.of(existing));
		Jwt accessJwt = mock(Jwt.class);
		when(accessJwt.getTokenValue()).thenReturn("access-token-value");
		when(jwtService.generateToken(eq(email), any(), any(), eq(JwtService.TOKEN_TYPE_ACCESS)))
				.thenReturn(accessJwt);

		service.loginWithGoogle(new GoogleLoginRequest("valid-id-token"), interimJwt);

		verify(userRepository, never()).save(any());
		ArgumentCaptor<List<String>> roles = ArgumentCaptor.forClass(List.class);
		verify(jwtService).generateToken(eq(email), roles.capture(), any(), eq(JwtService.TOKEN_TYPE_ACCESS));
		assertThat(roles.getValue()).containsExactly("ROLE_ADMIN");
	}

	@Test
	void unverifiedEmailIsRejected() {
		GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
				.setEmail("unverified@example.com")
				.setEmailVerified(false);
		when(googleTokenVerifier.verify("valid-id-token")).thenReturn(payload);

		assertThatThrownBy(() -> service.loginWithGoogle(new GoogleLoginRequest("valid-id-token"), interimJwt))
				.isInstanceOf(ResponseStatusException.class)
				.hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);

		verify(userRepository, never()).save(any());
	}
}
