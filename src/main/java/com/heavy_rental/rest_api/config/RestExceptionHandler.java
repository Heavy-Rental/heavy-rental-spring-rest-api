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

/**
 * Shared API error body {@code {"error","message"}}.
 * <p>
 * Recommender failures map to stable codes: {@code recommender_unavailable} (503),
 * {@code recommender_timeout} (504), {@code recommender_upstream_error} (502),
 * {@code payload_too_large} (413). {@link com.heavy_rental.rest_api.client.haystack.HaystackException}
 * is converted via {@link com.heavy_rental.rest_api.service.RecommenderSagaService#mapHaystack}.
 */
@RestControllerAdvice
public class RestExceptionHandler {

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(error("invalid_credentials", "Invalid email or password"));
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
			case SERVICE_UNAVAILABLE -> "recommender_unavailable";
			case GATEWAY_TIMEOUT -> "recommender_timeout";
			case BAD_GATEWAY -> "recommender_upstream_error";
			case UNPROCESSABLE_ENTITY -> "bad_request";
			case PAYLOAD_TOO_LARGE -> "payload_too_large";
			default -> "error";
		};
		return ResponseEntity.status(status).body(error(code, message));
	}

	@ExceptionHandler(com.heavy_rental.rest_api.client.haystack.HaystackException.class)
	public ResponseEntity<Map<String, String>> handleHaystack(
			com.heavy_rental.rest_api.client.haystack.HaystackException ex) {
		ResponseStatusException mapped = com.heavy_rental.rest_api.service.RecommenderSagaService.mapHaystack(ex);
		return handleResponseStatus(mapped);
	}

	private static Map<String, String> error(String code, String message) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("error", code);
		body.put("message", message);
		return body;
	}
}
