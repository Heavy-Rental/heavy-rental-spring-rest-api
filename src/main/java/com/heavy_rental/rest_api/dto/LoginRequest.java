package com.heavy_rental.rest_api.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
		@NotBlank(message = "Email is required") String email,
		@NotBlank(message = "Password is required") String password) {

	public LoginRequest {
		email = email == null ? null : email.strip();
	}
}
