package com.heavy_rental.rest_api.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.heavy_rental.rest_api.entity.Payment;
import com.heavy_rental.rest_api.repository.PaymentRepository;

/**
 * Backstop for missed/failed Stripe webhook deliveries (HR-203 — RNT-0099 and several
 * other bookings were found stuck at PENDING_DEPOSIT despite Stripe having already
 * captured the deposit, traced to no `stripe listen` running locally at the time).
 * Periodically re-checks any payment still PENDING a few minutes after creation directly
 * against Stripe via PaymentWebhookService.reconcilePending, so a single missed webhook
 * can't strand a booking indefinitely — in local dev or production alike.
 */
@Service
public class PaymentReconciliationSchedulerService {

    private static final int STALE_AFTER_MINUTES = 10;

    private final PaymentRepository paymentRepository;
    private final PaymentWebhookService paymentWebhookService;

    public PaymentReconciliationSchedulerService(
            PaymentRepository paymentRepository, PaymentWebhookService paymentWebhookService) {
        this.paymentRepository = paymentRepository;
        this.paymentWebhookService = paymentWebhookService;
    }

    @Scheduled(fixedDelayString = "PT15M")
    public void reconcilePendingPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_AFTER_MINUTES);
        List<Payment> stale = paymentRepository.findByStatus(Payment.PaymentStatus.PENDING).stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isBefore(cutoff))
                .toList();

        for (Payment payment : stale) {
            paymentWebhookService.reconcilePending(payment);
        }
    }
}
