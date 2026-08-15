package com.heavy_rental.rest_api.client.haystack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One line item in a {@link PricingQuoteRequest} batch.
 * {@code item_id} is {@code str} in haystack's Pydantic schema ({@code app/schemas/pricing.py}
 * {@code QuoteItemRequest}) — not numeric, despite Spring's own {@code RentalPlanRecord.id}
 * being a {@code Long}. {@code asset_id} is the real numeric {@code int} PK.
 */
public record PricingQuoteRequestItem(
		@JsonProperty("item_id") String itemId,
		@JsonProperty("asset_id") Long assetId) {
}
