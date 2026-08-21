// Guards the invariant that becomes load-bearing once FULL_PAYMENT exists: the scheduler
// must only ever target PENDING_CONFIRMED bookings, so a booking that went straight to
// CONFIRMED via a full payment (skipping PENDING_CONFIRMED) is never picked up for a second,
// unwanted balance charge.
package com.heavy_rental.rest_api.service;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class BalanceChargeSchedulerServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentService paymentService;

    private BalanceChargeSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new BalanceChargeSchedulerService(bookingRepository, paymentRepository, paymentService);
    }

    @Test
    void chargeBalancesDueTomorrow_skipsFullyPaidConfirmedBookings() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        Booking pendingConfirmed = new Booking();
        pendingConfirmed.setId(1L);
        pendingConfirmed.setStatus(Booking.BookingStatus.PENDING_CONFIRMED);
        pendingConfirmed.setRemainingBalance(BigDecimal.valueOf(700));

        Booking fullyPaidConfirmed = new Booking();
        fullyPaidConfirmed.setId(2L);
        fullyPaidConfirmed.setStatus(Booking.BookingStatus.CONFIRMED);

        // Mirrors the real query's filtering, so the test fails if the scheduler is ever
        // widened to also request CONFIRMED bookings.
        when(bookingRepository.findByStartDateAndStatusIn(eq(tomorrow), anyList()))
                .thenAnswer(invocation -> {
                    List<Booking.BookingStatus> statuses = invocation.getArgument(1);
                    return Stream.of(pendingConfirmed, fullyPaidConfirmed)
                            .filter(b -> statuses.contains(b.getStatus()))
                            .toList();
                });
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(pendingConfirmed));
        when(paymentRepository.findByBookingId(1L)).thenReturn(List.of());

        service.chargeBalancesDueTomorrow();

        verify(bookingRepository).findByStartDateAndStatusIn(
                eq(tomorrow), eq(List.of(Booking.BookingStatus.PENDING_CONFIRMED)));
        verify(paymentService).chargeBalanceOffSession(pendingConfirmed);
        verify(paymentService, never()).chargeBalanceOffSession(fullyPaidConfirmed);
        verify(bookingRepository, never()).findById(2L);
    }

    @Test
    void chargeBalancesDueTomorrow_noBookingsDue_doesNothing() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        when(bookingRepository.findByStartDateAndStatusIn(eq(tomorrow), anyList())).thenReturn(List.of());

        service.chargeBalancesDueTomorrow();

        verify(paymentService, never()).chargeBalanceOffSession(org.mockito.ArgumentMatchers.any());
    }
}
