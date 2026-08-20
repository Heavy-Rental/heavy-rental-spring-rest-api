package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

public record FullPaymentIntentResponse(String clientSecret, String paymentIntentId, BigDecimal amount) {
}
