package com.heavy_rental.rest_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rollout controls for FastAPI-backed dynamic pricing on the rental-plan quote step
 * (see {@code openspec/changes/dynamic-plan-quote-pricing/}).
 * <p>
 * {@code dynamicEnabled} gates {@code RentalPlanService.requestQuote()}'s use of
 * {@code DynamicPricingService}; when {@code false} (default), quote pricing is unchanged
 * Spring-only {@code DefaultPricingClient} arithmetic.
 */
@ConfigurationProperties(prefix = "pricing")
public record PricingProperties(
		@DefaultValue("false") boolean dynamicEnabled,
		@DefaultValue("20.0") double defaultDistanceKm) {
}
