package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;

public record ReturnItemResponse(
    Long bookingId,
    String customerName,
    LocalDate endDate,
    String siteAddress,
    String assetName,
    String serialNumber,
    String deliveryNotes,
    String returnNotes,
    String bookingStatus) {
}
