package com.heavy_rental.rest_api.client.haystack.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Call 1 optional expected budget (never invent client-side). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpectedBudgetDto(BigDecimal amount, String currency, String source) {
}
