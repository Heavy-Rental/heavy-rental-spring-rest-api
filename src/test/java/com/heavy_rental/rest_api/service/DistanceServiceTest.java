// Unit tests for DistanceService (openspec/changes/pricing-postal-distance/): resolveDistanceKm
// must always succeed and fall back to pricing.default-distance-km for every failure mode —
// disabled lookup, missing/malformed destination postal code, no OneMap match, or an
// OneMapException of any kind — never letting a caller (DynamicPricingService) see an exception.
package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.heavy_rental.rest_api.client.onemap.Coordinates;
import com.heavy_rental.rest_api.client.onemap.OneMapClient;
import com.heavy_rental.rest_api.client.onemap.OneMapException;
import com.heavy_rental.rest_api.config.PricingProperties;
import com.heavy_rental.rest_api.entity.RentalPlan;

@ExtendWith(MockitoExtension.class)
class DistanceServiceTest {

    @Mock private OneMapClient oneMapClient;

    private DistanceService service;
    private RentalPlan plan;

    private static final String ORIGIN = "629462";

    // 11 Gul Drive (origin) and 20 Jurong Port Road (a real delivery postal code), verified live
    // against OneMap while designing this change — real-world sanity check for the haversine math.
    private static final Coordinates ORIGIN_COORDS = new Coordinates(1.31692631645881, 103.673636054133, "11 GUL DRIVE SINGAPORE 629462");
    private static final Coordinates DEST_COORDS = new Coordinates(1.3186451330849, 103.719175822788, "20 JURONG PORT ROAD SINGAPORE 619094");

    @BeforeEach
    void setUp() {
        service = new DistanceService(oneMapClient, new PricingProperties(true, 20.0, ORIGIN, true));

        plan = new RentalPlan();
        plan.setId(55L);
    }

    @Test
    void resolveDistanceKm_happyPath_computesHaversineDistance() {
        plan.setSitePostalCode("619094");
        when(oneMapClient.geocode(ORIGIN)).thenReturn(Optional.of(ORIGIN_COORDS));
        when(oneMapClient.geocode("619094")).thenReturn(Optional.of(DEST_COORDS));

        double distanceKm = service.resolveDistanceKm(plan);

        // Straight-line distance between these two real Tuas-area coordinates, independently
        // computed (Python) for this test: ~5.07km.
        assertThat(distanceKm).isCloseTo(5.066, within(0.01));
    }

    @Test
    void resolveDistanceKm_lookupDisabled_returnsDefaultWithoutCallingOneMap() {
        service = new DistanceService(oneMapClient, new PricingProperties(true, 20.0, ORIGIN, false));
        plan.setSitePostalCode("619094");

        double distanceKm = service.resolveDistanceKm(plan);

        assertThat(distanceKm).isEqualTo(20.0);
        verify(oneMapClient, never()).geocode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolveDistanceKm_nullSitePostalCode_returnsDefaultWithoutCallingOneMap() {
        plan.setSitePostalCode(null);

        double distanceKm = service.resolveDistanceKm(plan);

        assertThat(distanceKm).isEqualTo(20.0);
        verify(oneMapClient, never()).geocode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolveDistanceKm_malformedSitePostalCode_returnsDefaultWithoutCallingOneMap() {
        plan.setSitePostalCode("12345");

        double distanceKm = service.resolveDistanceKm(plan);

        assertThat(distanceKm).isEqualTo(20.0);
        verify(oneMapClient, never()).geocode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolveDistanceKm_originNotFound_returnsDefault() {
        plan.setSitePostalCode("619094");
        when(oneMapClient.geocode(ORIGIN)).thenReturn(Optional.empty());
        when(oneMapClient.geocode("619094")).thenReturn(Optional.of(DEST_COORDS));

        assertThat(service.resolveDistanceKm(plan)).isEqualTo(20.0);
    }

    @Test
    void resolveDistanceKm_destinationNotFound_returnsDefault() {
        plan.setSitePostalCode("619094");
        when(oneMapClient.geocode(ORIGIN)).thenReturn(Optional.of(ORIGIN_COORDS));
        when(oneMapClient.geocode("619094")).thenReturn(Optional.empty());

        assertThat(service.resolveDistanceKm(plan)).isEqualTo(20.0);
    }

    @Test
    void resolveDistanceKm_oneMapThrows_returnsDefaultAndNeverPropagates() {
        plan.setSitePostalCode("619094");
        when(oneMapClient.geocode(eq(ORIGIN)))
                .thenThrow(new OneMapException(503, "onemap_unavailable", "circuit open", OneMapException.Kind.UNAVAILABLE));

        double distanceKm = service.resolveDistanceKm(plan);

        assertThat(distanceKm).isEqualTo(20.0);
    }

    @Test
    void haversineKm_samePoint_isZero() {
        assertThat(DistanceService.haversineKm(ORIGIN_COORDS, ORIGIN_COORDS)).isEqualTo(0.0, within(1e-9));
    }
}
