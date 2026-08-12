package com.heavy_rental.rest_api.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.heavy_rental.rest_api.dto.ProjectKnowledgeQueryPortalRequest;
import com.heavy_rental.rest_api.dto.ProjectKnowledgeQueryPortalResponse;
import com.heavy_rental.rest_api.dto.ProjectSpecSubmitCommand;
import com.heavy_rental.rest_api.dto.RecommendationSessionResponse;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecRequest;
import com.heavy_rental.rest_api.dto.SubmitProjectSpecResponse;
import com.heavy_rental.rest_api.service.RecommenderSagaService;

/**
 * Thin portal REST for the S2b recommender journey.
 * <p>
 * Controllers must not call haystack directly — all orchestration is in
 * {@link RecommenderSagaService}. Auth: access JWT ({@code ROLE_USER} / {@code ROLE_ADMIN}).
 *
 * <ul>
 *   <li>{@code POST /project-spec} — Call 1 + Call 2 quote (JSON or multipart)</li>
 *   <li>{@code POST /{id}/knowledge-query} — Call 3 chatbot</li>
 *   <li>{@code GET /{id}} — DB session only</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

	private final RecommenderSagaService recommenderSagaService;

	public RecommendationController(RecommenderSagaService recommenderSagaService) {
		this.recommenderSagaService = recommenderSagaService;
	}

	/**
	 * {@code POST /api/recommendations/project-spec} (JSON) — portal project-spec submit.
	 * <p>
	 * Orchestrates haystack Call 1 (ingest) then Call 2 (recommend/quote). Returns session
	 * handles plus Call 2 quote fields ({@code quoteRef}, {@code items}, …). Optional inbound
	 * {@code X-Correlation-Id} is propagated to haystack; JWT principal supplies user identity.
	 */
	@PostMapping(value = "/project-spec", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<SubmitProjectSpecResponse> submitProjectSpecJson(
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
			@RequestBody SubmitProjectSpecRequest request) {
		return ResponseEntity.ok(recommenderSagaService.submitProjectSpec(jwt, request, correlationId));
	}

	/**
	 * {@code POST /api/recommendations/project-spec} (multipart) — file and/or projectText.
	 * <p>
	 * Same saga as JSON (Call 1 then Call 2 quote). At least one of {@code file} or
	 * {@code projectText} is required. Form field names are camelCase for the portal.
	 */
	@PostMapping(value = "/project-spec", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<SubmitProjectSpecResponse> submitProjectSpecMultipart(
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
			@RequestPart(value = "file", required = false) MultipartFile file,
			@RequestParam(value = "projectText", required = false) String projectText,
			@RequestParam(value = "startDate", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(value = "endDate", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(value = "userName", required = false) String userName,
			@RequestParam(value = "query", required = false) String query,
			@RequestParam(value = "topK", required = false) Integer topK) {
		byte[] bytes = null;
		String fileName = null;
		String contentType = null;
		if (file != null && !file.isEmpty()) {
			try {
				bytes = file.getBytes();
			} catch (Exception ex) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
			}
			fileName = file.getOriginalFilename();
			contentType = file.getContentType();
		}
		ProjectSpecSubmitCommand cmd = new ProjectSpecSubmitCommand(
				projectText,
				startDate,
				endDate,
				userName,
				query,
				topK,
				bytes,
				fileName,
				contentType);
		return ResponseEntity.ok(recommenderSagaService.submitProjectSpec(jwt, cmd, correlationId));
	}

	/**
	 * {@code POST /api/recommendations/{recommendationId}/knowledge-query} — follow-up chatbot Q&amp;A.
	 * <p>
	 * Loads the stored recommendation session, checks ownership (or admin) from the JWT, and
	 * invokes haystack Call 3 ({@code .../project-knowledge/query}) with the session's
	 * {@code ingest_id}. Body carries free-form {@code query} and optional {@code topK}.
	 * Returns HTTP 200 with chatbot {@code answer} / {@code sourcesUsed}. Does not re-ingest
	 * and does not run Call 2 recommend.
	 */
	@PostMapping("/{recommendationId}/knowledge-query")
	public ResponseEntity<ProjectKnowledgeQueryPortalResponse> knowledgeQuery(
			@AuthenticationPrincipal Jwt jwt,
			@PathVariable Long recommendationId,
			@RequestBody ProjectKnowledgeQueryPortalRequest request) {
		return ResponseEntity.ok(recommenderSagaService.queryKnowledge(jwt, recommendationId, request));
	}

	/**
	 * {@code GET /api/recommendations/{recommendationId}} — load a stored recommendation session.
	 * <p>
	 * Read-only DB fetch of the {@code AIRecommendation} row for the given Spring PK.
	 * JWT principal is used for owner-or-admin authorization. Returns session summary
	 * ({@code ingestId}, summary, dates, budget, warnings, status, {@code correlationId}, …).
	 * Does not call haystack.
	 */
	@GetMapping("/{recommendationId}")
	public ResponseEntity<RecommendationSessionResponse> getSession(
			@AuthenticationPrincipal Jwt jwt,
			@PathVariable Long recommendationId) {
		return ResponseEntity.ok(recommenderSagaService.getSession(jwt, recommendationId));
	}
}
