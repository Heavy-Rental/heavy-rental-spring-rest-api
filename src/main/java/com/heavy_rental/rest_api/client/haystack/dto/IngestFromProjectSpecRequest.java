package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Call 1 JSON request body for {@code POST .../submitprojectspecification}.
 * Wire fields are snake_case.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestFromProjectSpecRequest(
		@JsonProperty("user_id") String userId,
		@JsonProperty("user_name") String userName,
		@JsonProperty("project_text") String projectText,
		@JsonProperty("start_date") String startDate,
		@JsonProperty("end_date") String endDate) {
}
