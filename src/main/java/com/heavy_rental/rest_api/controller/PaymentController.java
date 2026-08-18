package com.heavy_rental.rest_api.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.CreateDepositIntentRequest;
import com.heavy_rental.rest_api.dto.PaymentIntentResponse;
import com.heavy_rental.rest_api.service.PaymentService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/deposit-intent")
    public ResponseEntity<PaymentIntentResponse> createDepositIntent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateDepositIntentRequest request) {
        try {
            PaymentIntent intent = paymentService.createDepositPaymentIntent(jwt, request.bookingId());
            return ResponseEntity.ok(new PaymentIntentResponse(intent.getClientSecret(), intent.getId()));
        } catch (StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe error: " + e.getMessage());
        }
    }
}
