// Small companion test: confirms toReturnItemResponse() carries returnNotes alongside
// deliveryNotes without one clobbering the other (HR-100).
package com.heavy_rental.rest_api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.heavy_rental.rest_api.dto.ReturnItemResponse;
import com.heavy_rental.rest_api.entity.Booking;

class BookingMapperTest {

    private final BookingMapper mapper = new BookingMapper();

    @Test
    void toReturnItemResponse_includesReturnNotesAlongsideDeliveryNotes() {
        Booking booking = new Booking();
        booking.setId(4L);
        booking.setEndDate(LocalDate.now());
        booking.setSiteAddress("88 Tuas South Ave 3, Singapore 637311");
        booking.setDeliveryNotes("Crane assist required for offload");
        booking.setReturnNotes("Returned in good condition");
        booking.setStatus(Booking.BookingStatus.COMPLETED);

        ReturnItemResponse response = mapper.toReturnItemResponse(booking, List.of());

        assertThat(response.deliveryNotes()).isEqualTo("Crane assist required for offload");
        assertThat(response.returnNotes()).isEqualTo("Returned in good condition");
        assertThat(response.bookingStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void toReturnItemResponse_noItems_assetFieldsAreEmptyNotNull() {
        Booking booking = new Booking();
        booking.setId(9L);
        booking.setEndDate(LocalDate.now());
        booking.setDeliveryNotes("");
        booking.setReturnNotes("");
        booking.setStatus(Booking.BookingStatus.COMPLETED);

        ReturnItemResponse response = mapper.toReturnItemResponse(booking, List.of());

        assertThat(response.assetName()).isEmpty();
        assertThat(response.serialNumber()).isEmpty();
    }
}
