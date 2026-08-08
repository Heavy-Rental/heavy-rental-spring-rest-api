package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

public record EquipmentResponse(
        Long id,
        String name,
        String category,
        BigDecimal baseDailyRate,
        BigDecimal minDailyRate,
        BigDecimal maxDailyRate,
        Integer capacity,
        BigDecimal platformHeight,
        Integer purchaseYear,
        String condition,
        Boolean available,
        String desc,
        String img,
        String location) {
}
