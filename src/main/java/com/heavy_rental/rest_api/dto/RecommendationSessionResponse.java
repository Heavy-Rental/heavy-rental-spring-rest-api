package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Stored recommendation session (DB only — no live haystack call).
 * Returned by {@code GET /api/recommendations/{id}}.
 */
public record RecommendationSessionResponse(
		Long recommendationId,
		String ingestId,
		String userRequirementSummary,
		LocalDate tentativeStartDate,
		LocalDate tentativeEndDate,
		ExpectedBudgetResponse expectedBudget,
		List<String> warnings,
		String status,
		String correlationId,
		LocalDateTime createdAt) {
}
