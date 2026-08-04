package com.heavy_rental.rest_api.controller;

import com.heavy_rental.rest_api.service.PaymentService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // DTO for JSON request body
    public record CreatePaymentIntentRequest(BigDecimal amount, Integer bookingId) {}

    @PostMapping("/create-payment-intent")
    public ResponseEntity<Map<String, String>> createPaymentIntent(
            @RequestBody CreatePaymentIntentRequest request) {
        try {
            // Fixed: pass 2 arguments (amount, bookingId) matching your updated service signature
            PaymentIntent intent = paymentService.createPaymentIntent(
                    request.amount(), 
                    request.bookingId()
            );

            return ResponseEntity.ok(Map.of(
                    "clientSecret", intent.getClientSecret(),
                    "paymentIntentId", intent.getId()
            ));
        } catch (StripeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}