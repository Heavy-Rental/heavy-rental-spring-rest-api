package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.client.haystack.HaystackException;
import com.heavy_rental.rest_api.client.haystack.HaystackRecommenderClient;
import com.heavy_rental.rest_api.client.haystack.IngestMultipartCommand;
import com.heavy_rental.rest_api.client.haystack.dto.ExpectedBudgetDto;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsRequest;
import com.heavy_rental.rest_api.client.haystack.dto.GetAssetRecommendationsResponse;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecRequest;
import com.heavy_rental.rest_api.client.haystack.dto.IngestFromProjectSpecResponse;
import com.heavy_rental.rest_api.client.haystack.dto.NeedSummaryDto;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryRequest;
import com.heavy_rental.rest_api.client.haystack.dto.ProjectKnowledgeQueryResponse;
import com.heavy_rental.rest_api.client.haystack.dto.RecommendEquipmentDto;
import com.heavy_rental.rest_api.client.haystack.dto.RecommendItemDto;
import com.heavy_rental.rest_api.dto.ExpectedBudgetResponse;
import com.heavy_rental.rest_api.dto.NeedSummaryResponse;
import com.heavy_rental.rest_api.dto.ProjectKnowledgeQueryPortalRequest;
import com.heavy_rental.rest_api.dto.ProjectKnowledgeQueryPortalResponse;
import com.heavy_rental.rest_api.dto.ProjectSpecSubmitCommand;
import com.heavy_rental.rest_api.dto.RecommendEquipmentResponse;
import com.heavy_rental.rest_api.dto.RecommendItemResponse;
import com.heavy_rental.rest_api.dto.RecommendationSessionResponse;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecRequest;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecResponse;
import com.heavy_rental.rest_api.entity.AIRecommendation;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.AIRecommendationRepository;
import com.heavy_rental.rest_api.repository.AssetImageRepository;

/**
 * Application saga for the portal recommender journey (S2b).
 *
 * <h2>Portal submit</h2>
 * {@link #submitProjectSpec} runs <strong>Call 1 then Call 2</strong>:
 * <ol>
 *   <li>Mint one {@code Idempotency-Key} and correlation id</li>
 *   <li>Ingest JSON or multipart (Call 1); persist {@code ingest_id} on {@link AIRecommendation}</li>
 *   <li>Recommend/quote (Call 2); map {@code quoteRef}/{@code items} to the portal response</li>
 * </ol>
 * If Call 2 fails after a successful Call 1: <strong>do not re-ingest</strong>; session row is kept.
 *
 * <h2>Follow-up Q&amp;A</h2>
 * {@link #queryKnowledge} is <strong>Call 3 only</strong> ({@code .../project-knowledge/query}).
 *
 * <h2>Hard rules</h2>
 * Never invent equipment on failure; never trust client-supplied haystack {@code user_id}
 * (derived from JWT → {@link CurrentUserService}).
 *
 * <p>
 * <b>TDD:</b> behaviour is guarded by {@code RecommenderSagaServiceTest},
 * {@code RecommenderSagaWireMockTest}, and portal MockMvc IT — change scenarios there first.
 *
 * @see com.heavy_rental.rest_api.controller.RecommendationController
 * @see com.heavy_rental.rest_api.client.haystack.HaystackRecommenderClient
 */
@Service
public class RecommenderSagaService {

	/** Used when portal does not send {@code query} and Call 1 has no summary. */
	public static final String DEFAULT_ASSET_QUERY =
			"Summarize equipment needs and recommend suitable assets for this project specification.";

	private static final String JPEG_DATA_URI_PREFIX = "data:image/jpeg;base64,";

	private final HaystackRecommenderClient haystackClient;
	private final AIRecommendationRepository recommendationRepository;
	private final CurrentUserService currentUserService;
	private final AssetImageRepository assetImageRepository;

	public RecommenderSagaService(
			HaystackRecommenderClient haystackClient,
			AIRecommendationRepository recommendationRepository,
			CurrentUserService currentUserService,
			AssetImageRepository assetImageRepository) {
		this.haystackClient = haystackClient;
		this.recommendationRepository = recommendationRepository;
		this.currentUserService = currentUserService;
		this.assetImageRepository = assetImageRepository;
	}

	/**
	 * JSON portal submit (backward compatible).
	 */
	@Transactional
	public SubmitProjectSpecResponse submitProjectSpec(
			Jwt jwt, SubmitProjectSpecRequest request, String correlationId) {
		return submitProjectSpec(jwt, ProjectSpecSubmitCommand.fromJson(request), correlationId);
	}

