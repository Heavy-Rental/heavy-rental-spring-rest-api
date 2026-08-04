package com.heavy_rental.rest_api.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.DeliveryRecord;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, Long> {
  List<DeliveryRecord> findByBookingId(Long bookingId);
  List<DeliveryRecord> findByDriverId(Long driverId);
}
