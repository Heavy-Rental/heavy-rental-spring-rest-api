package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Haystack {@code GET /health} body. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthResponse(String status, String database) {
}
