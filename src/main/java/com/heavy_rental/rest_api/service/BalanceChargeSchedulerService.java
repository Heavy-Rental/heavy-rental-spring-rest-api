package com.heavy_rental.rest_api.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;

/**
 * Daily sweep (02:00 Asia/Singapore) that charges the 70% balance for bookings starting
 * tomorrow, off-session, using the payment method saved at deposit time.
 */
@Service
public class BalanceChargeSchedulerService {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public BalanceChargeSchedulerService(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
    }

    // TODO(stripe-refactor): the sweep query this relied on (bookings starting tomorrow,
    // deposit paid, not cancelled) was built on the now-removed Booking.paidStatus field
    // (develop folded payment state into BookingStatus instead — see 8bdf067). Disabled
    // until this is rebuilt against Booking.BookingStatus; processOne() below is kept
    // (with its own paidStatus guard removed) for manual invocation/testing in the
    // meantime, per SPEC-stripe.md's verification checklist.
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Singapore")
    public void chargeBalancesDueTomorrow() {
        List<Booking> due = List.of();

        // Each booking is its own transaction (see processOne) so one failure can't abort the batch.
        for (Booking booking : due) {
            processOne(booking.getId());
        }
    }

    @Transactional
    public void processOne(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return;
        }

        List<Payment> balancePayments = paymentRepository.findByBookingId(bookingId).stream()
                .filter(p -> p.getPaymentType() == Payment.PaymentType.BALANCE)
                .toList();

        // Any non-FAIL BALANCE payment means it's already succeeded or in flight; any FAIL
        // means the single allowed attempt already happened. Either way, skip — never retry.
        boolean alreadyHandled = !balancePayments.isEmpty();
        if (alreadyHandled) {
            return;
        }

        booking.setBalanceChargeAttemptedAt(LocalDateTime.now());
        bookingRepository.save(booking);

        paymentService.chargeBalanceOffSession(booking);
    }
}
