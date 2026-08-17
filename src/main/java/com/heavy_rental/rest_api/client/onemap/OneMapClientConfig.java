package com.heavy_rental.rest_api.client.onemap;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * Spring beans for the OneMap RestClient and Resilience4j decorators.
 * <p>
 * Mirrors {@code HaystackClientConfig}'s shape: a shared {@code RestClient.Builder} seed, one
 * circuit breaker. Unlike haystack's four distinct ingest/recommend/qa/pricing bulkheads, OneMap
 * gets a single bulkhead — there is only one operation shape here (geocode), used by both
 * {@code DistanceService} and the postal-code validation endpoint, so per-operation isolation
 * would isolate nothing real. No {@code Retry} bean — see {@link OneMapClient}'s class javadoc.
 */
@Configuration
@EnableConfigurationProperties(OneMapProperties.class)
public class OneMapClientConfig {

	/** Shared RestClient.Builder seed (base URL and timeouts applied per client instance). */
	@Bean
	RestClient.Builder oneMapRestClientBuilder() {
		return RestClient.builder();
	}

	/** Single CB for all OneMap calls (token fetch + search) — open means fail-fast {@code onemap_unavailable}. */
	@Bean
	CircuitBreaker onemapCircuitBreaker(OneMapProperties props) {
		var r = props.getResilience();
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.failureRateThreshold(r.getCircuitBreakerFailureRateThreshold())
				.slidingWindowSize(r.getCircuitBreakerSlidingWindowSize())
				.minimumNumberOfCalls(r.getCircuitBreakerMinimumNumberOfCalls())
				.waitDurationInOpenState(r.getCircuitBreakerWaitDurationInOpenState())
				.recordException(ex -> {
					if (ex instanceof OneMapException ome) {
						return ome.isRetryable();
					}
					return true;
				})
				.build();
		return CircuitBreaker.of("onemap", config);
	}

	@Bean
	Bulkhead onemapBulkhead(OneMapProperties props) {
		BulkheadConfig config = BulkheadConfig.custom()
				.maxConcurrentCalls(props.getResilience().getBulkheadMaxConcurrent())
				.maxWaitDuration(Duration.ZERO)
				.build();
		return Bulkhead.of("onemap", config);
	}

	@Bean
	OneMapAuthService oneMapAuthService(
			OneMapProperties properties,
			@Qualifier("oneMapRestClientBuilder") RestClient.Builder restClientBuilder) {
		return new OneMapAuthService(properties, restClientBuilder);
	}

	@Bean
	OneMapClient oneMapClient(
			OneMapProperties properties,
			@Qualifier("oneMapRestClientBuilder") RestClient.Builder restClientBuilder,
			OneMapAuthService oneMapAuthService,
			CircuitBreaker onemapCircuitBreaker,
			Bulkhead onemapBulkhead) {
		return new OneMapClient(properties, restClientBuilder, oneMapAuthService, onemapCircuitBreaker, onemapBulkhead);
	}

	/**
	 * Builds a blocking RestClient with connect + read timeouts (SimpleClientHttpRequestFactory).
	 * Duplicated from {@code HaystackClientConfig.buildRestClient} (package-private there) rather
	 * than reused, so {@code client.onemap} stays self-contained.
	 */
	static RestClient buildRestClient(RestClient.Builder builder, String baseUrl, Duration connect, Duration read) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, connect.toMillis()));
		factory.setReadTimeout((int) Math.min(Integer.MAX_VALUE, read.toMillis()));
		return builder.baseUrl(baseUrl).requestFactory(factory).build();
	}
}
