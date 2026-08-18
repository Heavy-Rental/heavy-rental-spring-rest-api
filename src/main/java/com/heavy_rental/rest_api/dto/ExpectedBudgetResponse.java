package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

/** Portal display of Call 1 expected budget (never invented client-side). */
public record ExpectedBudgetResponse(BigDecimal amount, String currency, String source) {
}
