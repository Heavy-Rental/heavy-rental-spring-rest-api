package com.heavy_rental.rest_api.client.onemap;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.heavy_rental.rest_api.client.onemap.dto.OneMapTokenRequest;
import com.heavy_rental.rest_api.client.onemap.dto.OneMapTokenResponse;

/**
 * Caches OneMap's bearer token (~3 day validity, {@code POST /api/auth/post/getToken}) and
 * refreshes it proactively — {@code onemap.token-refresh-buffer} before actual expiry — so
 * {@link OneMapClient} only pays a token round-trip the first time (or after a forced refresh),
 * not on every geocode call.
 * <p>
 * Deliberately outside {@link OneMapClient}'s circuit breaker / bulkhead: those decorate the
 * <em>whole</em> geocode operation (token fetch + search), so a token failure here already counts
 * as a geocode failure one layer up — this class only owns caching and the raw token HTTP call.
 */
public class OneMapAuthService {

	private static final String PATH_GET_TOKEN = "/api/auth/post/getToken";

	private final OneMapProperties properties;
	private final RestClient authClient;
	private final Clock clock;
	private final Object refreshLock = new Object();
	private volatile CachedToken cached;

	public OneMapAuthService(OneMapProperties properties, RestClient.Builder restClientBuilder) {
		this(properties, restClientBuilder, Clock.systemUTC());
	}

	/** Package-visible so tests can inject a fixed {@link Clock} for deterministic expiry checks. */
	OneMapAuthService(OneMapProperties properties, RestClient.Builder restClientBuilder, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		String base = trimTrailingSlash(properties.getBaseUrl());
		this.authClient = OneMapClientConfig.buildRestClient(
				restClientBuilder, base, properties.getTimeouts().getConnect(), properties.getTimeouts().getRead());
	}

	/** Returns a token guaranteed valid for at least {@code onemap.token-refresh-buffer} more time. */
	public String getValidToken() {
		CachedToken token = cached;
		if (isFresh(token)) {
			return token.accessToken();
		}
		synchronized (refreshLock) {
			token = cached;
			if (isFresh(token)) {
				return token.accessToken();
			}
			token = fetchToken();
			cached = token;
			return token.accessToken();
		}
	}

	private boolean isFresh(CachedToken token) {
		return token != null
				&& clock.instant().isBefore(token.expiresAt().minus(properties.getTokenRefreshBuffer()));
	}

	private CachedToken fetchToken() {
		try {
			OneMapTokenResponse resp = authClient.post()
					.uri(PATH_GET_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.body(new OneMapTokenRequest(properties.getEmail(), properties.getPassword()))
					.retrieve()
					.body(OneMapTokenResponse.class);
			if (resp == null || resp.accessToken() == null || resp.expiryTimestamp() == null) {
				throw new OneMapException(0, "onemap_auth_upstream_error",
						"OneMap token response was empty or missing fields", OneMapException.Kind.UPSTREAM);
			}
			Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(resp.expiryTimestamp()));
			return new CachedToken(resp.accessToken(), expiresAt);
		} catch (OneMapException already) {
			throw already;
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private OneMapException mapException(Exception ex) {
		if (ex instanceof RestClientResponseException rce) {
			int status = rce.getStatusCode().value();
			OneMapException.Kind kind = status >= 500 ? OneMapException.Kind.UPSTREAM : OneMapException.Kind.CLIENT;
			String body = rce.getResponseBodyAsString();
			String message = (body == null || body.isBlank()) ? rce.getStatusText() : body;
			return new OneMapException(status, "onemap_auth_error", message, kind, rce);
		}
		if (isTimeout(ex)) {
			return new OneMapException(504, "onemap_auth_timeout",
					"OneMap token request timed out: " + rootMessage(ex), OneMapException.Kind.TIMEOUT, ex);
		}
		return new OneMapException(0, "onemap_auth_transport_error",
				"OneMap token request failed: " + rootMessage(ex), OneMapException.Kind.TRANSPORT, ex);
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

	private record CachedToken(String accessToken, Instant expiresAt) {
	}
}
