package com.heavy_rental.rest_api.client.haystack;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.ObjectMapper;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsRequest;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsResponse;
import com.heavy_rental.rest_api.client.haystack.dto.HealthResponse;
import com.heavy_rental.rest_api.client.haystack.dto.HaystackErrorBody;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecRequest;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecResponse;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryRequest;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryResponse;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

/**
 * Outbound HTTP client for haystack-fast-api (S2b).
 *
 * <ul>
 *   <li><b>Call 1</b> — {@link #ingest} (JSON) / {@link #ingestMultipart} — Idempotency-Key + correlation</li>
 *   <li><b>Call 2</b> — {@link #recommend} — quote; never re-ingests</li>
 *   <li><b>Call 3</b> — {@link #queryProjectKnowledge} — chatbot Q&amp;A</li>
 *   <li>{@link #health} — short timeout</li>
 * </ul>
 *
 * Resilience is applied programmatically (not AOP): shared CB, per-op bulkhead, limited retry.
 * Ingest retries (when enabled) <strong>must</strong> reuse the same {@code Idempotency-Key}.
 *
 * @see package-info
 */
public class HaystackRecommenderClient {

	public static final String PATH_HEALTH = "/health";
	public static final String PATH_INGEST = "/internal/v1/recommendations/submitprojectspecification";
	/** Call 2 — recommend / quote. */
	public static final String PATH_RECOMMEND =
			"/internal/v1/recommendations/project-knowledge/getassetrecommendations";
	/** Call 3 — chatbot Q&amp;A. */
	public static final String PATH_QUERY = "/internal/v1/recommendations/project-knowledge/query";

	public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
	public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

	private final HaystackProperties properties;
	private final RestClient.Builder restClientBuilder;
	private final ObjectMapper objectMapper;
	private final CircuitBreaker circuitBreaker;
	private final Bulkhead ingestBulkhead;
	private final Bulkhead recommendBulkhead;
	private final Bulkhead qaBulkhead;
	private final Retry ingestRetry;
	private final Retry recommendRetry;
	private final Retry qaRetry;

	private final RestClient healthClient;
	private final RestClient ingestClient;
	private final RestClient recommendClient;
	private final RestClient qaClient;

	public HaystackRecommenderClient(
			HaystackProperties properties,
			RestClient.Builder restClientBuilder,
			ObjectMapper objectMapper,
			CircuitBreaker circuitBreaker,
			Bulkhead ingestBulkhead,
			Bulkhead recommendBulkhead,
			Bulkhead qaBulkhead,
			Retry ingestRetry,
			Retry recommendRetry,
			Retry qaRetry) {
		this.properties = properties;
		this.restClientBuilder = restClientBuilder;
		this.objectMapper = objectMapper;
		this.circuitBreaker = circuitBreaker;
		this.ingestBulkhead = ingestBulkhead;
		this.recommendBulkhead = recommendBulkhead;
		this.qaBulkhead = qaBulkhead;
		this.ingestRetry = ingestRetry;
		this.recommendRetry = recommendRetry;
		this.qaRetry = qaRetry;

		String base = trimTrailingSlash(properties.getBaseUrl());
		Duration connect = properties.getTimeouts().getConnect();
		this.healthClient = HaystackClientConfig.buildRestClient(
				restClientBuilder, base, connect, properties.getTimeouts().getHealthRead());
		this.ingestClient = HaystackClientConfig.buildRestClient(
				restClientBuilder, base, connect, properties.getTimeouts().getIngestRead());
		this.recommendClient = HaystackClientConfig.buildRestClient(
				restClientBuilder, base, connect, properties.getTimeouts().getRecommendRead());
		this.qaClient = HaystackClientConfig.buildRestClient(
				restClientBuilder, base, connect, properties.getTimeouts().getQaRead());
	}

	/** GET /health with short timeout; sends {@code X-Correlation-Id}. */
	public HealthResponse health(String correlationId) {
		return decorate(qaBulkhead, circuitBreaker, () -> doHealth(correlationId));
	}

