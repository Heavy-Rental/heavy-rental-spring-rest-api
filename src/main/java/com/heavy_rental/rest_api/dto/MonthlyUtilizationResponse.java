package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

public record MonthlyUtilizationResponse(
        Long id,
        String month,
        Double utilization,
        BigDecimal revenue) {
}
