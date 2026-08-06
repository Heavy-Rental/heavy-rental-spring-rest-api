package com.heavy_rental.rest_api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.entity.User;
import com.heavy_rental.rest_api.repository.BookingRepository;
import com.heavy_rental.rest_api.repository.PaymentRepository;
import com.heavy_rental.rest_api.repository.UserRepository;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public PaymentService(
            PaymentRepository paymentRepository,
            BookingRepository bookingRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
    }

    /**
     * Client-initiated: creates the 30% deposit PaymentIntent for a booking, saving the
     * payment method (via setup_future_usage=off_session) so the balance can be charged
     * later without the customer present. Booking fetch, ownership check, and the Stripe
     * call all happen in one transaction so the lazy `booking.customer` relation stays
     * accessible (open-in-view is disabled for this app).
     */
    @Transactional
    public PaymentIntent createDepositPaymentIntent(Jwt jwt, Long bookingId) throws StripeException {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        currentUserService.assertOwnerOrAdmin(jwt, booking.getCustomer());

        // TODO(stripe-refactor): this used to guard against re-initiating an already-paid
        // deposit via the now-removed Booking.paidStatus field (develop folded payment state
        // into BookingStatus instead — see 8bdf067). Re-add an equivalent guard against
        // Booking.BookingStatus once this flow is reconciled with that model.

        String stripeCustomerId = resolveOrCreateStripeCustomer(booking.getCustomer());

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toCents(booking.getDepositAmount()))
                .setCurrency("sgd")
                .setCustomer(stripeCustomerId)
                .setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                .putMetadata("booking_id", String.valueOf(booking.getId()))
                .putMetadata("payment_type", "DEPOSIT")
                .build();

        PaymentIntent intent = PaymentIntent.create(params);

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setStripePaymentIntentId(intent.getId());
        payment.setStripeCustomerId(stripeCustomerId);
        payment.setAmount(booking.getDepositAmount());
        payment.setPaymentType(Payment.PaymentType.DEPOSIT);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setCreatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        return intent;
    }

    /**
     * System-initiated only (wired into the daily cron in Phase 3): charges the remaining
     * 70% off-session using the payment method saved during the deposit. Single attempt —
     * on failure this persists the failure and flags the booking for manual follow-up,
     * it never retries.
     */
    @Transactional
    public void chargeBalanceOffSession(Booking booking) {
        Payment depositPayment = paymentRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> p.getPaymentType() == Payment.PaymentType.DEPOSIT
                        && p.getStatus() == Payment.PaymentStatus.SUCCESS)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No successful deposit payment found for booking " + booking.getId()));

        String stripeCustomerId = depositPayment.getStripeCustomerId();
        String stripePaymentMethodId = depositPayment.getStripePaymentMethodId();

        Payment balancePayment = new Payment();
        balancePayment.setBooking(booking);
        balancePayment.setStripeCustomerId(stripeCustomerId);
        balancePayment.setAmount(booking.getRemainingBalance());
        balancePayment.setPaymentType(Payment.PaymentType.BALANCE);
        balancePayment.setStatus(Payment.PaymentStatus.PENDING);
        balancePayment.setCreatedAt(LocalDateTime.now());
        balancePayment = paymentRepository.save(balancePayment);

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(toCents(booking.getRemainingBalance()))
                    .setCurrency("sgd")
                    .setCustomer(stripeCustomerId)
                    .setPaymentMethod(stripePaymentMethodId)
                    .setOffSession(true)
                    .setConfirm(true)
                    .putMetadata("booking_id", String.valueOf(booking.getId()))
                    .putMetadata("payment_type", "BALANCE")
                    .build();

            PaymentIntent intent = PaymentIntent.create(params);

            balancePayment.setStripePaymentIntentId(intent.getId());
            balancePayment.setStripePaymentMethodId(intent.getPaymentMethod());
            balancePayment.setStripeChargeId(intent.getLatestCharge());

            if ("succeeded".equals(intent.getStatus())) {
                balancePayment.setStatus(Payment.PaymentStatus.SUCCESS);
                balancePayment.setPaidAt(LocalDateTime.now());
                // TODO(stripe-refactor): used to also set the now-removed Booking.paidStatus
                // to FULL here. Transition Booking.status to its develop-model equivalent
                // once this flow is reconciled with BookingStatus (see 8bdf067).
                booking.setRemainingBalance(BigDecimal.ZERO);
                bookingRepository.save(booking);
            }
            paymentRepository.save(balancePayment);
        } catch (StripeException e) {
            String declineCode = (e instanceof CardException cardException) ? cardException.getDeclineCode() : null;

            balancePayment.setStatus(Payment.PaymentStatus.FAIL);
            balancePayment.setFailureReason(e.getMessage());
            balancePayment.setRequiresManualFollowUp(true);
            balancePayment.setManualFollowUpReason(classifyFailure(e.getCode(), declineCode));
            paymentRepository.save(balancePayment);

            booking.setNeedsManualFollowUp(true);
            bookingRepository.save(booking);
        }
    }

    private String resolveOrCreateStripeCustomer(User user) throws StripeException {
        if (user.getStripeCustomerId() != null && !user.getStripeCustomerId().isBlank()) {
            return user.getStripeCustomerId();
        }

        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getName())
                .build();
        Customer customer = Customer.create(params);

        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        return customer.getId();
    }

    private static long toCents(BigDecimal amount) {
        // Stripe expects SGD in cents (1 SGD = 100 cents); round to nearest cent before converting.
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static String classifyFailure(String stripeErrorCode, String declineCode) {
        if ("authentication_required".equals(stripeErrorCode)) {
            return "authentication_required";
        }
        if ("expired_card".equals(declineCode)) {
            return "expired_card";
        }
        if (declineCode != null) {
            return "card_declined";
        }
        return "other";
    }
}
