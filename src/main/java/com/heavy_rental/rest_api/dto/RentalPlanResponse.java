package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RentalPlanResponse(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        String siteAddress,
        String status,
        BigDecimal totalAmount,
        List<RentalPlanItemResponse> items) {
}
