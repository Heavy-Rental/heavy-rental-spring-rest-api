package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;

public record BookingUpdateRequest(
    LocalDate startDate,
    LocalDate endDate,
    String siteAddress,
    String deliveryNotes) {
}
