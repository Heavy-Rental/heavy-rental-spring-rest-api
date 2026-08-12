package com.heavy_rental.rest_api.client.haystack.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Call 2 quote line item from haystack. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendItemDto(
		Integer rankOrder,
		RecommendEquipmentDto equipment,
		BigDecimal baseDailyRate,
		BigDecimal lineTotal,
		BigDecimal matchScore) {
}
