package com.heavy_rental.rest_api.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.heavy_rental.rest_api.security.JwtService;

@Service
public class AuthService {

	private static final List<String> DEFAULT_ROLES = List.of("ROLE_USER");

	private final JwtService jwtService;

	public AuthService(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	/**
	 * Issue a JWT access token using a random UUID subject and the current date/time.
	 * Returns only the raw token string (no {@code Bearer} prefix).
	 */
	public String getBearerToken() {
		String uuid = UUID.randomUUID().toString();
		Instant generatedAt = Instant.now();
		Jwt jwt = jwtService.generateToken(uuid, DEFAULT_ROLES, generatedAt);
		return jwt.getTokenValue();
	}
}
