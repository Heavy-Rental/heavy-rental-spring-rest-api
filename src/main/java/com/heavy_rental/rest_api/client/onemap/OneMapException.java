package com.heavy_rental.rest_api.client.onemap;

/**
 * Outbound OneMap call failure (token fetch or search).
 * <p>
 * Carries HTTP status (or 0 for transport), a stable {@code errorCode}, and a {@link Kind} used
 * for circuit-breaker failure counting. Mirrors {@code HaystackException}'s shape.
 * <p>
 * Always caught internally — {@code DistanceService} falls back to {@code pricing.default-distance-km}
 * and {@code PostalCodeService} maps it to a {@code 503}; it never reaches
 * {@code RestExceptionHandler} unhandled.
 */
public class OneMapException extends RuntimeException {

	/**
	 * Failure class for circuit-breaker eligibility and HTTP mapping.
	 * Only {@link #UPSTREAM}, {@link #TIMEOUT}, and {@link #TRANSPORT} are retryable.
	 */
	public enum Kind {
		/** OneMap 4xx — not retryable. */
		CLIENT,
		/** OneMap 5xx. */
		UPSTREAM,
		/** Client read/connect timeout. */
		TIMEOUT,
		/** Circuit open or bulkhead full. */
		UNAVAILABLE,
		/** Network / RestClient transport error. */
		TRANSPORT
	}

	private final int status;
	private final String errorCode;
	private final Kind kind;

	public OneMapException(int status, String errorCode, String message, Kind kind) {
		super(message);
		this.status = status;
		this.errorCode = errorCode;
		this.kind = kind;
	}

	public OneMapException(int status, String errorCode, String message, Kind kind, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.errorCode = errorCode;
		this.kind = kind;
	}

	public int getStatus() {
		return status;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public Kind getKind() {
		return kind;
	}

	/**
	 * True for transient failures that should count toward circuit-breaker failure rate.
	 * There is deliberately no Resilience4j {@code Retry} for OneMap (see {@link OneMapClient}) —
	 * this still gates the circuit breaker the same way {@code HaystackException.isRetryable()} does.
	 */
	public boolean isRetryable() {
		return kind == Kind.UPSTREAM || kind == Kind.TIMEOUT || kind == Kind.TRANSPORT;
	}
}
