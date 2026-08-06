package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

public record BookingItemResponse(
        Long id,
        Long assetId,
        String assetName,
        BigDecimal dailyRate,
        BigDecimal subtotal) {
}
