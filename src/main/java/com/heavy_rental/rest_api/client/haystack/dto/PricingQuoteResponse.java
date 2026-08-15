package com.heavy_rental.rest_api.client.haystack.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response for {@code POST /internal/v1/pricing/quote}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingQuoteResponse(
		@JsonProperty("rental_plan_id") Long rentalPlanId,
		String currency,
		@JsonProperty("deposit_rate") BigDecimal depositRate,
		boolean degraded,
		List<PricingQuoteResponseItem> results,
		List<String> warnings) {
}
