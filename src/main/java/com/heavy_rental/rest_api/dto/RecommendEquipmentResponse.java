package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Nested equipment on a Call 2 quote line (portal-facing).
 * Fields are pass-through from haystack; null when omitted upstream.
 */
public record RecommendEquipmentResponse(
		Object id,
		String name,
		String category,
		BigDecimal baseDailyRate,
		BigDecimal weekly,
		Integer capacity,
		Integer purchaseYear,
		String location,
		Boolean available,
		String img,
		String desc,
		List<String> tags) {
}
