package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;
import com.stripe.exception.StripeException;
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

    /**
     * Reconciliation entry point for PaymentReconciliationSchedulerService: re-checks a
     * payment that's been stuck PENDING directly against Stripe, for cases where the
     * payment_intent.succeeded webhook was never delivered (HR-203 — e.g. no `stripe
     * listen` running locally at the time). Applies the same success logic as the webhook
     * path, guarded by the same PENDING check. Only "succeeded" is reconciled here — any
     * other Stripe-side status (still requires payment method, processing, canceled, etc.)
     * is left alone, since those aren't stranded bookings, just payments genuinely still
     * in progress or never completed, which the frontend's own failure screen already
     * covers on the client side when confirmPayment itself returns an error.
     */
    @Transactional
    public void reconcilePending(Payment payment) {
        if (payment.getStatus() != Payment.PaymentStatus.PENDING) {
            return;
        }

        PaymentIntent intent;
        try {
            intent = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
        } catch (StripeException e) {
            return;
        }

        if ("succeeded".equals(intent.getStatus())) {
            applySucceeded(payment, intent);
        }
    }

    private void applySucceeded(Payment payment, PaymentIntent intent) {
        payment.setStatus(Payment.PaymentStatus.SUCCESS);
        payment.setPaidAt(LocalDateTime.now());
        payment.setStripePaymentMethodId(intent.getPaymentMethod());
        payment.setStripeChargeId(intent.getLatestCharge());
        paymentRepository.save(payment);

        Booking booking = payment.getBooking();
        if (payment.getPaymentType() == Payment.PaymentType.DEPOSIT) {
            booking.setStatus(Booking.BookingStatus.PENDING_CONFIRMED);
        } else if (payment.getPaymentType() == Payment.PaymentType.BALANCE) {
            booking.setStatus(Booking.BookingStatus.CONFIRMED);
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
