// Unit tests for DynamicPricingService (openspec/changes/dynamic-plan-quote-pricing/): the
// per-item fallback to DefaultPricingClient (Asset.baseDailyRate) whenever haystack's pricing
// endpoint is unavailable for the whole batch, or a specific item, must never let a HaystackException
// escape priceItems() — the caller (RentalPlanService.requestQuote()) must always get a price.
package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.heavy_rental.rest_api.client.haystack.HaystackException;
import com.heavy_rental.rest_api.client.haystack.HaystackPricingClient;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteRequest;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteResponse;
import com.heavy_rental.rest_api.client.haystack.dto.PricingQuoteResponseItem;
import com.heavy_rental.rest_api.config.PricingProperties;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.RentalPlan;
import com.heavy_rental.rest_api.entity.RentalPlanRecord;

@ExtendWith(MockitoExtension.class)
class DynamicPricingServiceTest {

    @Mock private HaystackPricingClient haystackPricingClient;
    @Mock private DefaultPricingClient defaultPricingClient;
    @Mock private DistanceService distanceService;

    private DynamicPricingService service;

    private RentalPlan plan;
    private RentalPlanRecord item1;
    private RentalPlanRecord item2;

    @BeforeEach
    void setUp() {
        service = new DynamicPricingService(haystackPricingClient, defaultPricingClient, distanceService,
                new PricingProperties(true, 20.0, "629462", true));
        // Not every test below cares about the resolved distance (e.g. isEnabled_...) — lenient so
        // those aren't flagged as unnecessary stubbing; priceItems_usesDistanceServiceResolvedDistance
        // overrides this default for its specific plan to prove the value actually flows through.
        lenient().when(distanceService.resolveDistanceKm(any())).thenReturn(20.0);

        plan = new RentalPlan();
        plan.setId(55L);
        plan.setStartDate(LocalDate.of(2026, 9, 1));
        plan.setEndDate(LocalDate.of(2026, 9, 5));

        Asset asset1 = new Asset();
        asset1.setId(4L);
        item1 = new RentalPlanRecord();
        item1.setId(101L);
        item1.setAsset(asset1);

        Asset asset2 = new Asset();
        asset2.setId(7L);
        item2 = new RentalPlanRecord();
        item2.setId(102L);
        item2.setAsset(asset2);
    }

    @Test
    void isEnabled_delegatesToPricingProperties() {
        assertThat(service.isEnabled()).isTrue();
        assertThat(new DynamicPricingService(haystackPricingClient, defaultPricingClient, distanceService,
                new PricingProperties(false, 20.0, "629462", true)).isEnabled()).isFalse();
    }

    @Test
    void priceItems_happyPath_usesHaystackPricesAndNeverFallsBack() {
        var result1 = new PricingQuoteResponseItem(
                "101", 4L, new BigDecimal("182.40"), new BigDecimal("912.00"),
                true, new BigDecimal("120.00"), new BigDecimal("260.00"), "prod-2026-08-01", false, null);
        when(haystackPricingClient.quote(any(PricingQuoteRequest.class), anyString()))
                .thenReturn(new PricingQuoteResponse("55", "SGD", new BigDecimal("0.30"), false,
                        List.of(result1), List.of()));

        List<PricingClient.ItemPrice> prices = service.priceItems(plan, List.of(item1), "corr-test-1");

        assertThat(prices).hasSize(1);
        assertThat(prices.get(0).dailyRate()).isEqualByComparingTo("182.40");
        assertThat(prices.get(0).subtotal()).isEqualByComparingTo("912.00");
        verify(defaultPricingClient, never()).priceItem(any(), any(), any());
    }

    @Test
    void priceItems_explicitCorrelationId_isPropagatedToHaystackClient() {
        // Portal's inbound X-Correlation-Id (RentalPlanController) must reach the outbound
        // haystack call unchanged — same tracing convention as RecommenderSagaService.
        when(haystackPricingClient.quote(any(PricingQuoteRequest.class), eq("corr-from-portal")))
                .thenReturn(new PricingQuoteResponse("55", "SGD", new BigDecimal("0.30"), false, List.of(), List.of()));

        service.priceItems(plan, List.of(item1), "corr-from-portal");

        verify(haystackPricingClient).quote(any(PricingQuoteRequest.class), eq("corr-from-portal"));
    }

    @Test
    void priceItems_nullCorrelationId_generatesFreshNonBlankOne() {
        when(haystackPricingClient.quote(any(PricingQuoteRequest.class), anyString()))
                .thenReturn(new PricingQuoteResponse("55", "SGD", new BigDecimal("0.30"), false, List.of(), List.of()));
        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);

