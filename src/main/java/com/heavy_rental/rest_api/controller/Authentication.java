package com.heavy_rental.rest_api.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.service.AuthService;

/**
 * JWT authentication REST endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/auth/getBearerToken} — random UUID + date/time → raw JWT only</li>
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
	 * Issue a JWT access token derived from a random UUID and the current date/time
	 * (plain text, no JSON wrapper, no {@code Bearer} prefix).
	 *
	 * <pre>
	 * GET /api/auth/getBearerToken
	 * </pre>
	 */
	@GetMapping(value = "/getBearerToken", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> getBearerToken() {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_PLAIN)
				.body(authService.getBearerToken());
	}
}
