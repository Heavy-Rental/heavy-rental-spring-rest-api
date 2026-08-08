package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;

public record DeliveryItemResponse(
    Long bookingId,
    String customerName,
    LocalDate startDate,
    String siteAddress,
    String assetName,
    String serialNumber,
    String deliveryNotes,
    String bookingStatus) {
}

