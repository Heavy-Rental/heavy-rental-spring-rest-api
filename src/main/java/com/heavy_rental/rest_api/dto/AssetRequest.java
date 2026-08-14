package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssetRequest(
        @NotBlank String name,
        @NotBlank String serialno,
        @NotNull Long categoryId,
        @NotNull BigDecimal baseDailyRate,
        @NotNull BigDecimal minDailyRate,
        @NotNull BigDecimal maxDailyRate,
        Integer capacity,
        BigDecimal platformHeight,
        Integer purchaseYear,
        String condition,
        String desc,
        String location) {
}