	/**
	 * JSON or multipart project-spec submit → Call 1 then Call 2 quote.
	 */
	@Transactional
	public SubmitProjectSpecResponse submitProjectSpec(
			Jwt jwt, ProjectSpecSubmitCommand cmd, String correlationId) {
		User user = currentUserService.getUser(jwt);
		if (cmd == null || (!cmd.hasProjectText() && !cmd.hasFile())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"projectText or file is required");
		}

		String haystackUserId = String.valueOf(user.getId());
		String idempotencyKey = UUID.randomUUID().toString();
		String corr = (correlationId != null && !correlationId.isBlank())
				? correlationId
				: UUID.randomUUID().toString();
		String userName = cmd.userName() != null ? cmd.userName() : user.getName();
		String start = cmd.startDate() != null ? cmd.startDate().toString() : null;
		String end = cmd.endDate() != null ? cmd.endDate().toString() : null;

		IngestFromProjectSpecResponse ingest;
		try {
			if (cmd.hasFile()) {
				ingest = haystackClient.ingestMultipart(
						new IngestMultipartCommand(
								haystackUserId,
								userName,
								cmd.hasProjectText() ? cmd.projectText().trim() : null,
								start,
								end,
								cmd.fileBytes(),
								cmd.fileName(),
								cmd.fileContentType()),
						idempotencyKey,
						corr);
			} else {
				ingest = haystackClient.ingest(
						new IngestFromProjectSpecRequest(
								haystackUserId,
								userName,
								cmd.projectText().trim(),
								start,
								end),
						idempotencyKey,
						corr);
			}
		} catch (HaystackException ex) {
			throw mapHaystack(ex);
		}

		if (ingest == null || ingest.ingestId() == null || ingest.ingestId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Haystack ingest returned no ingest_id");
		}

		String rawPrompt = cmd.hasProjectText()
				? cmd.projectText().trim()
				: ("[uploaded file: "
						+ (cmd.fileName() != null ? cmd.fileName() : "project-spec")
						+ "]");

		AIRecommendation entity = new AIRecommendation();
		entity.setUser(user);
		entity.setStatus(AIRecommendation.RecommendationStatus.GENERATED);
		entity.setCreatedAt(LocalDateTime.now());
		entity.setRawProjectPrompt(rawPrompt);
		entity.setAiReasoningSummary(ingest.userRequirementSummary());
		entity.setIngestId(ingest.ingestId());
		entity.setHaystackUserId(ingest.userId() != null ? ingest.userId() : haystackUserId);
		entity.setIdempotencyKey(idempotencyKey);
		entity.setCorrelationId(corr);
		entity.setTentativeStartDate(parseDate(ingest.tentativeStartDate(), cmd.startDate()));
		entity.setTentativeEndDate(parseDate(ingest.tentativeEndDate(), cmd.endDate()));
		if (ingest.expectedBudget() != null) {
			entity.setExpectedBudgetAmount(ingest.expectedBudget().amount());
			entity.setExpectedBudgetCurrency(ingest.expectedBudget().currency());
			entity.setExpectedBudgetSource(ingest.expectedBudget().source());
		}
		if (ingest.warnings() != null && !ingest.warnings().isEmpty()) {
			entity.setWarnings(String.join("\n", ingest.warnings()));
		}

		AIRecommendation saved = recommendationRepository.save(entity);

		// Call 2 — getassetrecommendations. Never re-ingest on failure.
		String call2UserId = saved.getHaystackUserId();
		String call2Query = resolveCall2Query(cmd.query(), ingest.userRequirementSummary());
		GetAssetRecommendationsRequest recommendBody = new GetAssetRecommendationsRequest(
				call2UserId,
				saved.getIngestId(),
				call2Query,
				cmd.topK());

		GetAssetRecommendationsResponse quote;
		try {
			quote = haystackClient.recommend(recommendBody, corr);
		} catch (HaystackException ex) {
			throw mapHaystack(ex);
		}

		if (quote != null && quote.confidenceScore() != null) {
			saved.setConfidenceScore(quote.confidenceScore());
			recommendationRepository.save(saved);
		}

