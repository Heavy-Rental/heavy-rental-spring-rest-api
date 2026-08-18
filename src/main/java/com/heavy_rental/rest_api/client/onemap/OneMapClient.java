package com.heavy_rental.rest_api.client.onemap;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.heavy_rental.rest_api.client.onemap.dto.OneMapSearchResponse;
import com.heavy_rental.rest_api.client.onemap.dto.OneMapSearchResult;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Geocodes a Singapore postal code to coordinates via OneMap's Search API
 * ({@code /api/common/elastic/search}), the Singapore government's geocoding service.
 * <p>
 * Search itself does not require authentication on OneMap's live API — verified directly against
 * the real endpoint (see {@code openspec/changes/pricing-postal-distance/design.md}) — but
 * unauthenticated calls degrade after only a handful of requests (an "Authentication token
 * missing" warning appended alongside otherwise-valid results), which is not viable at this app's
 * real call volume. Every call here is therefore made with a bearer token from
 * {@link OneMapAuthService}, and a token-fetch failure is treated as a geocode failure — both
 * count toward the same circuit breaker below.
 * <p>
 * {@code Optional.empty()} means OneMap responded with no match for the postal code — a normal,
 * cacheable outcome, distinct from {@link OneMapException} (OneMap itself is
 * unreachable/erroring/timing out/circuit-open), which is never cached so a transient outage
 * self-heals on the next call.
 * <p>
 * Deliberately no Resilience4j {@code Retry}: the business consequence of a failed geocode is
 * "fall back to a configurable constant" (see {@code DistanceService}), not "the caller's action
 * fails" — an in-call retry only adds latency to every cold-cache request for no correctness
 * benefit, since a transient failure already self-heals on the next call (failures aren't cached).
 * Deliberately one shared {@link Bulkhead}, not per-operation like haystack's four: there is only
 * one operation shape here, used by both {@code DistanceService} and the postal-code validation
 * endpoint.
 */
public class OneMapClient {

	private static final String PATH_SEARCH = "/api/common/elastic/search";

	private final OneMapAuthService authService;
	private final CircuitBreaker circuitBreaker;
	private final Bulkhead bulkhead;
	private final RestClient searchClient;
	private final ConcurrentHashMap<String, Optional<Coordinates>> cache = new ConcurrentHashMap<>();

	public OneMapClient(
			OneMapProperties properties,
			RestClient.Builder restClientBuilder,
			OneMapAuthService authService,
			CircuitBreaker circuitBreaker,
			Bulkhead bulkhead) {
		this.authService = authService;
		this.circuitBreaker = circuitBreaker;
		this.bulkhead = bulkhead;

		String base = trimTrailingSlash(properties.getBaseUrl());
		this.searchClient = OneMapClientConfig.buildRestClient(
				restClientBuilder, base, properties.getTimeouts().getConnect(), properties.getTimeouts().getRead());
	}

	/** Postal code (6 digits) to coordinates. {@code Optional.empty()} if OneMap has no match. */
	public Optional<Coordinates> geocode(String postalCode) {
		Optional<Coordinates> hit = cache.get(postalCode);
		if (hit != null) {
			return hit;
		}
		Optional<Coordinates> result = decorate(() -> doGeocode(postalCode));
		cache.put(postalCode, result);
		return result;
	}

	private Optional<Coordinates> doGeocode(String postalCode) {
		try {
			String token = authService.getValidToken();
			OneMapSearchResponse resp = searchClient.get()
					.uri(uriBuilder -> uriBuilder.path(PATH_SEARCH)
							.queryParam("searchVal", postalCode)
							.queryParam("returnGeom", "Y")
							.queryParam("getAddrDetails", "Y")
							.queryParam("pageNum", 1)
							.build())
					.header("Authorization", "Bearer " + token)
					.retrieve()
					.body(OneMapSearchResponse.class);

			if (resp == null || resp.found() <= 0 || resp.results() == null || resp.results().isEmpty()) {
				return Optional.empty();
			}
			OneMapSearchResult first = resp.results().get(0);
			return Optional.of(new Coordinates(
					Double.parseDouble(first.latitude()),
					Double.parseDouble(first.longitude()),
					first.address()));
		} catch (OneMapException already) {
			throw already;
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private Optional<Coordinates> decorate(Supplier<Optional<Coordinates>> supplier) {
		Supplier<Optional<Coordinates>> bulkheaded = Bulkhead.decorateSupplier(bulkhead, supplier);
		Supplier<Optional<Coordinates>> withCb = CircuitBreaker.decorateSupplier(circuitBreaker, bulkheaded);
		try {
			return withCb.get();
		} catch (RuntimeException ex) {
			throw mapDecoratorException(ex);
		}
	}

	private RuntimeException mapDecoratorException(Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null && !(cause instanceof OneMapException)
				&& !(cause instanceof CallNotPermittedException)
				&& !(cause instanceof BulkheadFullException)) {
			cause = cause.getCause();
		}
		if (cause instanceof OneMapException already) {
			return already;
		}
		if (cause instanceof CallNotPermittedException) {
			return new OneMapException(503, "onemap_unavailable",
					"OneMap circuit breaker is open", OneMapException.Kind.UNAVAILABLE, cause);
		}
		if (cause instanceof BulkheadFullException) {
			return new OneMapException(503, "onemap_unavailable",
					"OneMap bulkhead is full", OneMapException.Kind.UNAVAILABLE, cause);
		}
		return mapException(cause instanceof Exception e ? e : new RuntimeException(cause));
	}

	private OneMapException mapException(Exception ex) {
		if (ex instanceof OneMapException already) {
			return already;
		}
		if (ex instanceof RestClientResponseException rce) {
			int status = rce.getStatusCode().value();
			OneMapException.Kind kind = status >= 500 ? OneMapException.Kind.UPSTREAM : OneMapException.Kind.CLIENT;
			String body = rce.getResponseBodyAsString();
			String message = (body == null || body.isBlank()) ? rce.getStatusText() : body;
			return new OneMapException(status, "onemap_geocode_error", message, kind, rce);
		}
		if (isTimeout(ex)) {
			return new OneMapException(504, "onemap_geocode_timeout",
					"OneMap search call timed out: " + rootMessage(ex), OneMapException.Kind.TIMEOUT, ex);
		}
		return new OneMapException(0, "onemap_geocode_transport_error",
				"OneMap search transport error: " + rootMessage(ex), OneMapException.Kind.TRANSPORT, ex);
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
			return "https://www.onemap.gov.sg";
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
