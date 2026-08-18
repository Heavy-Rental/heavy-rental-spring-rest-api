package com.heavy_rental.rest_api.dto;

public record LoginResponse(
		String accessToken,
		String tokenType,
		long expiresIn,
		String username) {
}
