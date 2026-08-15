package com.heavy_rental.rest_api.client.haystack.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request for {@code POST /internal/v1/pricing/quote} — rental-plan quote pricing
 * (see {@code haystack-fast-api} {@code openspec/specs/dynamic-pricing/}, US-4).
 * Distinct from and independent of the recommender saga (Call 1/2/3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PricingQuoteRequest(
		@JsonProperty("rental_plan_id") Long rentalPlanId,
		@JsonProperty("start_date") LocalDate startDate,
		@JsonProperty("end_date") LocalDate endDate,
		@JsonProperty("distance_km") double distanceKm,
		List<PricingQuoteRequestItem> items) {
}