		return new SubmitProjectSpecResponse(
				saved.getId(),
				saved.getIngestId(),
				ingest.userRequirementSummary(),
				saved.getTentativeStartDate(),
				saved.getTentativeEndDate(),
				mapNeeds(ingest.needsSummary()),
				mapBudget(ingest.expectedBudget()),
				mergeWarnings(ingest.warnings(), quote != null ? quote.warnings() : null),
				corr,
				quote != null ? quote.quoteRef() : null,
				quote != null ? quote.confidenceScore() : null,
				quote != null ? quote.days() : null,
				quote != null ? quote.estimatedTotal() : null,
				quote != null ? quote.specSummary() : null,
				quote != null ? quote.rationale() : null,
				mapItems(quote != null ? quote.items() : null));
	}

	/**
	 * Follow-up Call 3 chatbot Q&amp;A only — loads stored handles; never re-ingests.
	 */
	@Transactional(readOnly = true)
	public ProjectKnowledgeQueryPortalResponse queryKnowledge(
			Jwt jwt,
			Long recommendationId,
			ProjectKnowledgeQueryPortalRequest request) {
		if (request == null || request.query() == null || request.query().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
		}

		AIRecommendation session = recommendationRepository.findById(recommendationId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommendation not found"));
		currentUserService.assertOwnerOrAdmin(jwt, session.getUser());

		if (session.getIngestId() == null || session.getIngestId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recommendation has no ingest_id");
		}

		String haystackUserId = session.getHaystackUserId() != null
				? session.getHaystackUserId()
				: String.valueOf(session.getUser().getId());
		String corr = session.getCorrelationId() != null
				? session.getCorrelationId()
				: UUID.randomUUID().toString();

		ProjectKnowledgeQueryRequest body = new ProjectKnowledgeQueryRequest(
				haystackUserId,
				session.getIngestId(),
				request.query().trim(),
				request.topK(),
				null);

		try {
			ProjectKnowledgeQueryResponse resp = haystackClient.queryProjectKnowledge(body, corr);
			return new ProjectKnowledgeQueryPortalResponse(
					resp != null ? resp.answer() : null,
					resp != null && resp.sourcesUsed() != null ? resp.sourcesUsed() : List.of());
		} catch (HaystackException ex) {
			throw mapHaystack(ex);
		}
	}

	@Transactional(readOnly = true)
	public RecommendationSessionResponse getSession(Jwt jwt, Long recommendationId) {
		AIRecommendation session = recommendationRepository.findById(recommendationId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommendation not found"));
		currentUserService.assertOwnerOrAdmin(jwt, session.getUser());
		return toSessionResponse(session);
	}

	private static RecommendationSessionResponse toSessionResponse(AIRecommendation s) {
		ExpectedBudgetResponse budget = null;
		if (s.getExpectedBudgetAmount() != null) {
			budget = new ExpectedBudgetResponse(
					s.getExpectedBudgetAmount(),
					s.getExpectedBudgetCurrency(),
					s.getExpectedBudgetSource());
		}
		List<String> warnings = s.getWarnings() == null || s.getWarnings().isBlank()
				? List.of()
				: Arrays.asList(s.getWarnings().split("\\n"));
		return new RecommendationSessionResponse(
				s.getId(),
				s.getIngestId(),
				s.getAiReasoningSummary(),
				s.getTentativeStartDate(),
				s.getTentativeEndDate(),
				budget,
				warnings,
				s.getStatus() != null ? s.getStatus().name() : null,
				s.getCorrelationId(),
				s.getCreatedAt());
	}

	private static List<NeedSummaryResponse> mapNeeds(List<NeedSummaryDto> needs) {
		if (needs == null || needs.isEmpty()) {
			return List.of();
		}
		return needs.stream()
				.map(n -> new NeedSummaryResponse(
						n.needId(),
						n.description(),
						n.equipmentHints() != null ? n.equipmentHints() : List.of(),
						n.quantity()))
				.collect(Collectors.toList());
	}

	private static ExpectedBudgetResponse mapBudget(ExpectedBudgetDto b) {
		if (b == null) {
			return null;
		}
		return new ExpectedBudgetResponse(b.amount(), b.currency(), b.source());
	}

	private List<RecommendItemResponse> mapItems(List<RecommendItemDto> items) {
		if (items == null || items.isEmpty()) {
			return List.of();
		}
		List<Long> catalogIds = items.stream()
				.map(i -> i.equipment() != null ? catalogAssetId(i.equipment().id()) : null)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		Map<Long, String> imgByAssetId = loadCatalogImages(catalogIds);
		return items.stream()
				.map(i -> new RecommendItemResponse(
						i.rankOrder(),
						i.matchScore(),
						i.reason(),
						i.lineTotal(),
						i.quantity(),
						mapEquipment(i.equipment(), i.baseDailyRate(), imgByAssetId)))
				.collect(Collectors.toList());
	}

	/**
	 * Nested equipment for portal quote lines. Catalog fields are pass-through — never invent.
	 * {@code img} is the catalog JPEG data URI when {@code id} matches {@code asset_images};
	 * otherwise haystack {@code img} is kept. If haystack puts {@code baseDailyRate} on the
	 * item, copy it onto equipment when missing.
	 */
	private static RecommendEquipmentResponse mapEquipment(
			RecommendEquipmentDto e,
			BigDecimal itemBaseDailyRate,
			Map<Long, String> imgByAssetId) {
		if (e == null) {
			return null;
		}
		Object id = normalizeEquipmentId(e.id());
		BigDecimal baseDailyRate = e.baseDailyRate() != null ? e.baseDailyRate() : itemBaseDailyRate;
		List<String> tags = e.tags() != null ? e.tags() : List.of();
		String img = e.img();
		if (id instanceof Long catalogId) {
			String catalogImg = imgByAssetId.get(catalogId);
			if (catalogImg != null) {
				img = catalogImg;
			}
		}
		return new RecommendEquipmentResponse(
				id,
				e.name(),
				e.category(),
				baseDailyRate,
				e.weekly(),
				e.capacity(),
				e.platformHeight(),
				e.purchaseYear(),
				e.location(),
				e.available(),
				img,
				e.desc(),
				tags);
	}

	private Map<Long, String> loadCatalogImages(List<Long> catalogIds) {
		if (catalogIds.isEmpty()) {
			return Map.of();
		}
		return assetImageRepository.findByAssetIdIn(catalogIds).stream()
				.filter(image -> image.getAsset() != null
						&& image.getAsset().getId() != null
						&& image.getImage() != null
						&& !image.getImage().isBlank())
				.collect(Collectors.toMap(
						image -> image.getAsset().getId(),
						image -> JPEG_DATA_URI_PREFIX + image.getImage(),
						(first, second) -> first));
	}

	/** Numeric catalog asset id, or {@code null} when haystack id is not a catalog PK. */
	private static Long catalogAssetId(Object id) {
		Object normalized = normalizeEquipmentId(id);
		return normalized instanceof Long catalogId ? catalogId : null;
	}

	/** Prefer numeric catalog ids as {@link Long}; keep non-numeric strings as-is. */
	private static Object normalizeEquipmentId(Object id) {
		if (id == null) {
			return null;
		}
		if (id instanceof Number n) {
			return n.longValue();
		}
		if (id instanceof String s) {
			String trimmed = s.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			try {
				return Long.parseLong(trimmed);
			} catch (NumberFormatException ignored) {
				return trimmed;
			}
		}
		return id;
	}

	private static List<String> mergeWarnings(List<String> call1, List<String> call2) {
		List<String> out = new ArrayList<>();
		if (call1 != null) {
			out.addAll(call1);
		}
		if (call2 != null) {
			out.addAll(call2);
		}
		return out;
	}

	private static LocalDate parseDate(String iso, LocalDate fallback) {
		if (iso != null && !iso.isBlank()) {
			try {
				return LocalDate.parse(iso);
			} catch (Exception ignored) {
				// fall through
			}
		}
		return fallback;
	}

	/**
	 * Optional Call 2 focus query: portal override → Call 1 summary → fixed default.
	 */
	static String resolveCall2Query(String portalQuery, String userRequirementSummary) {
		if (portalQuery != null && !portalQuery.isBlank()) {
			return portalQuery.trim();
		}
		if (userRequirementSummary != null && !userRequirementSummary.isBlank()) {
			return userRequirementSummary.trim();
		}
		return DEFAULT_ASSET_QUERY;
	}

	public static ResponseStatusException mapHaystack(HaystackException ex) {
		return switch (ex.getKind()) {
			case CLIENT -> {
				HttpStatus status = switch (ex.getStatus()) {
					case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
					case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
					default -> HttpStatus.BAD_REQUEST;
				};
				yield new ResponseStatusException(status, ex.getMessage());
			}
			case TIMEOUT -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage());
			case UNAVAILABLE -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
			case UPSTREAM, TRANSPORT -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
		};
	}
}
