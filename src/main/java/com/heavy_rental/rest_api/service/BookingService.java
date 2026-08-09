package com.heavy_rental.rest_api.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.BookingResponse;
import com.heavy_rental.rest_api.dto.BookingUpdateRequest;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.mapper.BookingMapper;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.BookingRepository;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final BookingMapper mapper;

    public BookingService(BookingRepository bookingRepository, BookingItemRepository bookingItemRepository,
            BookingMapper mapper) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getBookings() {
        return bookingRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId) {
        return toResponse(findByIdOr404(bookingId));
    }

    @Transactional
    public BookingResponse updateBooking(Long bookingId, BookingUpdateRequest request) {
        Booking booking = findByIdOr404(bookingId);

        booking.setStartDate(request.startDate());
        booking.setEndDate(request.endDate());
        booking.setSiteAddress(request.siteAddress());
        booking.setDeliveryNotes(request.deliveryNotes());

        bookingRepository.save(booking);
        return toResponse(booking);
    }

    private BookingResponse toResponse(Booking booking) {
        return mapper.toBookingResponse(booking, bookingItemRepository.findByBookingId(booking.getId()));
    }

    Booking findByIdOr404(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found: " + bookingId));
    }

    static Booking.BookingStatus parseStatusOr400(String status) {
        try {
            return Booking.BookingStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bookingStatus: " + status);
        }
    }
}
