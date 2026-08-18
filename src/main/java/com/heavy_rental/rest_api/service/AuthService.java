package com.heavy_rental.rest_api.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.heavy_rental.rest_api.dto.GoogleLoginRequest;
import com.heavy_rental.rest_api.dto.LoginRequest;
import com.heavy_rental.rest_api.dto.LoginResponse;
import com.heavy_rental.rest_api.dto.MessageResponse;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.heavy_rental.rest_api.security.GoogleTokenVerifier;
import com.heavy_rental.rest_api.security.JwtService;
import com.heavy_rental.rest_api.security.TokenDenylist;

@Service
public class AuthService {

	private static final List<String> INTERIM_ROLES = List.of("ROLE_INTERIM");

	private final JwtService jwtService;
	private final AuthenticationManager authenticationManager;
	private final TokenDenylist tokenDenylist;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final GoogleTokenVerifier googleTokenVerifier;

	public AuthService(
			JwtService jwtService,
			AuthenticationManager authenticationManager,
			TokenDenylist tokenDenylist,
			UserRepository userRepository,
			PasswordEncoder passwordEncoder,
			GoogleTokenVerifier googleTokenVerifier) {
		this.jwtService = jwtService;
		this.authenticationManager = authenticationManager;
		this.tokenDenylist = tokenDenylist;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.googleTokenVerifier = googleTokenVerifier;
	}

	/**
	 * Mint an interim JWT (random UUID subject, {@code ROLE_INTERIM}).
	 * Returns only the raw token string (no {@code Bearer} prefix).
	 */
	public String getBearerToken() {
		String uuid = UUID.randomUUID().toString();
		Instant generatedAt = Instant.now();
		Jwt jwt = jwtService.generateToken(
				uuid,
				INTERIM_ROLES,
				generatedAt,
				JwtService.TOKEN_TYPE_INTERIM);
		return jwt.getTokenValue();
	}

	/**
	 * Upgrade an interim JWT to a session access JWT after Spring Security authentication.
	 * Denylists the interim {@code jti} so it cannot be reused.
	 */
	public LoginResponse login(LoginRequest request, Jwt interimJwt) {
		if (interimJwt == null || !JwtService.isInterim(interimJwt)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Interim bearer token required");
		}

		if (request == null
				|| request.email() == null || request.email().isBlank()
				|| request.password() == null || request.password().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
		}

		Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email().trim(), request.password()));

		List<String> roles = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.filter(authority -> authority.startsWith("ROLE_"))
				.filter(authority -> !authority.equals("ROLE_INTERIM"))
				.collect(Collectors.toList());

		if (roles.isEmpty()) {
			roles = List.of("ROLE_USER");
		}

		Jwt accessJwt = jwtService.generateToken(
				authentication.getName(),
				roles,
				Instant.now(),
				JwtService.TOKEN_TYPE_ACCESS);

		tokenDenylist.deny(interimJwt.getId(), interimJwt.getExpiresAt());

		return new LoginResponse(
				accessJwt.getTokenValue(),
				"Bearer",
				jwtService.getExpiresInSeconds(),
				authentication.getName());
	}

	/**
	 * Same interim -> access handshake as {@link #login}, but authenticates via a Google-issued
	 * ID token instead of a password. This is the mobile ops app's sign-in path, so a first-time
	 * sign-in auto-provisions a {@code ROLE_DRIVER} account (never auto-elevates to ADMIN — that
	 * stays a manual {@code POST /api/users} action) and links to an existing account by email
	 * otherwise.
	 */
	public LoginResponse loginWithGoogle(GoogleLoginRequest request, Jwt interimJwt) {
		if (interimJwt == null || !JwtService.isInterim(interimJwt)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Interim bearer token required");
		}
		if (request == null || request.idToken() == null || request.idToken().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google ID token is required");
		}

		GoogleIdToken.Payload payload = googleTokenVerifier.verify(request.idToken());
		if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google email not verified");
		}
		String email = payload.getEmail();

		User user = userRepository.findByEmail(email)
				.orElseGet(() -> provisionGoogleUser(payload, email));

		if (!user.isEnabled()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is disabled");
		}

		List<String> roles = List.of("ROLE_" + user.getRole().name());
		Jwt accessJwt = jwtService.generateToken(user.getEmail(), roles, Instant.now(), JwtService.TOKEN_TYPE_ACCESS);

		tokenDenylist.deny(interimJwt.getId(), interimJwt.getExpiresAt());

		return new LoginResponse(
				accessJwt.getTokenValue(),
				"Bearer",
				jwtService.getExpiresInSeconds(),
				user.getEmail());
	}

	/**
	 * Revoke the current access token via denylist.
	 */
	public MessageResponse logout(Jwt accessJwt) {
		if (accessJwt == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token required");
		}
		if (!JwtService.isAccess(accessJwt)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access bearer token required");
		}
		tokenDenylist.deny(accessJwt.getId(), accessJwt.getExpiresAt());
		return new MessageResponse("Logged out successfully");
	}

	private User provisionGoogleUser(GoogleIdToken.Payload payload, String email) {
		Object nameClaim = payload.get("name");
		String baseName = nameClaim != null ? String.valueOf(nameClaim) : email.substring(0, email.indexOf('@'));

		User newUser = User.builder()
				.name(baseName)
				.email(email)
				// Unusable random hash: this account only ever authenticates via Google.
				.password(passwordEncoder.encode(UUID.randomUUID().toString()))
				// The mobile app is staff-only (admin/driver); a self-serve new account is a driver.
				.role(User.UserRole.DRIVER)
				.enabled(true)
				.createdAt(LocalDateTime.now())
				.build();

		try {
			return userRepository.save(newUser);
		} catch (DataIntegrityViolationException e) {
			// "name" is the unique column; Google's display name collided with an existing user's.
			newUser.setName(baseName + "-" + UUID.randomUUID().toString().substring(0, 8));
			return userRepository.save(newUser);
		}
	}
}