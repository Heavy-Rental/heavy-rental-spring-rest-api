package com.heavy_rental.rest_api.client.haystack;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.heavy_rental.rest_api.config.PricingProperties;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Spring beans for the haystack RestClient and Resilience4j decorators (S2b).
 * <p>
 * Builds a shared circuit breaker {@code haystack}, per-op bulkheads
 * (ingest / recommend / qa), and retries with exponential backoff + jitter.
 * RestClient instances themselves are constructed per-operation inside
 * {@link HaystackRecommenderClient} with distinct read timeouts.
 */
@Configuration
@EnableConfigurationProperties({HaystackProperties.class, PricingProperties.class})
public class HaystackClientConfig {

	/** Shared RestClient.Builder seed (base URL and timeouts applied per op in the client). */
	@Bean
	RestClient.Builder haystackRestClientBuilder() {
		return RestClient.builder();
	}

	/**
	 * Single CB for all haystack calls — open → fail-fast {@code recommender_unavailable}.
	 */
	@Bean
	CircuitBreaker haystackCircuitBreaker(HaystackProperties props) {
		var r = props.getResilience();
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.failureRateThreshold(r.getCircuitBreakerFailureRateThreshold())
				.slidingWindowSize(r.getCircuitBreakerSlidingWindowSize())
				.minimumNumberOfCalls(r.getCircuitBreakerMinimumNumberOfCalls())
				.waitDurationInOpenState(r.getCircuitBreakerWaitDurationInOpenState())
				.recordException(ex -> {
					if (ex instanceof HaystackException he) {
						return he.isRetryable() || he.getKind() == HaystackException.Kind.UPSTREAM
								|| he.getKind() == HaystackException.Kind.TIMEOUT
								|| he.getKind() == HaystackException.Kind.TRANSPORT;
					}
					return true;
				})
				.build();
		return CircuitBreaker.of("haystack", config);
	}

	@Bean
	Bulkhead haystackIngestBulkhead(HaystackProperties props) {
		BulkheadConfig config = BulkheadConfig.custom()
				.maxConcurrentCalls(props.getResilience().getBulkheadIngestMaxConcurrent())
				.maxWaitDuration(Duration.ZERO)
				.build();
		return Bulkhead.of("haystackIngest", config);
	}

	@Bean
	Bulkhead haystackRecommendBulkhead(HaystackProperties props) {
		BulkheadConfig config = BulkheadConfig.custom()
				.maxConcurrentCalls(props.getResilience().getBulkheadRecommendMaxConcurrent())
				.maxWaitDuration(Duration.ZERO)
				.build();
		return Bulkhead.of("haystackRecommend", config);
	}

	@Bean
	Bulkhead haystackQaBulkhead(HaystackProperties props) {
		BulkheadConfig config = BulkheadConfig.custom()
				.maxConcurrentCalls(props.getResilience().getBulkheadQaMaxConcurrent())
				.maxWaitDuration(Duration.ZERO)
				.build();
		return Bulkhead.of("haystackQa", config);
	}

	/** Rental-plan quote pricing (dynamic-plan-quote-pricing) — separate from the recommender saga. */
	@Bean
	Bulkhead haystackPricingBulkhead(HaystackProperties props) {
		BulkheadConfig config = BulkheadConfig.custom()
				.maxConcurrentCalls(props.getResilience().getBulkheadPricingMaxConcurrent())
				.maxWaitDuration(Duration.ZERO)
				.build();
		return Bulkhead.of("haystackPricing", config);
	}

	@Bean
	Retry haystackIngestRetry(HaystackProperties props) {
		int maxAttempts = Math.max(1, props.getRetry().getIngestMaxAttempts());
		RetryConfig config = RetryConfig.custom()
				.maxAttempts(props.getRetry().isIngestEnabled() ? maxAttempts : 1)
				// Plan B3: exponential backoff + jitter (base 100ms, multiplier 2, randomized)
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
						Duration.ofMillis(100), 2.0d, 0.5d))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build();
		return Retry.of("haystackIngest", config);
	}

	@Bean
	Retry haystackRecommendRetry(HaystackProperties props) {
		int maxAttempts = Math.max(1, props.getRetry().getRecommendMaxAttempts());
		RetryConfig config = RetryConfig.custom()
				.maxAttempts(maxAttempts)
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
						Duration.ofMillis(100), 2.0d, 0.5d))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build();
		return Retry.of("haystackRecommend", config);
	}

	@Bean
	Retry haystackQaRetry(HaystackProperties props) {
		int maxAttempts = Math.max(1, props.getRetry().getQaMaxAttempts());
		RetryConfig config = RetryConfig.custom()
				.maxAttempts(maxAttempts)
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
						Duration.ofMillis(100), 2.0d, 0.5d))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build();
		return Retry.of("haystackQa", config);
	}

	@Bean
	Retry haystackPricingRetry(HaystackProperties props) {
		int maxAttempts = Math.max(1, props.getRetry().getPricingMaxAttempts());
		RetryConfig config = RetryConfig.custom()
				.maxAttempts(maxAttempts)
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
						Duration.ofMillis(100), 2.0d, 0.5d))
				.retryOnException(ex -> ex instanceof HaystackException he && he.isRetryable())
				.build();
		return Retry.of("haystackPricing", config);
	}

	@Bean
	HaystackRecommenderClient haystackRecommenderClient(
			HaystackProperties properties,
			@Qualifier("haystackRestClientBuilder") RestClient.Builder restClientBuilder,
			ObjectMapper objectMapper,
			CircuitBreaker haystackCircuitBreaker,
			Bulkhead haystackIngestBulkhead,
			Bulkhead haystackRecommendBulkhead,
			Bulkhead haystackQaBulkhead,
			Retry haystackIngestRetry,
			Retry haystackRecommendRetry,
			Retry haystackQaRetry) {
		return new HaystackRecommenderClient(
				properties,
				restClientBuilder,
				objectMapper,
				haystackCircuitBreaker,
				haystackIngestBulkhead,
				haystackRecommendBulkhead,
				haystackQaBulkhead,
				haystackIngestRetry,
				haystackRecommendRetry,
				haystackQaRetry);
	}

	/**
	 * Rental-plan quote pricing client — reuses the shared {@code haystackCircuitBreaker}
	 * (same "haystack is up or down" fact as the recommender client) but its own bulkhead/retry,
	 * matching the per-operation pattern already used for health/ingest/recommend/qa.
	 */
	@Bean
	HaystackPricingClient haystackPricingClient(
			HaystackProperties properties,
			@Qualifier("haystackRestClientBuilder") RestClient.Builder restClientBuilder,
			ObjectMapper objectMapper,
			CircuitBreaker haystackCircuitBreaker,
			Bulkhead haystackPricingBulkhead,
			Retry haystackPricingRetry) {
		return new HaystackPricingClient(
				properties,
				restClientBuilder,
				objectMapper,
				haystackCircuitBreaker,
				haystackPricingBulkhead,
				haystackPricingRetry);
	}

	/**
	 * Builds a blocking RestClient with connect + read timeouts (SimpleClientHttpRequestFactory).
	 */
	static RestClient buildRestClient(RestClient.Builder builder, String baseUrl, Duration connect, Duration read) {
		// SimpleClientHttpRequestFactory timeouts are portable across Spring 7 / Boot 4
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, connect.toMillis()));
		factory.setReadTimeout((int) Math.min(Integer.MAX_VALUE, read.toMillis()));
		return builder.baseUrl(baseUrl).requestFactory(factory).build();
	}
}
