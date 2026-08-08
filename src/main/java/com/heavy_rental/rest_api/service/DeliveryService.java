package com.heavy_rental.rest_api.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.DeliveryItemResponse;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.mapper.BookingMapper;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.BookingRepository;

@Service
public class DeliveryService {

    private static final List<Booking.BookingStatus> DELIVERY_STATUSES =
            List.of(Booking.BookingStatus.CONFIRMED, Booking.BookingStatus.MOBILISED);

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final BookingMapper mapper;

    public DeliveryService(BookingRepository bookingRepository, BookingItemRepository bookingItemRepository,
            BookingMapper mapper) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.mapper = mapper;
    }

    /** startDate == today AND status in (CONFIRMED, MOBILISED). */
    @Transactional(readOnly = true)
    public List<DeliveryItemResponse> getTodaysDeliveries() {
        return bookingRepository.findByStartDateAndStatusIn(LocalDate.now(), DELIVERY_STATUSES).stream()
                .map(b -> mapper.toDeliveryItemResponse(b, bookingItemRepository.findByBookingId(b.getId())))
                .toList();
    }

    /** Only CONFIRMED -> MOBILISED is legal here. */
    @Transactional
    public DeliveryItemResponse updateStatus(Long bookingId, String requestedStatus) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found: " + bookingId));
        Booking.BookingStatus requested = BookingService.parseStatusOr400(requestedStatus);

        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED || requested != Booking.BookingStatus.MOBILISED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid transition: " + booking.getStatus() + " -> " + requestedStatus
                            + " (only CONFIRMED -> MOBILISED is allowed here)");
        }

        booking.setStatus(requested);
        bookingRepository.save(booking);
        return mapper.toDeliveryItemResponse(booking, bookingItemRepository.findByBookingId(booking.getId()));
    }
}