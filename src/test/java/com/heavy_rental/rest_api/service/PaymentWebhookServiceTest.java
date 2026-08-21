// Unit tests for the FULL_PAYMENT branch added to PaymentWebhookService: a successful
// full-payment webhook must confirm the booking directly (CONFIRMED, remainingBalance = 0),
// skipping PENDING_CONFIRMED entirely, and a failed full payment must not set manual
// follow-up (confirmed product decision: same treatment as a failed deposit).
package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private BookingRepository bookingRepository;

    private PaymentWebhookService service;

    @BeforeEach
    void setUp() {
        service = new PaymentWebhookService(paymentRepository, bookingRepository);
    }

    @Test
    void reconcilePending_fullPaymentSuccess_confirmsBookingDirectly() {
        Booking booking = new Booking();
        booking.setId(20L);
        booking.setStatus(Booking.BookingStatus.PENDING_DEPOSIT);
        booking.setRemainingBalance(BigDecimal.valueOf(1000));

        Payment payment = new Payment();
        payment.setId(99L);
        payment.setBooking(booking);
        payment.setPaymentType(Payment.PaymentType.FULL_PAYMENT);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setStripePaymentIntentId("pi_full_789");

        when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));

        PaymentIntent stripeIntent = mock(PaymentIntent.class);
        when(stripeIntent.getStatus()).thenReturn("succeeded");
        when(stripeIntent.getPaymentMethod()).thenReturn("pm_123");
        when(stripeIntent.getLatestCharge()).thenReturn("ch_123");

        try (MockedStatic<PaymentIntent> stripeStatic = mockStatic(PaymentIntent.class)) {
            stripeStatic.when(() -> PaymentIntent.retrieve("pi_full_789")).thenReturn(stripeIntent);
            service.reconcilePending(99L);
        }

        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.SUCCESS);
        // Skips PENDING_CONFIRMED entirely -- that status is what BalanceChargeSchedulerService
        // queries for, and a fully-paid booking must never be picked up by it.
        assertThat(booking.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        assertThat(booking.getRemainingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void applyFailed_fullPayment_doesNotSetManualFollowUp() {
        Booking booking = new Booking();
        booking.setId(21L);

        Payment payment = new Payment();
        payment.setId(100L);
        payment.setBooking(booking);
        payment.setPaymentType(Payment.PaymentType.FULL_PAYMENT);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setStripePaymentIntentId("pi_full_fail");

        when(paymentRepository.findByStripePaymentIntentId("pi_full_fail")).thenReturn(Optional.of(payment));

        PaymentIntent stripeIntent = mock(PaymentIntent.class);
        when(stripeIntent.getId()).thenReturn("pi_full_fail");
        when(stripeIntent.getLastPaymentError()).thenReturn(null);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(stripeIntent));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.payment_failed");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        service.handle(event);

        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.FAIL);
        assertThat(payment.isRequiresManualFollowUp()).isFalse();
        assertThat(booking.isNeedsManualFollowUp()).isFalse();
    }
}
