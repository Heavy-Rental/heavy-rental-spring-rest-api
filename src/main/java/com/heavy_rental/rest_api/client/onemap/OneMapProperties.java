package com.heavy_rental.rest_api.client.onemap;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the OneMap geocoding client ({@code onemap.*} in application.properties).
 * <p>
 * Mirrors {@code HaystackProperties}'s shape (mutable, nested timeout/resilience groups) since
 * tests need the same per-instance {@code baseUrl} override (WireMock's dynamic port).
 */
@ConfigurationProperties(prefix = "onemap")
public class OneMapProperties {

	private String baseUrl = "https://www.onemap.gov.sg";
	private String email = "";
	private String password = "";
	/** How long before actual token expiry to proactively refresh (OneMap tokens last ~3 days). */
	private Duration tokenRefreshBuffer = Duration.ofHours(6);
	private Timeouts timeouts = new Timeouts();
	private Resilience resilience = new Resilience();

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public Duration getTokenRefreshBuffer() {
		return tokenRefreshBuffer;
	}

	public void setTokenRefreshBuffer(Duration tokenRefreshBuffer) {
		this.tokenRefreshBuffer = tokenRefreshBuffer;
	}

	public Timeouts getTimeouts() {
		return timeouts;
	}

	public void setTimeouts(Timeouts timeouts) {
		this.timeouts = timeouts;
	}

	public Resilience getResilience() {
		return resilience;
	}

	public void setResilience(Resilience resilience) {
		this.resilience = resilience;
	}

	/**
	 * Deliberately shorter than haystack's — every OneMap call here sits on the synchronous
	 * quote/validation request path and always has a cheap fallback, so there's no reason to
	 * wait as long as haystack's pricing call before giving up.
	 */
	public static class Timeouts {
		private Duration connect = Duration.ofSeconds(3);
		private Duration read = Duration.ofSeconds(5);

		public Duration getConnect() {
			return connect;
		}

		public void setConnect(Duration connect) {
			this.connect = connect;
		}

		public Duration getRead() {
			return read;
		}

		public void setRead(Duration read) {
			this.read = read;
		}
	}

	/** Single circuit breaker + single bulkhead — see {@link OneMapClientConfig} for why. */
	public static class Resilience {
		private float circuitBreakerFailureRateThreshold = 50f;
		private int circuitBreakerSlidingWindowSize = 10;
		private int circuitBreakerMinimumNumberOfCalls = 5;
		private Duration circuitBreakerWaitDurationInOpenState = Duration.ofSeconds(30);
		private int bulkheadMaxConcurrent = 10;

		public float getCircuitBreakerFailureRateThreshold() {
			return circuitBreakerFailureRateThreshold;
		}

		public void setCircuitBreakerFailureRateThreshold(float circuitBreakerFailureRateThreshold) {
			this.circuitBreakerFailureRateThreshold = circuitBreakerFailureRateThreshold;
		}

		public int getCircuitBreakerSlidingWindowSize() {
			return circuitBreakerSlidingWindowSize;
		}

		public void setCircuitBreakerSlidingWindowSize(int circuitBreakerSlidingWindowSize) {
			this.circuitBreakerSlidingWindowSize = circuitBreakerSlidingWindowSize;
		}

		public int getCircuitBreakerMinimumNumberOfCalls() {
			return circuitBreakerMinimumNumberOfCalls;
		}

		public void setCircuitBreakerMinimumNumberOfCalls(int circuitBreakerMinimumNumberOfCalls) {
			this.circuitBreakerMinimumNumberOfCalls = circuitBreakerMinimumNumberOfCalls;
		}

		public Duration getCircuitBreakerWaitDurationInOpenState() {
			return circuitBreakerWaitDurationInOpenState;
		}

		public void setCircuitBreakerWaitDurationInOpenState(Duration circuitBreakerWaitDurationInOpenState) {
			this.circuitBreakerWaitDurationInOpenState = circuitBreakerWaitDurationInOpenState;
		}

		public int getBulkheadMaxConcurrent() {
			return bulkheadMaxConcurrent;
		}

		public void setBulkheadMaxConcurrent(int bulkheadMaxConcurrent) {
			this.bulkheadMaxConcurrent = bulkheadMaxConcurrent;
		}
	}
}
