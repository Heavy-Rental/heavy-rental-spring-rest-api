package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;

public record RentalPlanCreateRequest(
        LocalDate startDate,
        LocalDate endDate,
        String siteAddress) {
}
