// Unit tests for the returnNotes change (HR-100): valid transition persists returnNotes,
// invalid transition/unknown status/missing booking all leave returnNotes untouched.
// Uses JUnit 5 + Mockito + AssertJ, all already on the classpath via spring-boot-starter-test.
package com.heavy_rental.rest_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import com.heavy_rental.rest_api.dto.ReturnItemResponse;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.mapper.BookingMapper;
import com.heavy_rental.rest_api.repository.BookingItemRepository;
import com.heavy_rental.rest_api.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class ReturnServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingItemRepository bookingItemRepository;

    // Real mapper — pure mapping logic, nothing worth mocking here.
    private final BookingMapper mapper = new BookingMapper();

    private ReturnService returnService;

    @BeforeEach
    void setUp() {
        returnService = new ReturnService(bookingRepository, bookingItemRepository, mapper);
    }

    private Booking mobilisedBooking() {
        Booking booking = new Booking();
        booking.setId(4L);
        booking.setStatus(Booking.BookingStatus.MOBILISED);
        booking.setEndDate(LocalDate.now());
        booking.setSiteAddress("88 Tuas South Ave 3, Singapore 637311");
        booking.setDeliveryNotes("Crane assist required for offload");
        booking.setReturnNotes(null);
        return booking;
    }

    @Test
    void updateStatus_validTransition_persistsReturnNotesAndCompletesBooking() {
        Booking booking = mobilisedBooking();
        when(bookingRepository.findById(4L)).thenReturn(Optional.of(booking));
        when(bookingItemRepository.findByBookingId(4L)).thenReturn(List.of());
        when(bookingRepository.save(booking)).thenReturn(booking);

        ReturnItemResponse response = returnService.updateStatus(4L, "COMPLETED", "Returned in good condition");

        assertThat(booking.getStatus()).isEqualTo(Booking.BookingStatus.COMPLETED);
        assertThat(booking.getReturnNotes()).isEqualTo("Returned in good condition");
        assertThat(response.bookingStatus()).isEqualTo("COMPLETED");
        assertThat(response.returnNotes()).isEqualTo("Returned in good condition");
        verify(bookingRepository).save(booking);
    }

    @Test
    void updateStatus_invalidTargetStatus_rejectsAndLeavesReturnNotesUntouched() {
        Booking booking = mobilisedBooking(); // MOBILISED, but requested target is wrong
        when(bookingRepository.findById(4L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> returnService.updateStatus(4L, "CONFIRMED", "should not be saved"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid transition");

        assertThat(booking.getStatus()).isEqualTo(Booking.BookingStatus.MOBILISED); // unchanged
        assertThat(booking.getReturnNotes()).isNull(); // not persisted on rejection
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void updateStatus_wrongCurrentStatus_rejects() {
        Booking booking = mobilisedBooking();
        booking.setStatus(Booking.BookingStatus.CONFIRMED); // not MOBILISED
        when(bookingRepository.findById(4L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> returnService.updateStatus(4L, "COMPLETED", "note"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid transition");

        verify(bookingRepository, never()).save(any());
    }

    @Test
    void updateStatus_bookingNotFound_throws404() {
        when(bookingRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> returnService.updateStatus(999L, "COMPLETED", "note"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Booking not found");

        verify(bookingRepository, never()).save(any());
    }

    @Test
    void updateStatus_blankReturnNotes_stillSucceeds() {
        Booking booking = mobilisedBooking();
        when(bookingRepository.findById(4L)).thenReturn(Optional.of(booking));
        when(bookingItemRepository.findByBookingId(4L)).thenReturn(List.of());
        when(bookingRepository.save(booking)).thenReturn(booking);

        ReturnItemResponse response = returnService.updateStatus(4L, "COMPLETED", "");

        assertThat(booking.getStatus()).isEqualTo(Booking.BookingStatus.COMPLETED);
        assertThat(booking.getReturnNotes()).isEmpty();
        assertThat(response.returnNotes()).isEmpty();
    }

    @Test
    void updateStatus_invalidBookingStatusValue_throwsBadRequest() {
        Booking booking = mobilisedBooking();
        when(bookingRepository.findById(4L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> returnService.updateStatus(4L, "NOT_A_REAL_STATUS", "note"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid bookingStatus");

        verify(bookingRepository, never()).save(any());
    }
}
