package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;

/**
 * Applies Stripe webhook events to our Payment/Booking rows. Every branch checks the
 * current status before writing, because this races with the synchronous confirm-call
 * result in PaymentService.chargeBalanceOffSession (Phase 3) and Stripe redelivers events.
 */
@Service
public class PaymentWebhookService {

    private static final String EVENT_SUCCEEDED = "payment_intent.succeeded";
    private static final String EVENT_FAILED = "payment_intent.payment_failed";

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;

    public PaymentWebhookService(PaymentRepository paymentRepository, BookingRepository bookingRepository) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
    }

    @Transactional
    public void handle(Event event) {
        if (!EVENT_SUCCEEDED.equals(event.getType()) && !EVENT_FAILED.equals(event.getType())) {
            return;
        }

        PaymentIntent intent = extractPaymentIntent(event);
        if (intent == null) {
            return;
        }

        Payment payment = paymentRepository.findByStripePaymentIntentId(intent.getId()).orElse(null);
        if (payment == null || payment.getStatus() != Payment.PaymentStatus.PENDING) {
            // Not ours, or already resolved by an earlier delivery / the synchronous caller — no-op.
            return;
        }

        if (EVENT_SUCCEEDED.equals(event.getType())) {
            applySucceeded(payment, intent);
        } else {
            applyFailed(payment, intent);
        }
    }

    private void applySucceeded(Payment payment, PaymentIntent intent) {
        payment.setStatus(Payment.PaymentStatus.SUCCESS);
        payment.setPaidAt(LocalDateTime.now());
        payment.setStripePaymentMethodId(intent.getPaymentMethod());
        payment.setStripeChargeId(intent.getLatestCharge());
        paymentRepository.save(payment);

        // TODO(stripe-refactor): this used to set the now-removed Booking.paidStatus to
        // DEPOSIT/FULL here (develop folded payment state into BookingStatus instead — see
        // 8bdf067). Transition Booking.status to its develop-model equivalent once this
        // flow is reconciled with that model.
        Booking booking = payment.getBooking();
        if (payment.getPaymentType() == Payment.PaymentType.BALANCE) {
            booking.setRemainingBalance(BigDecimal.ZERO);
        }
        bookingRepository.save(booking);
    }

    private void applyFailed(Payment payment, PaymentIntent intent) {
        StripeError error = intent.getLastPaymentError();

        payment.setStatus(Payment.PaymentStatus.FAIL);
        payment.setFailureReason(error != null ? error.getMessage() : "Payment failed");

        if (payment.getPaymentType() == Payment.PaymentType.BALANCE) {
            payment.setRequiresManualFollowUp(true);
            payment.setManualFollowUpReason(classify(error));

            Booking booking = payment.getBooking();
            booking.setNeedsManualFollowUp(true);
            bookingRepository.save(booking);
        }
        paymentRepository.save(payment);
    }

    private static String classify(StripeError error) {
        if (error == null) {
            return "other";
        }
        if ("authentication_required".equals(error.getCode())) {
            return "authentication_required";
        }
        if ("expired_card".equals(error.getDeclineCode())) {
            return "expired_card";
        }
        if (error.getDeclineCode() != null) {
            return "card_declined";
        }
        return "other";
    }

    private static PaymentIntent extractPaymentIntent(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        return stripeObject instanceof PaymentIntent intent ? intent : null;
    }
}
