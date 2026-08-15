// Unit tests for the HR-129 checkout-conversion changes to RentalPlanService: re-quoting an
// already-QUOTED plan (the requestQuote regression found live during HR-129 verification — see
// SPEC-rental-plan-quote.md REQ-4), and item add/remove reverting a QUOTED plan to DRAFT (B10,
// REQ-2/REQ-3). Not yet run locally — this environment's mvnw test is currently broken by an
// unrelated, pre-existing local Maven repo gap (missing surefire-plugin transitive dependencies).
package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.RentalPlanCreateRequest;
import com.heavy_rental.rest_api.dto.RentalPlanItemRequest;
import com.heavy_rental.rest_api.dto.RentalPlanResponse;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.RentalPlan;
import com.heavy_rental.rest_api.entity.RentalPlanRecord;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.AssetRepository;
import com.heavy_rental.rest_api.repository.RentalPlanRecordRepository;
import com.heavy_rental.rest_api.repository.RentalPlanRepository;
import com.heavy_rental.rest_api.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class RentalPlanServiceTest {

    private static final String EMAIL = "mei.lin@example.sg";

    @Mock private RentalPlanRepository rentalPlanRepository;
    @Mock private RentalPlanRecordRepository rentalPlanRecordRepository;
    @Mock private UserRepository userRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private PricingClient pricingClient;
    @Mock private DynamicPricingService dynamicPricingService;
    @Mock private PlatformTransactionManager transactionManager;

    private RentalPlanService service;
    private User customer;

    @BeforeEach
    void setUp() {
        // dynamicPricingService.isEnabled() defaults to false (unstubbed boolean mock), so these
        // existing tests exercise the pre-dynamic-pricing behavior unchanged.
        // transactionManager is an unstubbed mock — TransactionTemplate.execute() just invokes
        // the callback directly against it (getTransaction()/commit()/rollback() are no-ops),
        // so requestQuote's two-phase read/write split runs synchronously here, same as real
        // transactions would, without needing a real DB transaction manager.
        service = new RentalPlanService(
                rentalPlanRepository, rentalPlanRecordRepository, userRepository, assetRepository, pricingClient,
                dynamicPricingService, transactionManager);

        customer = new User();
        customer.setId(1L);
        customer.setEmail(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(customer));
    }

    private RentalPlan ownedPlan(Long id, RentalPlan.PlanStatus status) {
        RentalPlan plan = new RentalPlan();
        plan.setId(id);
        plan.setCustomer(customer);
        plan.setStatus(status);
        plan.setStartDate(LocalDate.of(2026, 9, 1));
        plan.setEndDate(LocalDate.of(2026, 9, 5));
        return plan;
    }

    // --- REQ-4: requestQuote ---------------------------------------------------------------

    @Test
    void requestQuote_onAlreadyQuotedPlan_succeedsAndRefreshesUpdatedAt() {
        // Regression test for the bug found live during HR-129 verification: requestQuote used
        // to reject ANY non-DRAFT/SAVED plan with 409 "Plan is already quoted", which silently
        // broke the quote_expired recovery path — a customer with a stale QUOTED plan had no way
        // to refresh it. Fixed to reject only CONVERTED.
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.QUOTED);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        RentalPlanRecord item = new RentalPlanRecord();
        item.setId(1L);
        Asset asset = new Asset();
        asset.setId(1L);
        asset.setName("CAT 320 Excavator");
        item.setAsset(asset);
        item.setDailyRate(new BigDecimal("450.00"));
        item.setSubtotal(new BigDecimal("2250.00"));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of(item));

        RentalPlanResponse response = service.requestQuote(9L, EMAIL, null);

        assertThat(response.status()).isEqualTo("QUOTED");
        assertThat(response.totalAmount()).isEqualByComparingTo("2250.00");
        assertThat(plan.getUpdatedAt()).isNotNull();
    }

    @Test
    void requestQuote_onConvertedPlan_rejectedWith409() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.CONVERTED);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.requestQuote(9L, EMAIL, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requestQuote_withDynamicPricingEnabled_usesRefreshedPricesFromDynamicPricingService() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.DRAFT);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        RentalPlanRecord item = new RentalPlanRecord();
        item.setId(1L);
        Asset asset = new Asset();
        asset.setId(1L);
        asset.setName("CAT 320 Excavator");
        item.setAsset(asset);
        item.setDailyRate(new BigDecimal("450.00"));
        item.setSubtotal(new BigDecimal("2250.00"));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of(item));

        when(dynamicPricingService.isEnabled()).thenReturn(true);
        when(dynamicPricingService.priceItems(eq(plan), eq(List.of(item)), any()))
                .thenReturn(List.of(new PricingClient.ItemPrice(new BigDecimal("500.00"), new BigDecimal("2500.00"))));

        RentalPlanResponse response = service.requestQuote(9L, EMAIL, null);

        assertThat(response.status()).isEqualTo("QUOTED");
        assertThat(response.totalAmount()).isEqualByComparingTo("2500.00");
        assertThat(item.getDailyRate()).isEqualByComparingTo("500.00");
        assertThat(item.getSubtotal()).isEqualByComparingTo("2500.00");
        verify(rentalPlanRecordRepository).save(item);
    }

    @Test
    void requestQuote_propagatesInboundCorrelationIdToDynamicPricingService() {
        // The portal's X-Correlation-Id (RentalPlanController) must reach DynamicPricingService
        // unchanged, same tracing convention as RecommenderSagaService — required so a customer's
        // "Get Quote" request and its downstream haystack call share one correlation id in logs.
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.DRAFT);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        RentalPlanRecord item = new RentalPlanRecord();
        item.setId(1L);
        Asset asset = new Asset();
        asset.setId(1L);
        item.setAsset(asset);
        item.setDailyRate(new BigDecimal("450.00"));
        item.setSubtotal(new BigDecimal("2250.00"));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of(item));

        when(dynamicPricingService.isEnabled()).thenReturn(true);
        when(dynamicPricingService.priceItems(eq(plan), eq(List.of(item)), eq("corr-quote-1")))
                .thenReturn(List.of(new PricingClient.ItemPrice(new BigDecimal("500.00"), new BigDecimal("2500.00"))));

        service.requestQuote(9L, EMAIL, "corr-quote-1");

        verify(dynamicPricingService).priceItems(eq(plan), eq(List.of(item)), eq("corr-quote-1"));
    }

    @Test
    void requestQuote_withDynamicPricingDisabled_neverCallsDynamicPricingServiceForPrices() {
        // dynamicPricingService.isEnabled() defaults to false on the unstubbed mock — this is
        // the same path exercised by requestQuote_onAlreadyQuotedPlan_succeedsAndRefreshesUpdatedAt,
        // asserted explicitly here so the flag-off contract has its own regression test.
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.DRAFT);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        RentalPlanRecord item = new RentalPlanRecord();
        item.setId(1L);
        Asset asset = new Asset();
        asset.setId(1L);
        item.setAsset(asset);
        item.setDailyRate(new BigDecimal("450.00"));
        item.setSubtotal(new BigDecimal("2250.00"));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of(item));

        RentalPlanResponse response = service.requestQuote(9L, EMAIL, null);

        assertThat(response.totalAmount()).isEqualByComparingTo("2250.00");
        verify(dynamicPricingService, never()).priceItems(any(), any(), any());
    }

    @Test
    void requestQuote_emptyPlan_rejectedWith400() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.DRAFT);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.requestQuote(9L, EMAIL, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- REQ-2/REQ-3 (B10): item add/remove revert a QUOTED plan ---------------------------

    @Test
    void addItem_onQuotedPlan_revertsToDraftAndClearsTotal() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.QUOTED);
        plan.setTotalAmount(new BigDecimal("2250.00"));
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        Asset asset = new Asset();
        asset.setId(2L);
        asset.setName("Komatsu PC210 Excavator");
        when(assetRepository.findById(2L)).thenReturn(Optional.of(asset));
        when(pricingClient.priceItem(eq(asset), any(), any()))
                .thenReturn(new PricingClient.ItemPrice(new BigDecimal("470.00"), new BigDecimal("2350.00")));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of());

        RentalPlanResponse response = service.addItem(9L, new RentalPlanItemRequest(2L), EMAIL);

        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.totalAmount()).isNull();
        assertThat(plan.getStatus()).isEqualTo(RentalPlan.PlanStatus.DRAFT);
        assertThat(plan.getTotalAmount()).isNull();
        assertThat(plan.getUpdatedAt()).isNotNull();
        verify(rentalPlanRecordRepository).save(any(RentalPlanRecord.class));
    }

    @Test
    void removeItem_onQuotedPlan_revertsToDraftAndClearsTotal() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.QUOTED);
        plan.setTotalAmount(new BigDecimal("2250.00"));
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        RentalPlanRecord item = new RentalPlanRecord();
        item.setId(10L);
        item.setRentalPlan(plan);
        when(rentalPlanRecordRepository.findById(10L)).thenReturn(Optional.of(item));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of());

        RentalPlanResponse response = service.removeItem(9L, 10L, EMAIL);

        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(plan.getTotalAmount()).isNull();
        verify(rentalPlanRecordRepository).delete(item);
    }

    @Test
    void addItem_onDraftPlan_doesNotReQuoteOrTouchStatus() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.DRAFT);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        Asset asset = new Asset();
        asset.setId(1L);
        when(assetRepository.findById(1L)).thenReturn(Optional.of(asset));
        when(pricingClient.priceItem(eq(asset), any(), any()))
                .thenReturn(new PricingClient.ItemPrice(new BigDecimal("450.00"), new BigDecimal("2250.00")));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of());

        service.addItem(9L, new RentalPlanItemRequest(1L), EMAIL);

        assertThat(plan.getStatus()).isEqualTo(RentalPlan.PlanStatus.DRAFT);
        // revertQuoteIfNeeded is a no-op on a DRAFT plan, so the plan itself is never re-saved.
        verify(rentalPlanRepository, never()).save(plan);
    }

    // --- BR-06: one active plan per customer ------------------------------------------------

    @Test
    void create_whenActivePlanExists_rejectedWith409() {
        RentalPlan existing = ownedPlan(1L, RentalPlan.PlanStatus.QUOTED);
        when(rentalPlanRepository.findByCustomerId(1L)).thenReturn(List.of(existing));

        RentalPlanCreateRequest request =
                new RentalPlanCreateRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), "1 Test St, 123456");

        assertThatThrownBy(() -> service.create(request, EMAIL))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void create_afterPriorPlanConverted_succeedsAndStampsCreatedAt() {
        // The bug that motivated this whole change: a CONVERTED plan must NOT count as "active"
        // for BR-06, or a customer who completes one booking is locked out of ever starting
        // another cart.
        RentalPlan converted = ownedPlan(1L, RentalPlan.PlanStatus.CONVERTED);
        when(rentalPlanRepository.findByCustomerId(1L)).thenReturn(List.of(converted));
        // The newly created plan is never actually persisted in this mock (no IDENTITY
        // generation), so its id stays null when toResponse() looks up its items — any() rather
        // than anyLong() to match that unambiguously.
        when(rentalPlanRecordRepository.findByRentalPlanId(any())).thenReturn(List.of());

        RentalPlanCreateRequest request =
                new RentalPlanCreateRequest(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "1 Test St, 123456");

        RentalPlanResponse response = service.create(request, EMAIL);

        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.createdAt()).isNotNull();
    }

    // --- cancel ------------------------------------------------------------------------------

    @Test
    void cancel_onQuotedPlan_setsCancelledAndClearsTotal() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.QUOTED);
        plan.setTotalAmount(new BigDecimal("2250.00"));
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));
        when(rentalPlanRecordRepository.findByRentalPlanId(9L)).thenReturn(List.of());

        RentalPlanResponse response = service.cancel(9L, EMAIL);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.totalAmount()).isNull();
        assertThat(plan.getUpdatedAt()).isNotNull();
        verify(rentalPlanRepository).save(plan);
    }

    @Test
    void cancel_onConvertedPlan_rejectedWith409AlreadyConverted() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.CONVERTED);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.cancel(9L, EMAIL))
                .isInstanceOf(RentalPlanConflictException.class)
                .satisfies(ex -> assertThat(((RentalPlanConflictException) ex).getCode())
                        .isEqualTo("already_converted"));
    }

    @Test
    void cancel_onAlreadyCancelledPlan_rejectedWith409AlreadyCancelled() {
        RentalPlan plan = ownedPlan(9L, RentalPlan.PlanStatus.CANCELLED);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.cancel(9L, EMAIL))
                .isInstanceOf(RentalPlanConflictException.class)
                .satisfies(ex -> assertThat(((RentalPlanConflictException) ex).getCode())
                        .isEqualTo("already_cancelled"));
    }

    @Test
    void cancel_doesNotCountAsActivePlan_forOneActivePlanRule() {
        RentalPlan cancelled = ownedPlan(1L, RentalPlan.PlanStatus.CANCELLED);
        when(rentalPlanRepository.findByCustomerId(1L)).thenReturn(List.of(cancelled));
        when(rentalPlanRecordRepository.findByRentalPlanId(any())).thenReturn(List.of());

        RentalPlanCreateRequest request =
                new RentalPlanCreateRequest(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "1 Test St, 123456");

        RentalPlanResponse response = service.create(request, EMAIL);

        assertThat(response.status()).isEqualTo("DRAFT");
    }
}
