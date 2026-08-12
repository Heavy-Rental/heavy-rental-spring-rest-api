package com.heavy_rental.rest_api.client.haystack;

/**
 * Outbound haystack call failure.
 * <p>
 * Carries HTTP status (or 0 for transport), a stable {@code errorCode}, and a {@link Kind}
 * used for portal mapping ({@code recommender_*} codes) and retry eligibility.
 * Mapped to portal JSON by {@link com.heavy_rental.rest_api.config.RestExceptionHandler}.
 */
public class HaystackException extends RuntimeException {

	/**
	 * Failure class for retry policy and HTTP mapping.
	 * Only {@link #UPSTREAM}, {@link #TIMEOUT}, and {@link #TRANSPORT} are retryable.
	 */
	public enum Kind {
		/** Haystack 4xx — do not success-retry. */
		CLIENT,
		/** Haystack 5xx. */
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

	public HaystackException(int status, String errorCode, String message, Kind kind) {
		super(message);
		this.status = status;
		this.errorCode = errorCode;
		this.kind = kind;
	}

	public HaystackException(int status, String errorCode, String message, Kind kind, Throwable cause) {
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

	public boolean isClientError() {
		return kind == Kind.CLIENT;
	}

	/** True for transient failures eligible for Resilience4j retry (not 4xx / CB open). */
	public boolean isRetryable() {
		return kind == Kind.UPSTREAM || kind == Kind.TIMEOUT || kind == Kind.TRANSPORT;
	}
}
