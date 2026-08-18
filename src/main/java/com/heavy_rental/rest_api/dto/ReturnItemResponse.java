package com.heavy_rental.rest_api.dto;

import java.time.LocalDate;
import java.util.List;

public record ReturnItemResponse(
    Long bookingId,
    String customerName,
    LocalDate endDate,
    String siteAddress,
    List<BookingItemLine> items,
    String deliveryNotes,
    String returnNotes,
    String bookingStatus) {
}
