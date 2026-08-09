package com.heavy_rental.rest_api.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.ReturnItemResponse;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.mapper.BookingMapper;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.BookingRepository;

@Service
public class ReturnService {

    private static final List<Booking.BookingStatus> RETURN_STATUSES =
            List.of(Booking.BookingStatus.MOBILISED, Booking.BookingStatus.COMPLETED);

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final BookingMapper mapper;

    public ReturnService(BookingRepository bookingRepository, BookingItemRepository bookingItemRepository,
            BookingMapper mapper) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.mapper = mapper;
    }

    /** endDate == today AND status in (MOBILISED, COMPLETED). */
    @Transactional(readOnly = true)
    public List<ReturnItemResponse> getTodaysReturns() {
        return bookingRepository.findByEndDateAndStatusIn(LocalDate.now(), RETURN_STATUSES).stream()
                .map(b -> mapper.toReturnItemResponse(b, bookingItemRepository.findByBookingId(b.getId())))
                .toList();
    }

    /** Only MOBILISED -> COMPLETED is legal here. */
    @Transactional
    public ReturnItemResponse updateStatus(Long bookingId, String requestedStatus) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found: " + bookingId));
        Booking.BookingStatus requested = BookingService.parseStatusOr400(requestedStatus);

        if (booking.getStatus() != Booking.BookingStatus.MOBILISED || requested != Booking.BookingStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid transition: " + booking.getStatus() + " -> " + requestedStatus
                            + " (only MOBILISED -> COMPLETED is allowed here)");
        }

        booking.setStatus(requested);
        bookingRepository.save(booking);
        return mapper.toReturnItemResponse(booking, bookingItemRepository.findByBookingId(booking.getId()));
    }
}
