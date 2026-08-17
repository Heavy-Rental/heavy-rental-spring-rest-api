package com.heavy_rental.rest_api.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.heavy_rental.rest_api.client.haystack.HaystackException;
import com.heavy_rental.rest_api.client.haystack.HaystackPricingClient;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteRequest;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteRequestItem;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteResponse;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteResponseItem;
import com.heavy_rental.rest_api.config.PricingProperties;
import com.heavy_rental.rest_api.entity.RentalPlan;
import com.heavy_rental.rest_api.entity.RentalPlanRecord;

/**
 * Prices a rental plan's line items via {@code haystack-fast-api}'s
 * {@code POST /internal/v1/pricing/quote} (see {@code openspec/changes/dynamic-plan-quote-pricing/}),
 * with a per-item fallback to {@link DefaultPricingClient} ({@code Asset.baseDailyRate}) so a
 * quote request is never blocked by the pricing service being unavailable or unable to resolve
 * a specific item. {@code distance_km} is resolved via {@link DistanceService}, which never throws
 * either — a geocoding failure there falls back to a constant, same "never block the quote"
 * philosophy (see {@code openspec/changes/pricing-postal-distance/}).
 * <p>
 * Deliberately independent of the recommender saga — only called from
 * {@link RentalPlanService#requestQuote}, never from cart-building ({@code addItem}).
 */
@Service
public class DynamicPricingService {

	private static final Logger log = LoggerFactory.getLogger(DynamicPricingService.class);

	private final HaystackPricingClient haystackPricingClient;
	private final DefaultPricingClient defaultPricingClient;
	private final DistanceService distanceService;
	private final PricingProperties pricingProperties;

	public DynamicPricingService(
			HaystackPricingClient haystackPricingClient,
			DefaultPricingClient defaultPricingClient,
			DistanceService distanceService,
			PricingProperties pricingProperties) {
		this.haystackPricingClient = haystackPricingClient;
		this.defaultPricingClient = defaultPricingClient;
		this.distanceService = distanceService;
		this.pricingProperties = pricingProperties;
	}

	/** Rollout flag for {@code RentalPlanService.requestQuote()} — see {@code PricingProperties}. */
	public boolean isEnabled() {
		return pricingProperties.dynamicEnabled();
	}

	/**
	 * Prices every item in {@code items} for {@code plan}'s dates, in the same order as
	 * {@code items}. Never throws for pricing-service failures — falls back per item.
	 * <p>
	 * {@code correlationId} is the inbound {@code X-Correlation-Id} from the portal quote request
	 * (may be {@code null}/blank); propagated to haystack when present, same convention as
	 * {@code RecommenderSagaService} — otherwise a fresh id is generated so the outbound call is
	 * still traceable.
	 */
	public List<PricingClient.ItemPrice> priceItems(RentalPlan plan, List<RentalPlanRecord> items,
			String correlationId) {
		Map<String, PricingQuoteResponseItem> results = fetchResults(plan, items, correlationId);

		return items.stream()
				.map(item -> {
					PricingQuoteResponseItem result = results.get(String.valueOf(item.getId()));
					if (result != null && result.isUsable()) {
						if (result.degraded()) {
							// haystack-fast-api dynamic-pricing spec: degraded means the primary data
							// snapshot was unavailable and it fell back to a secondary source — the
							// price is still model-computed and used as-is, just logged for ops
							// visibility into upstream data-source health (not a pricing failure).
							log.warn("Dynamic pricing degraded for plan {} item {} (model {}) — using price from secondary data source",
									plan.getId(), item.getId(), result.modelVersion());
						}
						return new PricingClient.ItemPrice(result.dailyRate(), result.totalPrice());
					}
					if (result != null && result.error() != null) {
						log.warn("Dynamic pricing unusable for plan {} item {}: {} — falling back to base rate",
								plan.getId(), item.getId(), result.error());
					}
					return defaultPricingClient.priceItem(item.getAsset(), plan.getStartDate(), plan.getEndDate());
				})
				.toList();
	}

	private Map<String, PricingQuoteResponseItem> fetchResults(RentalPlan plan, List<RentalPlanRecord> items,
			String correlationId) {
		List<PricingQuoteRequestItem> requestItems = items.stream()
				.map(item -> new PricingQuoteRequestItem(String.valueOf(item.getId()), item.getAsset().getId()))
				.toList();

		PricingQuoteRequest request = new PricingQuoteRequest(
				String.valueOf(plan.getId()),
				plan.getStartDate(),
				plan.getEndDate(),
				distanceService.resolveDistanceKm(plan),
				requestItems);

		String corr = (correlationId != null && !correlationId.isBlank())
				? correlationId
				: UUID.randomUUID().toString();

		try {
			PricingQuoteResponse response = haystackPricingClient.quote(request, corr);
			Map<String, PricingQuoteResponseItem> byItemId = new HashMap<>();
			if (response != null && response.results() != null) {
				response.results().forEach(result -> byItemId.put(result.itemId(), result));
			}
			return byItemId;
		} catch (HaystackException ex) {
			log.warn("Dynamic pricing unavailable for plan {} ({}: {}) — falling back to base rate for all items",
					plan.getId(), ex.getErrorCode(), ex.getMessage());
			return Map.of();
		}
	}
}
