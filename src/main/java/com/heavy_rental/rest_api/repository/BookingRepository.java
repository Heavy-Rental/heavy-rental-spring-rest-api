package com.heavy_rental.rest_api.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {
  List<Booking> findByCustomerId(Long customerId);
  List<Booking> findByStatus(Booking.BookingStatus status);
  List<Booking> findByCustomerIdAndStatus(Long customerId, Booking.BookingStatus status);
  List<Booking> findByStartDateAndStatusIn(LocalDate startDate, List<Booking.BookingStatus> statuses);
  List<Booking> findByEndDateAndStatusIn(LocalDate endDate, List<Booking.BookingStatus> statuses);
}
