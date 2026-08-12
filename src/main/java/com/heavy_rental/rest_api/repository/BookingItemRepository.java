package com.heavy_rental.rest_api.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.heavy_rental.rest_api.entity.Booking;
import com.heavy_rental.rest_api.entity.BookingItem;

public interface BookingItemRepository extends JpaRepository<BookingItem, Long> {
  List<BookingItem> findByBookingId(Long bookingId);
  List<BookingItem> findByAssetId(Long assetId);

  @Query("""
      SELECT DISTINCT bi.asset.id
      FROM BookingItem bi
      JOIN bi.booking b
      WHERE bi.asset.id IN :assetIds
        AND b.status IN :activeStatuses
        AND b.startDate <= :endDate
        AND b.endDate >= :startDate
      """)
  Set<Long> findAssetIdsWithOverlappingBooking(
      @Param("assetIds") Collection<Long> assetIds,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate,
      @Param("activeStatuses") Collection<Booking.BookingStatus> activeStatuses);

  @Query("""
      SELECT bi
      FROM BookingItem bi
      JOIN FETCH bi.booking b
      WHERE b.status IN :activeStatuses
      """)
  List<BookingItem> findByBookingStatusIn(@Param("activeStatuses") Collection<Booking.BookingStatus> activeStatuses);
}
