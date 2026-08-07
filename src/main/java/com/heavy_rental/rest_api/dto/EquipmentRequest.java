package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

public record EquipmentRequest(
        String name,
        String serialno,
        Long categoryId,
        BigDecimal baseDailyRate,
        BigDecimal minDailyRate,
        BigDecimal maxDailyRate,
        Integer capacity,
        BigDecimal platformHeight,
        Integer purchaseYear,
        String condition,
        String desc) {
}
