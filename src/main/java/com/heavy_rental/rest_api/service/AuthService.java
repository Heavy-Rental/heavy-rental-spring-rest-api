package com.heavy_rental.rest_api.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.LoginRequest;
import com.heavy_rental.rest_api.dto.LoginResponse;
import com.heavy_rental.rest_api.dto.MessageResponse;
import com.heavy_rental.rest_api.security.JwtService;
import com.heavy_rental.rest_api.security.TokenDenylist;

@Service
public class AuthService {

	private static final List<String> INTERIM_ROLES = List.of("ROLE_INTERIM");

	private final JwtService jwtService;
	private final AuthenticationManager authenticationManager;
	private final TokenDenylist tokenDenylist;

	public AuthService(
			JwtService jwtService,
			AuthenticationManager authenticationManager,
			TokenDenylist tokenDenylist) {
		this.jwtService = jwtService;
		this.authenticationManager = authenticationManager;
		this.tokenDenylist = tokenDenylist;
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
				|| request.username() == null || request.username().isBlank()
				|| request.password() == null || request.password().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
		}

		Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.username().trim(), request.password()));

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
}
