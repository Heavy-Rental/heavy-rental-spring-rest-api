package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;
import java.util.List;

public record DeliveryItemResponse(
    Long bookingId,
    String customerName,
    LocalDate startDate,
    String siteAddress,
    List<BookingItemLine> items,
    String deliveryNotes,
    String bookingStatus) {
}

