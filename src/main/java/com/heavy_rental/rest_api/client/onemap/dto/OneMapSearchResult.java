package com.heavy_rental.rest_api.client.onemap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One match in a {@link OneMapSearchResponse}. Only the fields this app actually uses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OneMapSearchResult(
		@JsonProperty("POSTAL") String postal,
		@JsonProperty("ADDRESS") String address,
		@JsonProperty("LATITUDE") String latitude,
		@JsonProperty("LONGITUDE") String longitude) {
}
