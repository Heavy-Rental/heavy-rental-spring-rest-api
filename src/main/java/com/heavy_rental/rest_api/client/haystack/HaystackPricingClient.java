package com.heavy_rental.rest_api.client.haystack;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.ObjectMapper;
import com.heavy_rental.rest_api.client.haystack.dto.HaystackErrorBody;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteRequest;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteResponse;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

/**
 * Outbound HTTP client for haystack-fast-api's rental-plan quote pricing endpoint
 * (see {@code openspec/changes/dynamic-plan-quote-pricing/}).
 * <p>
 * Deliberately independent of {@link HaystackRecommenderClient}: this is the plain
 * "customer already picked dates + equipment" pricing path, not the project-spec
 * recommender saga (Call 1/2/3). Calling this client never triggers ingest or recommend.
 * <p>
 * Resilience is applied the same way as the recommender client: its own circuit breaker,
 * bulkhead, and retry, reusing {@link HaystackException} for consistent error typing.
 */
public class HaystackPricingClient {

	/** Rental-plan quote pricing — separate endpoint from the recommender saga. */
	public static final String PATH_QUOTE = "/internal/v1/pricing/quote";

	public static final String HEADER_CORRELATION_ID = HaystackRecommenderClient.HEADER_CORRELATION_ID;

	private final ObjectMapper objectMapper;
	private final CircuitBreaker circuitBreaker;
	private final Bulkhead bulkhead;
	private final Retry retry;
	private final RestClient quoteClient;

	public HaystackPricingClient(
			HaystackProperties properties,
			RestClient.Builder restClientBuilder,
			ObjectMapper objectMapper,
			CircuitBreaker circuitBreaker,
			Bulkhead bulkhead,
			Retry retry) {
		this.objectMapper = objectMapper;
		this.circuitBreaker = circuitBreaker;
		this.bulkhead = bulkhead;
		this.retry = retry;

		String base = trimTrailingSlash(properties.getBaseUrl());
		Duration connect = properties.getTimeouts().getConnect();
		this.quoteClient = HaystackClientConfig.buildRestClient(
				restClientBuilder, base, connect, properties.getTimeouts().getPricingRead());
	}

	/** {@code POST /internal/v1/pricing/quote} — batch per-item pricing for one rental plan. */
	public PricingQuoteResponse quote(PricingQuoteRequest body, String correlationId) {
		Supplier<PricingQuoteResponse> call = () -> doQuote(body, correlationId);
		Supplier<PricingQuoteResponse> withRetry = Retry.decorateSupplier(retry, call);
		return decorate(withRetry);
	}

	private PricingQuoteResponse doQuote(PricingQuoteRequest body, String correlationId) {
		try {
			return quoteClient.post()
					.uri(PATH_QUOTE)
					.contentType(MediaType.APPLICATION_JSON)
					.header(HEADER_CORRELATION_ID, correlationId)
					.body(body)
					.retrieve()
					.body(PricingQuoteResponse.class);
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private PricingQuoteResponse decorate(Supplier<PricingQuoteResponse> supplier) {
		Supplier<PricingQuoteResponse> bulkheaded = Bulkhead.decorateSupplier(bulkhead, supplier);
		Supplier<PricingQuoteResponse> withCb = CircuitBreaker.decorateSupplier(circuitBreaker, bulkheaded);
		try {
			return withCb.get();
		} catch (RuntimeException ex) {
			throw mapDecoratorException(ex);
		}
	}

	private RuntimeException mapDecoratorException(Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null && !(cause instanceof HaystackException)
				&& !(cause instanceof CallNotPermittedException)
				&& !(cause instanceof BulkheadFullException)) {
			cause = cause.getCause();
		}
		if (cause instanceof HaystackException he) {
			return he;
		}
		if (cause instanceof CallNotPermittedException) {
			return new HaystackException(503, "pricing_unavailable",
					"Pricing circuit breaker is open", HaystackException.Kind.UNAVAILABLE, cause);
		}
		if (cause instanceof BulkheadFullException) {
			return new HaystackException(503, "pricing_unavailable",
					"Pricing bulkhead is full", HaystackException.Kind.UNAVAILABLE, cause);
		}
		return mapException(cause instanceof Exception e ? e : new RuntimeException(cause));
	}

	HaystackException mapException(Exception ex) {
		if (ex instanceof HaystackException he) {
			return he;
		}
		if (ex instanceof RestClientResponseException rce) {
			return mapResponseException(rce);
		}
		if (isTimeout(ex)) {
			return new HaystackException(504, "pricing_timeout",
					"Haystack pricing call timed out: " + rootMessage(ex), HaystackException.Kind.TIMEOUT, ex);
		}
		return new HaystackException(0, "pricing_upstream_error",
				"Haystack pricing transport error: " + rootMessage(ex), HaystackException.Kind.TRANSPORT, ex);
	}

	private HaystackException mapResponseException(RestClientResponseException rce) {
		int status = rce.getStatusCode().value();
		String body = rce.getResponseBodyAsString();
		String code = status >= 500 ? "internal_error" : "bad_request";
		String message = rce.getStatusText();
		try {
			if (body != null && !body.isBlank()) {
				HaystackErrorBody err = objectMapper.readValue(body, HaystackErrorBody.class);
				if (err.error() != null && !err.error().isBlank()) {
					code = err.error();
				}
				if (err.message() != null && !err.message().isBlank()) {
					message = err.message();
				}
			}
		} catch (Exception ignored) {
			if (!body.isBlank()) {
				message = body;
			}
		}
		HaystackException.Kind kind = status >= 500 ? HaystackException.Kind.UPSTREAM : HaystackException.Kind.CLIENT;
		return new HaystackException(status, code, message, kind, rce);
	}

	private static boolean isTimeout(Throwable ex) {
		Throwable t = ex;
		while (t != null) {
			if (t instanceof SocketTimeoutException
					|| t instanceof HttpTimeoutException
					|| t instanceof TimeoutException
					|| (t.getMessage() != null && t.getMessage().toLowerCase().contains("timed out"))) {
				return true;
			}
			t = t.getCause();
		}
		return false;
	}

	private static String rootMessage(Throwable ex) {
		return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	private static String trimTrailingSlash(String url) {
		if (url == null || url.isBlank()) {
			return "http://localhost:8000";
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
