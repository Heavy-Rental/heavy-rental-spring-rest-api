package com.heavy_rental.rest_api.client.haystack.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Nested equipment reference on a Call 2 quote item.
 * {@code id} is the catalog asset id when known (number or string from haystack).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendEquipmentDto(
		Object id,
		String name,
		String category,
		BigDecimal baseDailyRate,
		BigDecimal weekly,
		Integer capacity,
		BigDecimal platformHeight,
		Integer purchaseYear,
		String location,
		Boolean available,
		String img,
		String desc,
		List<String> tags) {
}
