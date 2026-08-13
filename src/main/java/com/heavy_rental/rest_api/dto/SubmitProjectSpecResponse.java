package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Portal submit response: Call 1 session handles + Call 2 recommend quote.
 * <p>
 * Does <strong>not</strong> include chatbot {@code answer} (Call 3 / knowledge-query only).
 */
public record SubmitProjectSpecResponse(
		Long recommendationId,
		String ingestId,
		String userRequirementSummary,
		LocalDate tentativeStartDate,
		LocalDate tentativeEndDate,
		List<NeedSummaryResponse> needsSummary,
		ExpectedBudgetResponse expectedBudget,
		List<String> warnings,
		String correlationId,
		/** From haystack Call 2 {@code quoteRef}. */
		String quoteRef,
		BigDecimal confidenceScore,
		Integer days,
		BigDecimal estimatedTotal,
		String specSummary,
		String rationale,
		List<RecommendItemResponse> items) {
}
