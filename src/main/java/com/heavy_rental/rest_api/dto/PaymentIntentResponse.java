package com.heavy_rental.rest_api.dto;

public record PaymentIntentResponse(String clientSecret, String paymentIntentId) {
}
