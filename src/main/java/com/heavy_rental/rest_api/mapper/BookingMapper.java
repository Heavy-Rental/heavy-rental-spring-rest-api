package com.heavy_rental.rest_api.mapper;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.heavy_rental.rest_api.dto.BookingResponse;
import com.heavy_rental.rest_api.dto.DeliveryItemResponse;
import com.heavy_rental.rest_api.dto.ReturnItemResponse;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;

@Component
public class BookingMapper {

    public BookingResponse toBookingResponse(Booking booking, List<BookingItem> items) {
        Asset asset = primaryAsset(items);
        return new BookingResponse(
                booking.getId(),
                booking.getCustomer() != null ? booking.getCustomer().getName() : "",
                booking.getStartDate(),
                booking.getEndDate(),
                booking.getStatus().name(),
                booking.getSiteAddress(),
                asset != null ? asset.getName() : "",
                asset != null ? asset.getSerialno() : "",
                booking.getDeliveryNotes());
    }

    public DeliveryItemResponse toDeliveryItemResponse(Booking booking, List<BookingItem> items) {
        Asset asset = primaryAsset(items);
        return new DeliveryItemResponse(
                booking.getId(),
                booking.getCustomer() != null ? booking.getCustomer().getName() : "",
                booking.getStartDate(),
                booking.getSiteAddress(),
                asset != null ? asset.getName() : "",
                asset != null ? asset.getSerialno() : "",
                booking.getDeliveryNotes(),
                booking.getStatus().name());
    }

    public ReturnItemResponse toReturnItemResponse(Booking booking, List<BookingItem> items) {
        Asset asset = primaryAsset(items);
        return new ReturnItemResponse(
                booking.getId(),
                booking.getCustomer() != null ? booking.getCustomer().getName() : "",
                booking.getEndDate(),
                booking.getSiteAddress(),
                asset != null ? asset.getName() : "",
                asset != null ? asset.getSerialno() : "",
                booking.getDeliveryNotes(),
                booking.getStatus().name());
    }

    private Asset primaryAsset(List<BookingItem> items) {
        return items.stream()
                .min(Comparator.comparing(BookingItem::getId))
                .map(BookingItem::getAsset)
                .orElse(null);
    }
}
