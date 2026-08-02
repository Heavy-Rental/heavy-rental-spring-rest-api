package com.heavy_rental.rest_api.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class RestExceptionHandler {

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(error("invalid_credentials", "Invalid username or password"));
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<Map<String, String>> handleAuthentication(AuthenticationException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(error("unauthorized", ex.getMessage() != null ? ex.getMessage() : "Authentication failed"));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
		HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
		if (status == null) {
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}
		String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
		String code = switch (status) {
			case BAD_REQUEST -> "bad_request";
			case UNAUTHORIZED -> "unauthorized";
			case FORBIDDEN -> "forbidden";
			case CONFLICT -> "conflict";
			case NOT_FOUND -> "not_found";
			default -> "error";
		};
		return ResponseEntity.status(status).body(error(code, message));
	}

	private static Map<String, String> error(String code, String message) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("error", code);
		body.put("message", message);
		return body;
	}
}
