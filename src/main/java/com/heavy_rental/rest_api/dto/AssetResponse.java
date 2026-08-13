package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AssetResponse(
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
        String location,
        List<String> tags,
        String serialno,
        LocalDateTime lastConditionUpdatedAt) {

}
