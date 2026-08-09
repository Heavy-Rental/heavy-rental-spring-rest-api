package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Stub, same reasoning as DepotController: RentalPlan entity/repo exist but have no
// controller yet. Exists so the frontend's rentalPlanApi.list() call doesn't 404 —
// CustomerPortal degrades this to an empty rentalPlans list gracefully already.
@RestController
@RequestMapping("/api/rental-plans")
public class RentalPlanController {

    @GetMapping
    public List<Object> list() {
        return List.of();
    }
}
