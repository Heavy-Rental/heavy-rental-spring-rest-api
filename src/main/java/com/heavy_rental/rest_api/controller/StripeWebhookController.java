package com.heavy_rental.rest_api.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.service.PaymentWebhookService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

/**
 * Receives Stripe webhook events. Unauthenticated at the Spring Security layer (see
 * SecurityConfig) — the Stripe-Signature check below is the auth mechanism, since Stripe
 * cannot present a JWT.
 */
@RestController
@RequestMapping("/api/payments/webhook")
public class StripeWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    public StripeWebhookController(PaymentWebhookService paymentWebhookService) {
        this.paymentWebhookService = paymentWebhookService;
    }

    @PostMapping
    public ResponseEntity<Void> handle(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signatureHeader) {
        Event event;
        try {
            // Must verify against the raw request body — a parsed/re-serialized DTO would
            // not reproduce the exact bytes Stripe signed.
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        paymentWebhookService.handle(event);
        return ResponseEntity.ok().build();
    }
}
