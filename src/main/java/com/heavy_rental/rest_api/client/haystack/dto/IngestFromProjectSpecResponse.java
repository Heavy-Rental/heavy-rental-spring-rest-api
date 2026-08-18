package com.heavy_rental.rest_api.client.haystack.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Call 1 lean success body (FR-IX-023). Persist {@code ingest_id} and {@code user_id} for Call 2/3.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestFromProjectSpecResponse(
		@JsonProperty("ingest_id") String ingestId,
		@JsonProperty("user_id") String userId,
		@JsonProperty("user_requirement_summary") String userRequirementSummary,
		@JsonProperty("tentative_start_date") String tentativeStartDate,
		@JsonProperty("tentative_end_date") String tentativeEndDate,
		@JsonProperty("needs_summary") List<NeedSummaryDto> needsSummary,
		@JsonProperty("expected_budget") ExpectedBudgetDto expectedBudget,
		List<String> warnings) {
}
