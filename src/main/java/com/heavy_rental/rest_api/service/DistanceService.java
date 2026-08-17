package com.heavy_rental.rest_api.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.heavy_rental.rest_api.client.onemap.Coordinates;
import com.heavy_rental.rest_api.client.onemap.OneMapClient;
import com.heavy_rental.rest_api.client.onemap.OneMapException;
import com.heavy_rental.rest_api.config.PricingProperties;
import com.heavy_rental.rest_api.entity.RentalPlan;
import com.heavy_rental.rest_api.util.PostalCodeUtil;

/**
 * Resolves {@code distance_km} for {@link DynamicPricingService} — the straight-line (haversine)
 * distance between a fixed origin postal code ({@code pricing.origin-postal-code}) and a rental
 * plan's delivery postal code, both geocoded via {@link OneMapClient}
 * (see {@code openspec/changes/pricing-postal-distance/}).
 * <p>
 * Never throws for its own failure domain — mirrors {@code DynamicPricingService}'s existing
 * fallback-to-{@code DefaultPricingClient} philosophy. Every OneMap failure mode (disabled via
 * {@code pricing.distance-lookup-enabled}, missing/malformed delivery postal code, OneMap having
 * no match for either postal code, or an {@link OneMapException} of any kind) falls back to
 * {@code pricing.default-distance-km} instead, logged at {@code WARN} for ops visibility.
 */
@Service
public class DistanceService {

	private static final Logger log = LoggerFactory.getLogger(DistanceService.class);

	/** Mean Earth radius in km (WGS84 mean radius), used for the haversine calculation. */
	private static final double EARTH_RADIUS_KM = 6371.0088;

	private final OneMapClient oneMapClient;
	private final PricingProperties pricingProperties;

	public DistanceService(OneMapClient oneMapClient, PricingProperties pricingProperties) {
		this.oneMapClient = oneMapClient;
		this.pricingProperties = pricingProperties;
	}

	/** Always succeeds — see class javadoc for the full fallback list. */
	public double resolveDistanceKm(RentalPlan plan) {
		if (!pricingProperties.distanceLookupEnabled()) {
			return pricingProperties.defaultDistanceKm();
		}

		String destinationPostalCode = plan.getSitePostalCode();
		if (!PostalCodeUtil.isWellFormed(destinationPostalCode)) {
			log.warn("Plan {} has no usable sitePostalCode — using default distance", plan.getId());
			return pricingProperties.defaultDistanceKm();
		}

		try {
			Optional<Coordinates> origin = oneMapClient.geocode(pricingProperties.originPostalCode());
			Optional<Coordinates> destination = oneMapClient.geocode(destinationPostalCode);
			if (origin.isEmpty() || destination.isEmpty()) {
				log.warn("Plan {} — OneMap could not resolve origin/destination postal code — using default distance",
						plan.getId());
				return pricingProperties.defaultDistanceKm();
			}
			return haversineKm(origin.get(), destination.get());
		} catch (OneMapException ex) {
			log.warn("Plan {} — distance lookup unavailable ({}: {}) — using default distance",
					plan.getId(), ex.getErrorCode(), ex.getMessage());
			return pricingProperties.defaultDistanceKm();
		}
	}

	static double haversineKm(Coordinates a, Coordinates b) {
		double lat1 = Math.toRadians(a.latitude());
		double lat2 = Math.toRadians(b.latitude());
		double deltaLat = Math.toRadians(b.latitude() - a.latitude());
		double deltaLon = Math.toRadians(b.longitude() - a.longitude());

		double h = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
				+ Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
		double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
		return EARTH_RADIUS_KM * c;
	}
}
