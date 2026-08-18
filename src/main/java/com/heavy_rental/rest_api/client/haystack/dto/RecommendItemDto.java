package com.heavy_rental.rest_api.client.haystack.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Call 2 quote line item from haystack. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendItemDto(
		Integer rankOrder,
		BigDecimal matchScore,
		String reason,
		BigDecimal lineTotal,
		Integer quantity,
		RecommendEquipmentDto equipment,
		/** Optional legacy field when rate is on the item instead of nested equipment. */
		BigDecimal baseDailyRate) {
}
