package com.heavy_rental.rest_api.client.haystack;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration for the haystack recommender client ({@code haystack.*} in application.properties).
 * <p>
 * Timeout matrix (defaults): health 5s ≪ Q&amp;A 45s ≪ recommend 90s ≪ ingest 180s.
 * Production ingest retry stays off until haystack S2a is confirmed on the target environment.
 */
@ConfigurationProperties(prefix = "haystack")
public class HaystackProperties {

	private String baseUrl = "http://localhost:8000";
	private Timeouts timeouts = new Timeouts();
	private Retry retry = new Retry();
	private Resilience resilience = new Resilience();
	/**
	 * Max body size for future multipart ingest / large responses.
	 * Bound from {@code haystack.max-in-memory-size}; applied when multipart RestClient
	 * support lands. JSON-only S2b paths do not allocate a large codec buffer today.
	 */
	private DataSize maxInMemorySize = DataSize.ofMegabytes(20);

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public Timeouts getTimeouts() {
		return timeouts;
	}

	public void setTimeouts(Timeouts timeouts) {
		this.timeouts = timeouts;
	}

	public Retry getRetry() {
		return retry;
	}

	public void setRetry(Retry retry) {
		this.retry = retry;
	}

	public Resilience getResilience() {
		return resilience;
	}

	public void setResilience(Resilience resilience) {
		this.resilience = resilience;
	}

	public DataSize getMaxInMemorySize() {
		return maxInMemorySize;
	}

	public void setMaxInMemorySize(DataSize maxInMemorySize) {
		this.maxInMemorySize = maxInMemorySize;
	}

	/** Per-operation connect/read timeouts. */
	public static class Timeouts {
		private Duration connect = Duration.ofSeconds(5);
		private Duration healthRead = Duration.ofSeconds(5);
		/** Call 3 chatbot Q&amp;A read timeout. */
		private Duration qaRead = Duration.ofSeconds(45);
		/** Call 2 recommend / quote read timeout (typically longer than Q&amp;A). */
		private Duration recommendRead = Duration.ofSeconds(90);
		/** Call 1 ingest read timeout (longest; measure p95 in ops). */
		private Duration ingestRead = Duration.ofSeconds(180);
		/** Rental-plan quote pricing read timeout. */
		private Duration pricingRead = Duration.ofSeconds(20);

		public Duration getConnect() {
			return connect;
		}

		public void setConnect(Duration connect) {
			this.connect = connect;
		}

		public Duration getHealthRead() {
			return healthRead;
		}

		public void setHealthRead(Duration healthRead) {
			this.healthRead = healthRead;
		}

		public Duration getQaRead() {
			return qaRead;
		}

		public void setQaRead(Duration qaRead) {
			this.qaRead = qaRead;
		}

		public Duration getRecommendRead() {
			return recommendRead;
		}

		public void setRecommendRead(Duration recommendRead) {
			this.recommendRead = recommendRead;
		}

		public Duration getIngestRead() {
			return ingestRead;
		}

		public void setIngestRead(Duration ingestRead) {
			this.ingestRead = ingestRead;
		}

		public Duration getPricingRead() {
			return pricingRead;
		}

		public void setPricingRead(Duration pricingRead) {
			this.pricingRead = pricingRead;
		}
	}

	/** Retry policy knobs; ingest retries only when {@link #ingestEnabled} is true. */
	public static class Retry {
		/** When false (default), ingest is not retried — require haystack S2a in prod before enabling. */
		private boolean ingestEnabled = false;
		private int ingestMaxAttempts = 2;
		private int recommendMaxAttempts = 2;
		private int qaMaxAttempts = 2;
		private int pricingMaxAttempts = 1;

		public boolean isIngestEnabled() {
			return ingestEnabled;
		}

		public void setIngestEnabled(boolean ingestEnabled) {
			this.ingestEnabled = ingestEnabled;
		}

		public int getIngestMaxAttempts() {
			return ingestMaxAttempts;
		}

		public void setIngestMaxAttempts(int ingestMaxAttempts) {
			this.ingestMaxAttempts = ingestMaxAttempts;
		}

		public int getRecommendMaxAttempts() {
			return recommendMaxAttempts;
		}

		public void setRecommendMaxAttempts(int recommendMaxAttempts) {
			this.recommendMaxAttempts = recommendMaxAttempts;
		}

		public int getQaMaxAttempts() {
			return qaMaxAttempts;
		}

		public void setQaMaxAttempts(int qaMaxAttempts) {
			this.qaMaxAttempts = qaMaxAttempts;
		}

		public int getPricingMaxAttempts() {
			return pricingMaxAttempts;
		}

		public void setPricingMaxAttempts(int pricingMaxAttempts) {
			this.pricingMaxAttempts = pricingMaxAttempts;
		}
	}

	/** Circuit breaker and bulkhead concurrency limits. */
	public static class Resilience {
		private float circuitBreakerFailureRateThreshold = 50f;
		private int circuitBreakerSlidingWindowSize = 10;
		private int circuitBreakerMinimumNumberOfCalls = 5;
		private Duration circuitBreakerWaitDurationInOpenState = Duration.ofSeconds(30);
		private int bulkheadIngestMaxConcurrent = 5;
		private int bulkheadRecommendMaxConcurrent = 10;
		private int bulkheadQaMaxConcurrent = 10;
		private int bulkheadPricingMaxConcurrent = 10;

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

		public int getBulkheadIngestMaxConcurrent() {
			return bulkheadIngestMaxConcurrent;
		}

		public void setBulkheadIngestMaxConcurrent(int bulkheadIngestMaxConcurrent) {
			this.bulkheadIngestMaxConcurrent = bulkheadIngestMaxConcurrent;
		}

		public int getBulkheadRecommendMaxConcurrent() {
			return bulkheadRecommendMaxConcurrent;
		}

		public void setBulkheadRecommendMaxConcurrent(int bulkheadRecommendMaxConcurrent) {
			this.bulkheadRecommendMaxConcurrent = bulkheadRecommendMaxConcurrent;
		}

		public int getBulkheadQaMaxConcurrent() {
			return bulkheadQaMaxConcurrent;
		}

		public void setBulkheadQaMaxConcurrent(int bulkheadQaMaxConcurrent) {
			this.bulkheadQaMaxConcurrent = bulkheadQaMaxConcurrent;
		}

		public int getBulkheadPricingMaxConcurrent() {
			return bulkheadPricingMaxConcurrent;
		}

		public void setBulkheadPricingMaxConcurrent(int bulkheadPricingMaxConcurrent) {
			this.bulkheadPricingMaxConcurrent = bulkheadPricingMaxConcurrent;
		}
	}
}
