// Unit tests for the full-payment one-shot path added to PaymentService: creating a
// FULL_PAYMENT PaymentIntent for Booking.totalAmount, and the double-payment guards on both
// createFullPaymentIntent and the pre-existing createDepositPaymentIntent (which must now also
// reject when a FULL_PAYMENT already exists).
package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private CurrentUserService currentUserService;

    private PaymentService service;
    private Booking booking;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, bookingRepository, userRepository, currentUserService);

        User customer = new User();
        customer.setId(1L);
        customer.setEmail("mei.lin@example.sg");
        customer.setStripeCustomerId("cus_existing");

        booking = new Booking();
        booking.setId(10L);
        booking.setCustomer(customer);
        booking.setTotalAmount(BigDecimal.valueOf(1000));
        booking.setDepositAmount(BigDecimal.valueOf(300));
        booking.setRemainingBalance(BigDecimal.valueOf(700));

        jwt = mock(Jwt.class);
    }

    @Test
    void createFullPaymentIntent_chargesTotalAmountNotDeposit() throws Exception {
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(10L)).thenReturn(List.of());

        PaymentIntent fakeIntent = mock(PaymentIntent.class);
        when(fakeIntent.getId()).thenReturn("pi_full_123");

        ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        try (MockedStatic<PaymentIntent> stripeStatic = mockStatic(PaymentIntent.class)) {
            stripeStatic.when(() -> PaymentIntent.create(paramsCaptor.capture())).thenReturn(fakeIntent);

            PaymentIntent result = service.createFullPaymentIntent(jwt, 10L);

            assertThat(result).isSameAs(fakeIntent);
        }

        assertThat(paramsCaptor.getValue().getAmount()).isEqualTo(100000L);
        assertThat(paramsCaptor.getValue().getSetupFutureUsage()).isNull();

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();
        assertThat(saved.getPaymentType()).isEqualTo(Payment.PaymentType.FULL_PAYMENT);
        assertThat(saved.getStatus()).isEqualTo(Payment.PaymentStatus.PENDING);
        assertThat(saved.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void createFullPaymentIntent_rejectsWhenDepositAlreadyExists() {
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        Payment existingDeposit = new Payment();
        existingDeposit.setPaymentType(Payment.PaymentType.DEPOSIT);
        existingDeposit.setStatus(Payment.PaymentStatus.SUCCESS);
        when(paymentRepository.findByBookingId(10L)).thenReturn(List.of(existingDeposit));

        assertThatThrownBy(() -> service.createFullPaymentIntent(jwt, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been initiated or paid");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void createFullPaymentIntent_rejectsWhenFullPaymentAlreadyPending() {
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        Payment existingFullPayment = new Payment();
        existingFullPayment.setPaymentType(Payment.PaymentType.FULL_PAYMENT);
        existingFullPayment.setStatus(Payment.PaymentStatus.PENDING);
        when(paymentRepository.findByBookingId(10L)).thenReturn(List.of(existingFullPayment));

        assertThatThrownBy(() -> service.createFullPaymentIntent(jwt, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been initiated or paid");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void createFullPaymentIntent_allowsRetryAfterFailedAttempt() throws Exception {
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        Payment failedFullPayment = new Payment();
        failedFullPayment.setPaymentType(Payment.PaymentType.FULL_PAYMENT);
        failedFullPayment.setStatus(Payment.PaymentStatus.FAIL);
        when(paymentRepository.findByBookingId(10L)).thenReturn(List.of(failedFullPayment));

        PaymentIntent fakeIntent = mock(PaymentIntent.class);
        when(fakeIntent.getId()).thenReturn("pi_full_456");

        try (MockedStatic<PaymentIntent> stripeStatic = mockStatic(PaymentIntent.class)) {
            stripeStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(fakeIntent);
            PaymentIntent result = service.createFullPaymentIntent(jwt, 10L);
            assertThat(result).isSameAs(fakeIntent);
        }
    }

    // Patched guard: a customer who already paid in full must not also be able to open a
    // deposit intent, or they'd end up paying both.
    @Test
    void createDepositPaymentIntent_rejectsWhenFullPaymentAlreadyExists() {
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        Payment existingFullPayment = new Payment();
        existingFullPayment.setPaymentType(Payment.PaymentType.FULL_PAYMENT);
        existingFullPayment.setStatus(Payment.PaymentStatus.PENDING);
        when(paymentRepository.findByBookingId(10L)).thenReturn(List.of(existingFullPayment));

        assertThatThrownBy(() -> service.createDepositPaymentIntent(jwt, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been initiated or paid");

        verify(paymentRepository, never()).save(any());
    }
}
