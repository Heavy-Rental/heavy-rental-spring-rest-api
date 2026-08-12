package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** FastAPI error body shape {@code {"error","message"}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HaystackErrorBody(String error, String message) {
}
