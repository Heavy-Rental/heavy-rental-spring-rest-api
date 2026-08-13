package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Call 2 recommend/quote request for
 * {@code POST .../project-knowledge/getassetrecommendations}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GetAssetRecommendationsRequest(
		@JsonProperty("user_id") String userId,
		@JsonProperty("ingest_id") String ingestId,
		String query,
		@JsonProperty("top_k") Integer topK) {
}
