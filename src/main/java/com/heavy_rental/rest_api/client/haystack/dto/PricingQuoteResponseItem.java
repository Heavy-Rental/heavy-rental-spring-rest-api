package com.heavy_rental.rest_api.client.haystack.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One result in {@link PricingQuoteResponse#results()}. {@code error} is set (and pricing
 * fields left null) when haystack could not resolve that item — a per-item failure that
 * MUST NOT fail the rest of the batch (US-4 "unresolvable asset_id" scenario).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingQuoteResponseItem(
		@JsonProperty("item_id") Long itemId,
		@JsonProperty("asset_id") Long assetId,
		@JsonProperty("daily_rate") BigDecimal dailyRate,
		@JsonProperty("total_price") BigDecimal totalPrice,
		@JsonProperty("was_clamped") boolean wasClamped,
		@JsonProperty("min_daily_rate") BigDecimal minDailyRate,
		@JsonProperty("max_daily_rate") BigDecimal maxDailyRate,
		@JsonProperty("model_version") String modelVersion,
		boolean degraded,
		String error) {

	public boolean isUsable() {
		return error == null && dailyRate != null && totalPrice != null;
	}
}
