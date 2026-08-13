// Unit tests for the HR-129 checkout-conversion logic in BookingService: converting a QUOTED
// RentalPlan into a Booking (ownership/status/24-hour-freshness checks, items/price derived from
// the plan, CONVERTED transition), and the day-count math fix for direct (no rentalPlanId)
// bookings. Not yet run locally — this environment's mvnw test is currently broken by an
// unrelated, pre-existing local Maven repo gap (missing surefire-plugin transitive dependencies).
package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.BookingResponse;
import com.heavy_rental.rest_api.dto.CreateBookingItemRequest;
import com.heavy_rental.rest_api.dto.CreateBookingRequest;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.RentalPlan;
import com.heavy_rental.rest_api.entity.RentalPlanRecord;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.mapper.BookingMapper;
import com.heavy_rental.rest_api.repository.AssetRepository;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.RentalPlanRecordRepository;
import com.heavy_rental.rest_api.repository.RentalPlanRepository;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingItemRepository bookingItemRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private RentalPlanRepository rentalPlanRepository;
    @Mock private RentalPlanRecordRepository rentalPlanRecordRepository;
    @Mock private CurrentUserService currentUserService;

    private BookingService service;
    private User customer;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        service = new BookingService(
                bookingRepository, bookingItemRepository, assetRepository,
                rentalPlanRepository, rentalPlanRecordRepository, currentUserService, new BookingMapper());

        customer = new User();
        customer.setId(1L);
        customer.setName("Mei Lin");
        customer.setEmail("mei.lin@example.sg");

        jwt = mock(Jwt.class);
        when(currentUserService.getUser(jwt)).thenReturn(customer);

        // Simulates IDENTITY generation: a real save() assigns an id to a new row.
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking booking = inv.getArgument(0);
            booking.setId(100L);
            return booking;
        });
        when(bookingItemRepository.findByBookingId(100L)).thenReturn(List.of());
    }

    private RentalPlan quotedPlan(Long ownerId, LocalDateTime updatedAt) {
        RentalPlan plan = new RentalPlan();
        plan.setId(9L);
        User owner = new User();
        owner.setId(ownerId);
        plan.setCustomer(owner);
        plan.setStatus(RentalPlan.PlanStatus.QUOTED);
        plan.setStartDate(LocalDate.of(2026, 9, 1));
        plan.setEndDate(LocalDate.of(2026, 9, 5));
        plan.setTotalAmount(new BigDecimal("2250.00"));
        plan.setUpdatedAt(updatedAt);
        return plan;
    }

    private RentalPlanRecord recordFor(Asset asset, String dailyRate, String subtotal) {
        RentalPlanRecord record = new RentalPlanRecord();
        record.setAsset(asset);
        record.setDailyRate(new BigDecimal(dailyRate));
        record.setSubtotal(new BigDecimal(subtotal));
        return record;
    }

    // --- REQ-6: checkout from a RentalPlan --------------------------------------------------

    @Test
    void createBooking_rentalPlanNotOwnedByCaller_throws404() {
        RentalPlan plan = quotedPlan(999L, LocalDateTime.now());
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        CreateBookingRequest request = new CreateBookingRequest(null, null, null, 9L, "1 Test St, 123456", null);

        assertThatThrownBy(() -> service.createBooking(jwt, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void createBooking_planNotQuoted_throwsQuoteNotReady() {
        RentalPlan plan = quotedPlan(1L, LocalDateTime.now());
        plan.setStatus(RentalPlan.PlanStatus.DRAFT);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        CreateBookingRequest request = new CreateBookingRequest(null, null, null, 9L, "1 Test St, 123456", null);

        assertThatThrownBy(() -> service.createBooking(jwt, request))
                .isInstanceOf(RentalPlanConflictException.class)
                .satisfies(ex -> assertThat(((RentalPlanConflictException) ex).getCode())
                        .isEqualTo("quote_not_ready"));
    }

    @Test
    void createBooking_staleQuote_throwsQuoteExpired() {
        RentalPlan plan = quotedPlan(1L, LocalDateTime.now().minus(Duration.ofHours(25)));
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        CreateBookingRequest request = new CreateBookingRequest(null, null, null, 9L, "1 Test St, 123456", null);

        assertThatThrownBy(() -> service.createBooking(jwt, request))
                .isInstanceOf(RentalPlanConflictException.class)
                .satisfies(ex -> assertThat(((RentalPlanConflictException) ex).getCode())
                        .isEqualTo("quote_expired"));
    }

    @Test
    void createBooking_quotedPlanWithNullUpdatedAt_treatedAsExpired() {
        // Defensive edge case: a QUOTED plan that somehow never got an updatedAt stamp (e.g. a
        // row from before HR-129) must fail closed, not NPE or be treated as fresh.
        RentalPlan plan = quotedPlan(1L, null);
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        CreateBookingRequest request = new CreateBookingRequest(null, null, null, 9L, "1 Test St, 123456", null);

        assertThatThrownBy(() -> service.createBooking(jwt, request))
                .isInstanceOf(RentalPlanConflictException.class)
                .satisfies(ex -> assertThat(((RentalPlanConflictException) ex).getCode())
                        .isEqualTo("quote_expired"));
    }

    @Test
    void createBooking_happyPath_derivesFromPlanAndConvertsIt() {
        RentalPlan plan = quotedPlan(1L, LocalDateTime.now());
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        Asset asset = new Asset();
        asset.setId(1L);
        asset.setName("CAT 320 Excavator");
        when(rentalPlanRecordRepository.findByRentalPlanId(9L))
                .thenReturn(List.of(recordFor(asset, "450.00", "2250.00")));
        when(bookingItemRepository.findAssetIdsWithOverlappingBooking(anyCollection(), any(), any(), anyCollection()))
                .thenReturn(Set.of());

        CreateBookingRequest request = new CreateBookingRequest(null, null, null, 9L, "1 Test St, 123456", "Notes");
        BookingResponse response = service.createBooking(jwt, request);

        assertThat(response.bookingStatus()).isEqualTo("PENDING_DEPOSIT");
        assertThat(response.totalAmount()).isEqualByComparingTo("2250.00");
        assertThat(response.depositAmount()).isEqualByComparingTo("675.00");
        assertThat(response.remainingBalance()).isEqualByComparingTo("1575.00");

        assertThat(plan.getStatus()).isEqualTo(RentalPlan.PlanStatus.CONVERTED);
        verify(rentalPlanRepository).save(plan);
        verify(bookingItemRepository).saveAll(any());
    }

    @Test
    void createBooking_availabilityConflict_doesNotConvertPlan() {
        RentalPlan plan = quotedPlan(1L, LocalDateTime.now());
        when(rentalPlanRepository.findById(9L)).thenReturn(Optional.of(plan));

        Asset asset = new Asset();
        asset.setId(1L);
        when(rentalPlanRecordRepository.findByRentalPlanId(9L))
                .thenReturn(List.of(recordFor(asset, "450.00", "2250.00")));
        when(bookingItemRepository.findAssetIdsWithOverlappingBooking(anyCollection(), any(), any(), anyCollection()))
                .thenReturn(Set.of(1L));

        CreateBookingRequest request = new CreateBookingRequest(null, null, null, 9L, "1 Test St, 123456", null);

        assertThatThrownBy(() -> service.createBooking(jwt, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertThat(plan.getStatus()).isEqualTo(RentalPlan.PlanStatus.QUOTED);
        verify(rentalPlanRepository, never()).save(any(RentalPlan.class));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    // --- Direct booking (no rentalPlanId): day-count math -----------------------------------

    @Test
    void createBooking_directItems_dayCountIsInclusiveOfBothEnds() {
        // Regression test for the day-math fix: DAYS.between had no +1, disagreeing with
        // DefaultPricingClient's quote-time math. Dec 1 -> Dec 4 must be 4 days, not 3.
        Asset asset = new Asset();
        asset.setId(1L);
        asset.setBaseDailyRate(new BigDecimal("450.00"));
        when(assetRepository.findById(1L)).thenReturn(Optional.of(asset));
        when(bookingItemRepository.findAssetIdsWithOverlappingBooking(anyCollection(), any(), any(), anyCollection()))
                .thenReturn(Set.of());

        CreateBookingRequest request = new CreateBookingRequest(
                List.of(new CreateBookingItemRequest(1L)),
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 4),
                null, "7 Straits View, 018936", null);

        BookingResponse response = service.createBooking(jwt, request);

        assertThat(response.totalAmount()).isEqualByComparingTo("1800.00"); // 450 x 4 inclusive days
        assertThat(response.depositAmount()).isEqualByComparingTo("540.00");
        assertThat(response.remainingBalance()).isEqualByComparingTo("1260.00");
    }
}
