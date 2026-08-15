package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One line item in a {@link PricingQuoteRequest} batch. */
public record PricingQuoteRequestItem(
		@JsonProperty("item_id") Long itemId,
		@JsonProperty("asset_id") Long assetId) {
}
