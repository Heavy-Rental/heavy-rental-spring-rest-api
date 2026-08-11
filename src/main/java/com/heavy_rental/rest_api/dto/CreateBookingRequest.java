package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;
import java.util.List;

public record CreateBookingRequest(
        List<CreateBookingItemRequest> items,
        LocalDate startDate,
        LocalDate endDate,
        Long rentalPlanId,
        String siteAddress,
        String deliveryNotes) {
}