	/**
	 * Call 1 ingest (JSON). {@code idempotencyKey} is sent on every attempt and must not change across retries.
	 */
	public IngestFromProjectSpecResponse ingest(
			IngestFromProjectSpecRequest body,
			String idempotencyKey,
			String correlationId) {
		Supplier<IngestFromProjectSpecResponse> call = () -> doIngest(body, idempotencyKey, correlationId);
		Supplier<IngestFromProjectSpecResponse> withRetry = properties.getRetry().isIngestEnabled()
				? Retry.decorateSupplier(ingestRetry, call)
				: call;
		return decorate(ingestBulkhead, circuitBreaker, withRetry);
	}

	/**
	 * Call 1 ingest (multipart file and/or project_text). Same headers and resilience as JSON ingest.
	 */
	public IngestFromProjectSpecResponse ingestMultipart(
			IngestMultipartCommand cmd,
			String idempotencyKey,
			String correlationId) {
		if (cmd == null || (!cmd.hasFile() && !cmd.hasProjectText())) {
			throw new HaystackException(400, "bad_request",
					"multipart ingest requires file and/or project_text", HaystackException.Kind.CLIENT);
		}
		if (cmd.hasFile()) {
			long max = properties.getMaxInMemorySize() != null
					? properties.getMaxInMemorySize().toBytes()
					: DataSize.ofMegabytes(20).toBytes();
			if (cmd.fileBytes().length > max) {
				throw new HaystackException(413, "payload_too_large",
						"Project file exceeds haystack.max-in-memory-size ("
								+ properties.getMaxInMemorySize() + ")",
						HaystackException.Kind.CLIENT);
			}
		}
		Supplier<IngestFromProjectSpecResponse> call = () -> doIngestMultipart(cmd, idempotencyKey, correlationId);
		Supplier<IngestFromProjectSpecResponse> withRetry = properties.getRetry().isIngestEnabled()
				? Retry.decorateSupplier(ingestRetry, call)
				: call;
		return decorate(ingestBulkhead, circuitBreaker, withRetry);
	}

	/**
	 * Call 2 recommend / quote.
	 * Never triggers Call 1; saga must pass stored {@code user_id} + {@code ingest_id}.
	 */
	public GetAssetRecommendationsResponse recommend(
			GetAssetRecommendationsRequest body,
			String correlationId) {
		Supplier<GetAssetRecommendationsResponse> call = () -> doRecommend(body, correlationId);
		Supplier<GetAssetRecommendationsResponse> withRetry = Retry.decorateSupplier(recommendRetry, call);
		return decorate(recommendBulkhead, circuitBreaker, withRetry);
	}

	/**
	 * Call 3 chatbot Q&amp;A ({@code .../project-knowledge/query}).
	 */
	public ProjectKnowledgeQueryResponse queryProjectKnowledge(
			ProjectKnowledgeQueryRequest body,
			String correlationId) {
		Supplier<ProjectKnowledgeQueryResponse> call = () -> doQuery(body, correlationId);
		Supplier<ProjectKnowledgeQueryResponse> withRetry = Retry.decorateSupplier(qaRetry, call);
		return decorate(qaBulkhead, circuitBreaker, withRetry);
	}

