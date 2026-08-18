package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.heavy_rental.rest_api.dto.BookingResponse;
import com.heavy_rental.rest_api.dto.BookingUpdateRequest;
import com.heavy_rental.rest_api.dto.CreateBookingRequest;
import com.heavy_rental.rest_api.service.BookingService;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createBooking(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateBookingRequest request) {
        return bookingService.createBooking(jwt, request);
    }

    @GetMapping
    public List<BookingResponse> getBookings(@AuthenticationPrincipal Jwt jwt) {
        return bookingService.getBookings(jwt);
    }

    @GetMapping("/{bookingId}")
    public BookingResponse getBooking(@PathVariable Long bookingId, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.getBooking(bookingId, jwt);
    }

    @PutMapping("/{bookingId}")
    public BookingResponse updateBooking(
            @PathVariable Long bookingId, @Valid @RequestBody BookingUpdateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.updateBooking(bookingId, request, jwt);
    }
}
