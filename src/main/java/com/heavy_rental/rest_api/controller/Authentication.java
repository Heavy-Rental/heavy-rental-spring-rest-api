package com.heavy_rental.rest_api.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.dto.LoginRequest;
import com.heavy_rental.rest_api.dto.LoginResponse;
import com.heavy_rental.rest_api.dto.MessageResponse;
import com.heavy_rental.rest_api.service.AuthService;

/**
 * JWT authentication REST endpoints (multi-step: interim → login → logout).
 *
 * <ul>
 *   <li>{@code GET /api/auth/getBearerToken} — public interim JWT</li>
 *   <li>{@code POST /api/auth/login} — interim Bearer + credentials → access JWT</li>
 *   <li>{@code POST /api/auth/logout} — access Bearer → revoke</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class Authentication {

	private final AuthService authService;

	public Authentication(AuthService authService) {
		this.authService = authService;
	}

	/**
	 * Issue an interim JWT (random UUID + date/time, {@code ROLE_INTERIM}).
	 * Plain text body, no {@code Bearer} prefix.
	 */
	@GetMapping(value = "/getBearerToken", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> getBearerToken() {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_PLAIN)
				.body(authService.getBearerToken());
	}

	/**
	 * Authenticate with username/password using an interim Bearer token.
	 * Returns a session access JWT.
	 */
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(
			@AuthenticationPrincipal Jwt interimJwt,
			@RequestBody LoginRequest request) {
		return ResponseEntity.ok(authService.login(request, interimJwt));
	}

	/**
	 * Revoke the current access Bearer token.
	 */
	@PostMapping("/logout")
	public ResponseEntity<MessageResponse> logout(@AuthenticationPrincipal Jwt accessJwt) {
		return ResponseEntity.ok(authService.logout(accessJwt));
	}
}