        service.priceItems(plan, List.of(item1), null);

        verify(haystackPricingClient).quote(any(PricingQuoteRequest.class), correlationCaptor.capture());
        assertThat(correlationCaptor.getValue()).isNotBlank();
    }

    @Test
    void priceItems_wholeBatchFailure_fallsBackForEveryItem() {
        when(haystackPricingClient.quote(any(PricingQuoteRequest.class), anyString()))
                .thenThrow(new HaystackException(503, "pricing_unavailable", "circuit open",
                        HaystackException.Kind.UNAVAILABLE));
        when(defaultPricingClient.priceItem(eq(item1.getAsset()), any(), any()))
                .thenReturn(new PricingClient.ItemPrice(new BigDecimal("450.00"), new BigDecimal("2250.00")));
        when(defaultPricingClient.priceItem(eq(item2.getAsset()), any(), any()))
                .thenReturn(new PricingClient.ItemPrice(new BigDecimal("300.00"), new BigDecimal("1500.00")));

        List<PricingClient.ItemPrice> prices = service.priceItems(plan, List.of(item1, item2), "corr-test-2");

        assertThat(prices).hasSize(2);
        assertThat(prices.get(0).dailyRate()).isEqualByComparingTo("450.00");
        assertThat(prices.get(1).dailyRate()).isEqualByComparingTo("300.00");
    }

    @Test
    void priceItems_degradedResult_usesHaystackPriceWithoutFallback() {
        // haystack's `degraded` flag means the model fell back to secondary/stale data, not that
        // pricing failed — the returned price is still used, unlike `error` (see spec.md).
        var degraded = new PricingQuoteResponseItem(
                "101", 4L, new BigDecimal("182.40"), new BigDecimal("912.00"),
                true, new BigDecimal("120.00"), new BigDecimal("260.00"), "prod-2026-08-01", true, null);
        when(haystackPricingClient.quote(any(PricingQuoteRequest.class), anyString()))
                .thenReturn(new PricingQuoteResponse("55", "SGD", new BigDecimal("0.30"), true,
                        List.of(degraded), List.of()));

        List<PricingClient.ItemPrice> prices = service.priceItems(plan, List.of(item1), "corr-test-4");

        assertThat(prices.get(0).dailyRate()).isEqualByComparingTo("182.40");
        verify(defaultPricingClient, never()).priceItem(any(), any(), any());
    }

    @Test
    void priceItems_usesDistanceServiceResolvedDistance_inOutboundRequest() {
        // Distinct from the 20.0 default (see setUp()) so a passing test proves the value is
        // actually wired through DistanceService, not coincidentally matching a fallback constant.
        when(distanceService.resolveDistanceKm(plan)).thenReturn(37.5);
        when(haystackPricingClient.quote(any(PricingQuoteRequest.class), anyString()))
                .thenReturn(new PricingQuoteResponse("55", "SGD", new BigDecimal("0.30"), false, List.of(), List.of()));
        ArgumentCaptor<PricingQuoteRequest> requestCaptor = ArgumentCaptor.forClass(PricingQuoteRequest.class);

        service.priceItems(plan, List.of(item1), "corr-distance-1");

        verify(haystackPricingClient).quote(requestCaptor.capture(), anyString());
        assertThat(requestCaptor.getValue().distanceKm()).isEqualTo(37.5);
    }

    @Test
    void priceItems_perItemError_fallsBackOnlyForThatItem() {
        var usable = new PricingQuoteResponseItem(
                "101", 4L, new BigDecimal("182.40"), new BigDecimal("912.00"),
                true, new BigDecimal("120.00"), new BigDecimal("260.00"), "prod-2026-08-01", false, null);
        var errored = new PricingQuoteResponseItem(
                "102", 7L, null, null, false, null, null, null, false, "asset_not_found");
        when(haystackPricingClient.quote(any(PricingQuoteRequest.class), anyString()))
                .thenReturn(new PricingQuoteResponse("55", "SGD", new BigDecimal("0.30"), false,
                        List.of(usable, errored), List.of()));
        when(defaultPricingClient.priceItem(eq(item2.getAsset()), any(), any()))
                .thenReturn(new PricingClient.ItemPrice(new BigDecimal("300.00"), new BigDecimal("1500.00")));

        List<PricingClient.ItemPrice> prices = service.priceItems(plan, List.of(item1, item2), "corr-test-3");

        assertThat(prices.get(0).dailyRate()).isEqualByComparingTo("182.40");
        assertThat(prices.get(1).dailyRate()).isEqualByComparingTo("300.00");
        verify(defaultPricingClient, never()).priceItem(eq(item1.getAsset()), any(), any());
    }
}
