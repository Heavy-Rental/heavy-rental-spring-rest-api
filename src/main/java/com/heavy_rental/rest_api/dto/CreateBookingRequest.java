package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateBookingRequest(
        List<CreateBookingItemRequest> items,
        LocalDate startDate,
        LocalDate endDate,
        Long rentalPlanId,
        @NotBlank(message = "Site address is required")
        @Pattern(
                regexp = "^.*\\d{6}$",
                message = "Site address must end with a 6-digit postal code, e.g. \"20 Jurong Port Road, 619094\"")
        String siteAddress,
        String deliveryNotes) {
    public CreateBookingRequest {
        siteAddress = siteAddress == null ? null : siteAddress.strip();
    }
}