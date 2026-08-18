package com.heavy_rental.rest_api.client.onemap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response body for {@code POST /api/auth/post/getToken}. {@code expiryTimestamp} is epoch seconds. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OneMapTokenResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("expiry_timestamp") String expiryTimestamp) {
}
