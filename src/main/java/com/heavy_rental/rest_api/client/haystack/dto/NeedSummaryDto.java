package com.heavy_rental.rest_api.client.haystack.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Call 1 optional needs summary entry (display only; not ranked fleet). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NeedSummaryDto(
		@JsonProperty("need_id") String needId,
		String description,
		@JsonProperty("equipment_hints") List<String> equipmentHints,
		Integer quantity) {
}
