package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Nested equipment on a Call 2 quote line (portal-facing).
 * Fields are pass-through from haystack; null when omitted upstream.
 * {@code platformHeight} is omitted from JSON when null.
 */
public record RecommendEquipmentResponse(
		Object id,
		String name,
		String category,
		BigDecimal baseDailyRate,
		BigDecimal weekly,
		Integer capacity,
		@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal platformHeight,
		Integer purchaseYear,
		String location,
		Boolean available,
		String img,
		String desc,
		List<String> tags) {
}
