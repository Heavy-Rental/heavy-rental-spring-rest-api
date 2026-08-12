package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.dto.MonthlyUtilizationResponse;
import com.heavy_rental.rest_api.service.MonthlyUtilizationService;

@RestController
@RequestMapping("/api/monthly-utilization")
public class MonthlyUtilizationController {

    private final MonthlyUtilizationService monthlyUtilizationService;

    public MonthlyUtilizationController(MonthlyUtilizationService monthlyUtilizationService) {
        this.monthlyUtilizationService = monthlyUtilizationService;
    }

    @GetMapping
    public List<MonthlyUtilizationResponse> getTrailingSixMonths() {
        return monthlyUtilizationService.getTrailingSixMonths();
    }
}
