// Small companion test: confirms toReturnItemResponse() carries returnNotes alongside
// deliveryNotes without one clobbering the other (HR-100).
package com.heavy_rental.rest_api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.heavy_rental.rest_api.dto.BookingItemLine;
import com.heavy_rental.rest_api.dto.ReturnItemResponse;
import com.heavy_rental.rest_api.entity.Asset;
import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;

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
    void toReturnItemResponse_noItems_itemsListIsEmptyNotNull() {
        Booking booking = new Booking();
        booking.setId(9L);
        booking.setEndDate(LocalDate.now());
        booking.setDeliveryNotes("");
        booking.setReturnNotes("");
        booking.setStatus(Booking.BookingStatus.COMPLETED);

        ReturnItemResponse response = mapper.toReturnItemResponse(booking, List.of());

        assertThat(response.items()).isEmpty();
    }

    @Test
    void toReturnItemResponse_singleItem_mapsToOneEntry() {
        Booking booking = new Booking();
        booking.setId(2L);
        booking.setEndDate(LocalDate.now());
        booking.setDeliveryNotes("");
        booking.setReturnNotes("");
        booking.setStatus(Booking.BookingStatus.COMPLETED);

        Asset excavator = new Asset();
        excavator.setName("CAT 320 Excavator");
        excavator.setSerialno("SN-EXC-000320");

        BookingItem item = new BookingItem();
        item.setId(3L);
        item.setAsset(excavator);

        ReturnItemResponse response = mapper.toReturnItemResponse(booking, List.of(item));

        assertThat(response.items()).containsExactly(
                new BookingItemLine(null, "CAT 320 Excavator", "SN-EXC-000320"));
    }

    @Test
    void toReturnItemResponse_multipleItems_sortedByIdAscending() {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setEndDate(LocalDate.now());
        booking.setDeliveryNotes("");
        booking.setReturnNotes("");
        booking.setStatus(Booking.BookingStatus.COMPLETED);

        Asset forklift = new Asset();
        forklift.setName("Toyota 8FD25 Forklift");
        forklift.setSerialno("SN-FKL-008FD25");

        Asset boomLift = new Asset();
        boomLift.setName("JLG 460SJ Boom Lift");
        boomLift.setSerialno("SN-BML-000460");

        // Deliberately constructed out of id order to prove sorting, not insertion order, wins.
        BookingItem itemTwo = new BookingItem();
        itemTwo.setId(2L);
        itemTwo.setAsset(forklift);

        BookingItem itemOne = new BookingItem();
        itemOne.setId(1L);
        itemOne.setAsset(boomLift);

        ReturnItemResponse response = mapper.toReturnItemResponse(booking, List.of(itemTwo, itemOne));

        assertThat(response.items()).containsExactly(
                new BookingItemLine(null, "JLG 460SJ Boom Lift", "SN-BML-000460"),
                new BookingItemLine(null, "Toyota 8FD25 Forklift", "SN-FKL-008FD25"));
    }
}
