package com.heavy_rental.rest_api.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.ReturnRecord;

public interface ReturnRecordRepository extends JpaRepository<ReturnRecord, Long> {
  List<ReturnRecord> findByBookingId(Long bookingId);
  List<ReturnRecord> findByDriverId(Long driverId);

}