	private HealthResponse doHealth(String correlationId) {
		try {
			return healthClient.get()
					.uri(PATH_HEALTH)
					.header(HEADER_CORRELATION_ID, correlationId)
					.retrieve()
					.body(HealthResponse.class);
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private IngestFromProjectSpecResponse doIngest(
			IngestFromProjectSpecRequest body,
			String idempotencyKey,
			String correlationId) {
		try {
			return ingestClient.post()
					.uri(PATH_INGEST)
					.contentType(MediaType.APPLICATION_JSON)
					.header(HEADER_IDEMPOTENCY_KEY, idempotencyKey)
					.header(HEADER_CORRELATION_ID, correlationId)
					.body(body)
					.retrieve()
					.body(IngestFromProjectSpecResponse.class);
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private IngestFromProjectSpecResponse doIngestMultipart(
			IngestMultipartCommand cmd,
			String idempotencyKey,
			String correlationId) {
		try {
			// MultiValueMap + Resource triggers servlet multipart encoding without reactive MultipartBodyBuilder
			MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
			multipart.add("user_id", cmd.userId() != null ? cmd.userId() : "");
			if (cmd.userName() != null && !cmd.userName().isBlank()) {
				multipart.add("user_name", cmd.userName());
			}
			if (cmd.hasProjectText()) {
				multipart.add("project_text", cmd.projectText().trim());
			}
			if (cmd.startDate() != null && !cmd.startDate().isBlank()) {
				multipart.add("start_date", cmd.startDate());
			}
			if (cmd.endDate() != null && !cmd.endDate().isBlank()) {
				multipart.add("end_date", cmd.endDate());
			}
			if (cmd.hasFile()) {
				String name = (cmd.fileName() != null && !cmd.fileName().isBlank())
						? cmd.fileName()
						: "project-spec.bin";
				ByteArrayResource resource = new ByteArrayResource(cmd.fileBytes()) {
					@Override
					public String getFilename() {
						return name;
					}
				};
				multipart.add("file", resource);
			}
			return ingestClient.post()
					.uri(PATH_INGEST)
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.header(HEADER_IDEMPOTENCY_KEY, idempotencyKey)
					.header(HEADER_CORRELATION_ID, correlationId)
					.body(multipart)
					.retrieve()
					.body(IngestFromProjectSpecResponse.class);
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private GetAssetRecommendationsResponse doRecommend(
			GetAssetRecommendationsRequest body,
			String correlationId) {
		try {
			return recommendClient.post()
					.uri(PATH_RECOMMEND)
					.contentType(MediaType.APPLICATION_JSON)
					.header(HEADER_CORRELATION_ID, correlationId)
					.body(body)
					.retrieve()
					.body(GetAssetRecommendationsResponse.class);
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private ProjectKnowledgeQueryResponse doQuery(ProjectKnowledgeQueryRequest body, String correlationId) {
		try {
			return qaClient.post()
					.uri(PATH_QUERY)
					.contentType(MediaType.APPLICATION_JSON)
					.header(HEADER_CORRELATION_ID, correlationId)
					.body(body)
					.retrieve()
					.body(ProjectKnowledgeQueryResponse.class);
		} catch (Exception ex) {
			throw mapException(ex);
		}
	}

	private <T> T decorate(Bulkhead bulkhead, CircuitBreaker cb, Supplier<T> supplier) {
		Supplier<T> bulkheaded = Bulkhead.decorateSupplier(bulkhead, supplier);
		Supplier<T> withCb = CircuitBreaker.decorateSupplier(cb, bulkheaded);
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
			return new HaystackException(503, "recommender_unavailable",
					"Recommender circuit breaker is open", HaystackException.Kind.UNAVAILABLE, cause);
		}
		if (cause instanceof BulkheadFullException) {
			return new HaystackException(503, "recommender_unavailable",
					"Recommender bulkhead is full", HaystackException.Kind.UNAVAILABLE, cause);
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
			return new HaystackException(504, "recommender_timeout",
					"Haystack call timed out: " + rootMessage(ex), HaystackException.Kind.TIMEOUT, ex);
		}
		if (ex instanceof RestClientException) {
			return new HaystackException(0, "recommender_upstream_error",
					"Haystack transport error: " + rootMessage(ex), HaystackException.Kind.TRANSPORT, ex);
		}
		return new HaystackException(0, "recommender_upstream_error",
				rootMessage(ex), HaystackException.Kind.TRANSPORT, ex);
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
			if (body != null && !body.isBlank()) {
				message = body;
			}
		}
		HaystackException.Kind kind = status >= 500
				? HaystackException.Kind.UPSTREAM
				: HaystackException.Kind.CLIENT;
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

	/** Test support: rebuild clients against a new base URL (WireMock). */
	public HaystackRecommenderClient withBaseUrl(String baseUrl) {
		HaystackProperties copy = new HaystackProperties();
		copy.setBaseUrl(baseUrl);
		copy.setTimeouts(properties.getTimeouts());
		copy.setRetry(properties.getRetry());
		copy.setResilience(properties.getResilience());
		copy.setMaxInMemorySize(properties.getMaxInMemorySize());
		return new HaystackRecommenderClient(
				copy, restClientBuilder, objectMapper, circuitBreaker,
				ingestBulkhead, recommendBulkhead, qaBulkhead,
				ingestRetry, recommendRetry, qaRetry);
	}
}
