package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

/**
 * Portal-facing ranked quote line from Call 2 recommend (mapped from haystack items).
 * Not written to {@code recommendation_items} in S2b.
 */
public record RecommendItemResponse(
		Integer rankOrder,
		String equipmentId,
		String equipmentName,
		String category,
		BigDecimal baseDailyRate,
		BigDecimal lineTotal,
		BigDecimal matchScore) {
}
