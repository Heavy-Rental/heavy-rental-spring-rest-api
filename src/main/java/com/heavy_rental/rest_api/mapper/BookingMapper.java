package com.heavy_rental.rest_api.mapper;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.heavy_rental.rest_api.dto.BookingItemLine;
import com.heavy_rental.rest_api.dto.BookingResponse;
import com.heavy_rental.rest_api.dto.DeliveryItemResponse;
import com.heavy_rental.rest_api.dto.ReturnItemResponse;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;

@Component
public class BookingMapper {

    public BookingResponse toBookingResponse(Booking booking, List<BookingItem> items) {
        return new BookingResponse(
                booking.getId(),
                booking.getCustomer() != null ? booking.getCustomer().getName() : "",
                booking.getStartDate(),
                booking.getEndDate(),
                booking.getStatus().name(),
                booking.getSiteAddress(),
                toItemLines(items),
                booking.getDeliveryNotes(),
                booking.getTotalAmount(),
                booking.getDepositAmount(),
                booking.getRemainingBalance());
    }

    public DeliveryItemResponse toDeliveryItemResponse(Booking booking, List<BookingItem> items) {
        return new DeliveryItemResponse(
                booking.getId(),
                booking.getCustomer() != null ? booking.getCustomer().getName() : "",
                booking.getStartDate(),
                booking.getSiteAddress(),
                toItemLines(items),
                booking.getDeliveryNotes(),
                booking.getStatus().name());
    }

    public ReturnItemResponse toReturnItemResponse(Booking booking, List<BookingItem> items) {
        return new ReturnItemResponse(
                booking.getId(),
                booking.getCustomer() != null ? booking.getCustomer().getName() : "",
                booking.getEndDate(),
                booking.getSiteAddress(),
                toItemLines(items),
                booking.getDeliveryNotes(),
                booking.getReturnNotes(),
                booking.getStatus().name());
    }

    public List<BookingItemLine> toItemLines(List<BookingItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(BookingItem::getId))
                .map(item -> new BookingItemLine(
                        item.getAsset() != null ? item.getAsset().getId() : null,
                        item.getAsset() != null ? item.getAsset().getName() : "",
                        item.getAsset() != null ? item.getAsset().getSerialno() : ""))
                .toList();
    }
}
