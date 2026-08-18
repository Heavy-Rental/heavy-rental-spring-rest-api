package com.heavy_rental.rest_api.client.haystack.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Call 2 recommend/quote response. Primary portal submit body maps from this type.
 * Not Q&amp;A — chatbot {@code answer} is Call 3 only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetAssetRecommendationsResponse(
		@JsonProperty("user_id") String userId,
		@JsonProperty("ingest_id") String ingestId,
		String query,
		String quoteRef,
		BigDecimal confidenceScore,
		Integer days,
		BigDecimal estimatedTotal,
		String specSummary,
		String rationale,
		List<RecommendItemDto> items,
		List<String> warnings) {
}
