package com.heavy_rental.rest_api.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heavy_rental.rest_api.entity.RentalPlan;

public interface RentalPlanRepository extends JpaRepository<RentalPlan, Long> {
  List<RentalPlan> findByCustomerId(Long customerId);
  List<RentalPlan> findByStatus(RentalPlan.PlanStatus status);
}
