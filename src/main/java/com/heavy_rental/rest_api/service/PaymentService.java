package com.heavy_rental.rest_api.service;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PaymentService {

    public PaymentIntent createPaymentIntent(BigDecimal amount, Integer bookingId) throws StripeException {
        // Stripe expects SGD in cents (1 SGD = 100 cents).
        // Round to nearest cent (HALF_UP) before converting to long to prevent precision loss.
        long amountInCents = amount.multiply(BigDecimal.valueOf(100))
                                  .setScale(0, RoundingMode.HALF_UP)
                                  .longValue();

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountInCents)
                .setCurrency("sgd") // Fixed to SGD
                .putMetadata("booking_id", String.valueOf(bookingId))
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                )
                .build();

        return PaymentIntent.create(params);
    }
}