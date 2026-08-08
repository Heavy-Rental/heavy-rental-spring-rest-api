package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.dto.BookingResponse;
import com.heavy_rental.rest_api.service.BookingService;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping
    public List<BookingResponse> getBookings() {
        return bookingService.getBookings();
    }

    @GetMapping("/{bookingId}")
    public BookingResponse getBooking(@PathVariable Long bookingId) {
        return bookingService.getBooking(bookingId);
    }

    @PutMapping("/{bookingId}")
    public BookingResponse updateBooking(@PathVariable Long bookingId, @RequestBody BookingResponse request) {
        return bookingService.updateBooking(bookingId, request);
    }
}
