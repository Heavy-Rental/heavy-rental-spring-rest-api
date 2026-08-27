package com.heavy_rental.rest_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rollout controls for FastAPI-backed dynamic pricing on the rental-plan quote step
 * (see {@code openspec/changes/dynamic-plan-quote-pricing/}).
 * <p>
 * {@code dynamicEnabled} gates {@code RentalPlanService.requestQuote()}'s use of
 * {@code DynamicPricingService}; when {@code false}, quote pricing is Spring-only
 * {@code DefaultPricingClient} arithmetic. As-built {@code application.properties} default
 * is {@code true} ({@code DYNAMIC_PRICING_ENABLED:true}).
 * <p>
 * {@code originPostalCode}/{@code distanceLookupEnabled} back {@code DistanceService} (see
 * {@code openspec/changes/pricing-postal-distance/}): {@code distance_km} sent to haystack is
 * resolved from a single fixed origin postal code and the plan's delivery postal code via OneMap
 * geocoding, falling back to {@code defaultDistanceKm} on any failure. {@code distanceLookupEnabled}
 * (default {@code true}) is an independent kill-switch — when {@code false}, the OneMap lookup is
 * skipped entirely and {@code defaultDistanceKm} is used, mirroring the existing
 * {@code haystack.retry.ingest-enabled} precedent for gating a new external-call path.
 */
@ConfigurationProperties(prefix = "pricing")
public record PricingProperties(
		@DefaultValue("true") boolean dynamicEnabled,
		@DefaultValue("20.0") double defaultDistanceKm,
		@DefaultValue("629462") String originPostalCode,
		@DefaultValue("true") boolean distanceLookupEnabled) {
}
