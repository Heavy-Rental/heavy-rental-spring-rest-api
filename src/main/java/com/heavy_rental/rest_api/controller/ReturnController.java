package com.heavy_rental.rest_api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heavy_rental.rest_api.dto.ReturnItemResponse;
import com.heavy_rental.rest_api.dto.StatusUpdateRequest;
import com.heavy_rental.rest_api.service.ReturnService;

@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    @GetMapping
    public List<ReturnItemResponse> getTodaysReturns() {
        return returnService.getTodaysReturns();
    }

    @PatchMapping("/{bookingId}/status")
    public ReturnItemResponse updateStatus(@PathVariable Long bookingId, @RequestBody StatusUpdateRequest request) {
        return returnService.updateStatus(bookingId, request.bookingStatus());
    }
}
