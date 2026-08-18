package com.heavy_rental.rest_api.client.onemap.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response body for {@code GET /api/common/elastic/search} (postal code / address lookup).
 * {@code found == 0} means no match — a normal outcome, not an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OneMapSearchResponse(int found, List<OneMapSearchResult> results) {
}
