package com.heavy_rental.rest_api.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.RentalPlanRecord;

public interface RentalPlanRecordRepository extends JpaRepository<RentalPlanRecord, Long> {
  List<RentalPlanRecord> findByRentalPlanId(Long rentalPlanId);
}
