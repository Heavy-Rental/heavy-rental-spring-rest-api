package com.heavy_rental.rest_api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BookingResponse(
    Long bookingId,
    String customerName,
    LocalDate startDate,
    LocalDate endDate,
    String bookingStatus,
    String siteAddress,
    List<BookingItemLine> items,
    String deliveryNotes,
    BigDecimal totalAmount,
    BigDecimal depositAmount,
    BigDecimal remainingBalance) {
}
